package com.djdeck.app;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.OutputStream;

// Saves DJ Deck recordings straight into the device's Recordings folder (MediaStore, no storage permission needed).
@CapacitorPlugin(name = "RecSave")
public class RecSavePlugin extends Plugin {
  private Uri uri;
  private OutputStream out;
  private String shown = "";

  private void closeQuiet() {
    try {
      if (out != null) out.close();
    } catch (Exception e) {
    }
    out = null;
  }

  @PluginMethod
  public synchronized void open(PluginCall call) {
    if (Build.VERSION.SDK_INT < 29) {
      call.reject("Direct save needs Android 10 or newer");
      return;
    }
    closeQuiet();
    String name = call.getString("name", "DJ-REC.mp3");
    String mime = call.getString("mime", "audio/mpeg");
    if (name == null || name.trim().isEmpty()) name = "DJ-REC.mp3";
    name = name.replaceAll("[^A-Za-z0-9._ -]", "_");
    String rel = Build.VERSION.SDK_INT >= 31 ? "Recordings" : "Music/Recordings";
    try {
      ContentValues v = new ContentValues();
      v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
      v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
      v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
      v.put(MediaStore.MediaColumns.IS_PENDING, 1);
      uri = getContext().getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
      if (uri == null) {
        call.reject("Could not create the file in " + rel);
        return;
      }
      out = getContext().getContentResolver().openOutputStream(uri, "w");
      if (out == null) {
        call.reject("Could not open the file for writing");
        return;
      }
      shown = rel + "/" + name;
      call.resolve();
    } catch (Exception e) {
      call.reject("Save failed: " + e.getMessage());
    }
  }

  @PluginMethod
  public synchronized void write(PluginCall call) {
    if (out == null) {
      call.reject("No file open");
      return;
    }
    try {
      byte[] b = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
      out.write(b);
      call.resolve();
    } catch (Exception e) {
      call.reject("Write failed: " + e.getMessage());
    }
  }

  @PluginMethod
  public synchronized void close(PluginCall call) {
    if (out == null || uri == null) {
      call.reject("No file open");
      return;
    }
    try {
      out.flush();
      closeQuiet();
      ContentValues v = new ContentValues();
      v.put(MediaStore.MediaColumns.IS_PENDING, 0);
      getContext().getContentResolver().update(uri, v, null, null);
      JSObject o = new JSObject();
      o.put("path", shown);
      uri = null;
      call.resolve(o);
    } catch (Exception e) {
      call.reject("Finish failed: " + e.getMessage());
    }
  }

  @PluginMethod
  public synchronized void abort(PluginCall call) {
    closeQuiet();
    try {
      if (uri != null) getContext().getContentResolver().delete(uri, null, null);
    } catch (Exception e) {
    }
    uri = null;
    call.resolve();
  }
}
