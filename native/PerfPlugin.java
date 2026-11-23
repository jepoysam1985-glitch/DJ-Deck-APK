package com.djdeck.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Perf")
public class PerfPlugin extends Plugin {

  private static String rd(String p) {
    java.io.BufferedReader br = null;
    try {
      br = new java.io.BufferedReader(new java.io.FileReader(p));
      StringBuilder sb = new StringBuilder();
      String l; int n = 0;
      while ((l = br.readLine()) != null && n++ < 600) sb.append(l).append('\n');
      return sb.toString();
    } catch (Throwable t) {
      return null;
    } finally {
      try { if (br != null) br.close(); } catch (Throwable t) { }
    }
  }

  private static long rl(String p) {
    String s = rd(p);
    if (s == null) return -1;
    s = s.trim();
    try { return Long.parseLong(s.split("\\s+")[0]); } catch (Throwable t) { return -1; }
  }

  private static long kv(String text, String key) {
    if (text == null) return -1;
    for (String line : text.split("\n")) {
      if (line.startsWith(key)) {
        try { return Long.parseLong(line.substring(key.length()).replaceAll("[^0-9]", "")); } catch (Throwable t) { return -1; }
      }
    }
    return -1;
  }

  @PluginMethod
  public void snapshot(PluginCall call) {
    JSObject o = new JSObject();
    try {
      android.content.Context ctx = getContext();
      int n = Runtime.getRuntime().availableProcessors();
      long[] tot = new long[n];
      long[] idle = new long[n];
      for (int i = 0; i < n; i++) { tot[i] = -1; idle[i] = -1; }
      try {
        String stat = rd("/proc/stat");
        if (stat != null) {
          for (String line : stat.split("\n")) {
            if (line.startsWith("cpu") && line.length() > 3 && Character.isDigit(line.charAt(3))) {
              String[] f = line.trim().split("\\s+");
              int idx = Integer.parseInt(f[0].substring(3));
              if (idx < n && f.length > 5) {
                long t = 0;
                for (int k = 1; k < f.length && k <= 8; k++) t += Long.parseLong(f[k]);
                tot[idx] = t;
                idle[idx] = Long.parseLong(f[4]) + Long.parseLong(f[5]);
              }
            }
          }
        }
      } catch (Throwable t) { }
      JSArray cores = new JSArray();
      for (int i = 0; i < n; i++) {
        JSObject c = new JSObject();
        String b = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
        c.put("cur", rl(b + "scaling_cur_freq"));
        c.put("max", rl(b + "cpuinfo_max_freq"));
        c.put("tot", tot[i]);
        c.put("idle", idle[i]);
        cores.put(c);
      }
      o.put("cores", cores);
      o.put("model", Build.HARDWARE == null ? "cpu" : Build.HARDWARE);
      o.put("wallMs", SystemClock.elapsedRealtime());
      o.put("cpuMs", android.os.Process.getElapsedCpuTime());
      o.put("cpuTime", android.os.Process.getElapsedCpuTime());
      o.put("pid", android.os.Process.myPid());
      long up = -1;
      if (Build.VERSION.SDK_INT >= 24) up = SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime();
      o.put("upMs", up);

      ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
      ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
      am.getMemoryInfo(mi);
      o.put("total", mi.totalMem);
      o.put("avail", mi.availMem);
      Debug.MemoryInfo dm = new Debug.MemoryInfo();
      Debug.getMemoryInfo(dm);
      o.put("pss", (long) dm.getTotalPss() * 1024L);
      Runtime rt = Runtime.getRuntime();
      o.put("java", rt.totalMemory() - rt.freeMemory());
      o.put("javaMax", rt.maxMemory());
      o.put("nat", Debug.getNativeHeapAllocatedSize());
      String status = rd("/proc/self/status");
      long rss = kv(status, "VmRSS:");
      o.put("rss", rss < 0 ? -1 : rss * 1024L);
      o.put("threads", kv(status, "Threads:"));

      try {
        StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
        o.put("stTotal", sf.getTotalBytes());
        o.put("stFree", sf.getAvailableBytes());
      } catch (Throwable t) { o.put("stTotal", -1); o.put("stFree", -1); }

      o.put("rx", TrafficStats.getTotalRxBytes());
      o.put("tx", TrafficStats.getTotalTxBytes());

      Intent bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      if (bi != null) {
        int lv = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        o.put("bat", (lv < 0 || sc <= 0) ? -1 : (lv * 100 / sc));
        o.put("batStatus", bi.getIntExtra(BatteryManager.EXTRA_STATUS, 0));
        o.put("batHealth", bi.getIntExtra(BatteryManager.EXTRA_HEALTH, 0));
        o.put("batTemp", bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1));
        o.put("batVolt", bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1));
      } else {
        o.put("bat", -1);
      }
      try {
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        o.put("batNow", bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
        o.put("batCap", bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER));
        long ct = -1;
        if (Build.VERSION.SDK_INT >= 28) ct = bm.computeChargeTimeRemaining();
        o.put("batTime", ct);
      } catch (Throwable t) { }

      long gpu = -1;
      String g = rd("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage");
      if (g != null) {
        try { gpu = Long.parseLong(g.replaceAll("[^0-9]", "")); } catch (Throwable t) { }
      }
      o.put("gpu", gpu);
    } catch (Throwable t) {
      o.put("error", String.valueOf(t));
    }
    call.resolve(o);
  }

  @PluginMethod
  public void gc(PluginCall call) {
    System.gc();
    Runtime.getRuntime().gc();
    call.resolve();
  }
}
