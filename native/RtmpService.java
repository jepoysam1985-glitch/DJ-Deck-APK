package com.djdeck.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import com.pedro.common.ConnectChecker;
import com.pedro.library.rtmp.RtmpDisplay;

public class RtmpService extends Service implements ConnectChecker {
  private static final String CH = "djdeck_live";
  private RtmpDisplay display;
  private final Handler ui = new Handler(Looper.getMainLooper());

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }

  @Override
  public int onStartCommand(Intent in, int flags, int startId) {
    if (in == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    try {
      startFg();
      begin(in);
    } catch (Exception e) {
      RtmpPlugin.emit("error", e.getMessage() == null ? e.toString() : e.getMessage());
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  private void startFg() {
    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26) {
      nm.createNotificationChannel(new NotificationChannel(CH, "DJ Deck LIVE", NotificationManager.IMPORTANCE_LOW));
    }
    Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
    Notification n = b.setContentTitle("DJ Deck LIVE").setContentText("Streaming live...")
        .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build();
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(8731, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    } else {
      startForeground(8731, n);
    }
  }

  private void begin(Intent in) throws Exception {
    int rc = in.getIntExtra("rc", 0);
    Intent data = in.getParcelableExtra("data");
    String url = in.getStringExtra("url");
    String key = in.getStringExtra("key");
    String res = in.getStringExtra("res");
    int fps = in.getIntExtra("fps", 30);
    int vb = in.getIntExtra("vb", 4000);
    int ab = in.getIntExtra("ab", 128);
    int kf = in.getIntExtra("kf", 2);
    int sr = in.getIntExtra("sr", 44100);

    int w = 1280, h = 720;
    try {
      String[] p = res.toLowerCase().split("x");
      w = Integer.parseInt(p[0].trim());
      h = Integer.parseInt(p[1].trim());
    } catch (Exception e) { }
    if (h > w) { int t = w; w = h; h = t; }
    w -= w % 2;
    h -= h % 2;
    int dpi = getResources().getDisplayMetrics().densityDpi;

    display = new RtmpDisplay(this, true, this);
    display.setIntentResult(rc, data);
    if (!display.prepareVideo(w, h, fps, vb * 1000, 0, dpi)) {
      throw new Exception("could not prepare the video encoder");
    }
    boolean au = false;
    if (Build.VERSION.SDK_INT >= 29) {
      au = display.prepareInternalAudio(ab * 1000, sr, true, false, false);
    }
    if (!au) au = display.prepareAudio(ab * 1000, sr, true, false, false);
    if (!au) throw new Exception("could not prepare the audio encoder");

    String base = url.trim();
    while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    display.startStream(base + "/" + key.trim());
  }

  @Override
  public void onDestroy() {
    try {
      if (display != null && display.isStreaming()) display.stopStream();
    } catch (Exception e) { }
    display = null;
    RtmpPlugin.emit("stopped", "");
    super.onDestroy();
  }

  @Override public void onConnectionStarted(String url) { }
  @Override public void onConnectionSuccess() { RtmpPlugin.emit("live", ""); }
  @Override public void onConnectionFailed(String reason) {
    RtmpPlugin.emit("failed", reason);
    ui.post(() -> stopSelf());
  }
  @Override public void onNewBitrate(long bitrate) { }
  @Override public void onDisconnect() { }
  @Override public void onAuthError() { RtmpPlugin.emit("failed", "auth error: wrong stream key"); ui.post(() -> stopSelf()); }
  @Override public void onAuthSuccess() { }
}
