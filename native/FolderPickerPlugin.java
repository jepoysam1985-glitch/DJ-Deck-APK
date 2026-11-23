package com.djdeck.app;

import android.app.Activity;
import android.content.ContentUris;
import android.os.Build;
import android.provider.MediaStore;
import com.getcapacitor.PermissionState;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Base64;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Locale;

// Native folder picker (Storage Access Framework) — kasi hindi gumagana ang
// showDirectoryPicker / <input webkitdirectory> sa Android WebView.
@CapacitorPlugin(
  name = "FolderPicker",
  permissions = {
    @Permission(alias = "aud33", strings = { "android.permission.READ_MEDIA_AUDIO" }),
    @Permission(alias = "aud", strings = { "android.permission.READ_EXTERNAL_STORAGE" })
  }
)
public class FolderPickerPlugin extends Plugin {

  private static final String[] EXT = {
    ".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".oga", ".opus", ".wma", ".amr", ".weba"
  };

  private boolean isAudio(String name, String mime) {
    if (mime != null && mime.startsWith("audio/")) return true;
    String n = name.toLowerCase(Locale.ROOT);
    for (String e : EXT) if (n.endsWith(e)) return true;
    return false;
  }

  @PluginMethod
  public void pick(PluginCall call) {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    i.addFlags(
      Intent.FLAG_GRANT_READ_URI_PERMISSION
        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    );
    startActivityForResult(call, i, "pickResult");
  }

