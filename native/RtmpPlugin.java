package com.djdeck.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Rtmp")
public class RtmpPlugin extends Plugin {
  static RtmpPlugin inst;

  @Override
  public void load() {
    inst = this;
  }

  static void emit(String status, String reason) {
    RtmpPlugin p = inst;
    if (p == null) return;
    JSObject o = new JSObject();
    o.put("status", status);
    o.put("reason", reason == null ? "" : reason);
    p.notifyListeners("state", o);
  }

  @PluginMethod
  public void start(PluginCall call) {
    String url = call.getString("url", "");
    String key = call.getString("key", "");
    if (url == null || url.trim().isEmpty() || key == null || key.trim().isEmpty()) {
      call.reject("walang server URL o stream key");
      return;
    }
    if (Build.VERSION.SDK_INT < 29) {
      call.reject("Kailangan ng Android 10 pataas para makuha ang audio ng DJ Deck");
      return;
    }
    MediaProjectionManager m = (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    startActivityForResult(call, m.createScreenCaptureIntent(), "projResult");
  }

  @ActivityCallback
  private void projResult(PluginCall call, ActivityResult result) {
    if (call == null) return;
    if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
      call.reject("Hindi pinayagan ang screen capture");
      return;
    }
    Intent i = new Intent(getContext(), RtmpService.class);
    i.putExtra("rc", result.getResultCode());
    i.putExtra("data", result.getData());
    i.putExtra("url", call.getString("url", ""));
    i.putExtra("key", call.getString("key", ""));
    i.putExtra("res", call.getString("res", "1280x720"));
    i.putExtra("fps", call.getInt("fps", 30));
    i.putExtra("vb", call.getInt("vb", 4000));
    i.putExtra("ab", call.getInt("ab", 128));
    i.putExtra("kf", call.getInt("kf", 2));
    i.putExtra("sr", call.getInt("sr", 44100));
    ContextCompat.startForegroundService(getContext(), i);
    call.resolve();
  }

  @PluginMethod
  public void stop(PluginCall call) {
    getContext().stopService(new Intent(getContext(), RtmpService.class));
    call.resolve();
  }
}