  @ActivityCallback
  private void pickResult(PluginCall call, ActivityResult result) {
    if (call == null) return;
    if (
      result == null
        || result.getResultCode() != Activity.RESULT_OK
        || result.getData() == null
        || result.getData().getData() == null
    ) {
      call.reject("cancelled");
      return;
    }
    Uri tree = result.getData().getData();
    ContentResolver cr = getContext().getContentResolver();
    try {
      cr.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (Exception e) { }
    String name = "Music";
    try {
      Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
      Cursor c = cr.query(root, new String[] { Document.COLUMN_DISPLAY_NAME }, null, null, null);
      if (c != null) {
        try {
          if (c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
        } finally {
          c.close();
        }
      }
    } catch (Exception e) { }
    JSObject r = new JSObject();
    r.put("uri", tree.toString());
    r.put("name", name);
    call.resolve(r);
  }

  private void walk(Uri tree, String docId, String rel, JSArray out, int depth) {
    if (depth > 24) return;
    ContentResolver cr = getContext().getContentResolver();
    Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
    Cursor c = null;
    try {
      c = cr.query(
        kids,
        new String[] {
          Document.COLUMN_DOCUMENT_ID,
          Document.COLUMN_DISPLAY_NAME,
          Document.COLUMN_MIME_TYPE,
          Document.COLUMN_SIZE
        },
        null,
        null,
        null
      );
      if (c == null) return;
      while (c.moveToNext()) {
        String id = c.getString(0);
        String name = c.getString(1);
        String mime = c.getString(2);
        long size = c.isNull(3) ? 0 : c.getLong(3);
        if (id == null || name == null) continue;
        String path = rel.isEmpty() ? name : rel + "/" + name;
        if (Document.MIME_TYPE_DIR.equals(mime)) {
          if (!name.startsWith(".")) walk(tree, id, path, out, depth + 1);
        } else if (isAudio(name, mime)) {
          JSObject o = new JSObject();
          o.put("name", name);
          o.put("path", path);
          o.put("uri", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString());
          o.put("size", size);
          out.put(o);
        }
      }
    } finally {
      if (c != null) c.close();
    }
  }

  // ===== AUTO SCAN: lahat ng music sa phone via MediaStore (walang kailangang pumili ng folder) =====
  private String audAlias() {
    return Build.VERSION.SDK_INT >= 33 ? "aud33" : "aud";
  }

  @PluginMethod
  public void scanMedia(PluginCall call) {
    String a = audAlias();
    if (getPermissionState(a) != PermissionState.GRANTED) {
      requestPermissionForAlias(a, call, "mediaPerm");
      return;
    }
    doScanMedia(call);
  }

  @PermissionCallback
  private void mediaPerm(PluginCall call) {
    if (getPermissionState(audAlias()) == PermissionState.GRANTED) doScanMedia(call);
    else call.reject("permission denied");
  }

  private void doScanMedia(final PluginCall call) {
    new Thread(() -> {
      Cursor c = null;
      try {
        ContentResolver cr = getContext().getContentResolver();
        boolean q = Build.VERSION.SDK_INT >= 29;
        Uri base = q
          ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
          : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String relCol = q ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA;
        String[] proj = new String[] {
          MediaStore.Audio.Media._ID,
          MediaStore.Audio.Media.DISPLAY_NAME,
          MediaStore.Audio.Media.SIZE,
          relCol
        };
        String sel =
          MediaStore.Audio.Media.IS_MUSIC + "!=0 AND " +
          MediaStore.Audio.Media.IS_RINGTONE + "=0 AND " +
          MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND " +
          MediaStore.Audio.Media.IS_ALARM + "=0";
        c = cr.query(base, proj, sel, null, MediaStore.Audio.Media.DISPLAY_NAME + " COLLATE NOCASE ASC");
        JSArray arr = new JSArray();
        if (c != null) {
          while (c.moveToNext()) {
            long id = c.getLong(0);
            String name = c.getString(1);
            long size = c.isNull(2) ? 0 : c.getLong(2);
            String rel = c.getString(3);
            if (name == null) continue;
            String folder = "";
            if (rel != null) {
              if (q) {
                folder = rel;
              } else {
                int k = rel.lastIndexOf('/');
                folder = k > 0 ? rel.substring(0, k) : "";
                folder = folder.replace("/storage/emulated/0/", "").replace("/sdcard/", "");
              }
              while (folder.endsWith("/")) folder = folder.substring(0, folder.length() - 1);
            }
            JSObject o = new JSObject();
            o.put("name", name);
            o.put("path", folder.isEmpty() ? name : folder + "/" + name);
            o.put("uri", ContentUris.withAppendedId(base, id).toString());
            o.put("size", size);
            arr.put(o);
          }
        }
        JSObject r = new JSObject();
        r.put("files", arr);
        call.resolve(r);
      } catch (Exception e) {
        call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
      } finally {
        if (c != null) c.close();
      }
    }).start();
  }

  @PluginMethod
  public void list(final PluginCall call) {
    final String u = call.getString("uri", "");
    new Thread(() -> {
      try {
        Uri tree = Uri.parse(u);
        JSArray arr = new JSArray();
        walk(tree, DocumentsContract.getTreeDocumentId(tree), "", arr, 0);
        JSObject r = new JSObject();
        r.put("files", arr);
        call.resolve(r);
      } catch (Exception e) {
        call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
      }
    }).start();
  }

  @PluginMethod
  public void read(final PluginCall call) {
    final String u = call.getString("uri", "");
    final int off = call.getInt("offset", 0);
    final int len = Math.max(1, Math.min(call.getInt("length", 1048576), 4194304));
    new Thread(() -> {
      try {
        ContentResolver cr = getContext().getContentResolver();
        byte[] buf = new byte[len];
        int n = 0;
        boolean done = false;
        ParcelFileDescriptor pfd = null;
        try {
          pfd = cr.openFileDescriptor(Uri.parse(u), "r");
          FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
          FileChannel ch = fis.getChannel();
          ch.position(off);
          ByteBuffer bb = ByteBuffer.wrap(buf);
          while (n < len) {
            int k = ch.read(bb);
            if (k < 0) break;
            n += k;
          }
          done = true;
        } catch (Exception e) {
          n = 0;
        } finally {
          try { if (pfd != null) pfd.close(); } catch (Exception e) { }
        }
        if (!done) {
          InputStream in = cr.openInputStream(Uri.parse(u));
          try {
            long skip = off;
            while (skip > 0) {
              long s = in.skip(skip);
              if (s <= 0) break;
              skip -= s;
            }
            while (n < len) {
              int k = in.read(buf, n, len - n);
              if (k < 0) break;
              n += k;
            }
          } finally {
            in.close();
          }
        }
        JSObject r = new JSObject();
        r.put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP));
        r.put("n", n);
        call.resolve(r);
      } catch (Exception e) {
        call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
      }
    }).start();
  }
}
