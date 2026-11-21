package com.djdeck.app;

import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLSocketFactory;

@CapacitorPlugin(name = "TcpSource")
public class TcpSourcePlugin extends Plugin {
  private volatile Socket sock;
  private volatile OutputStream out;
  private final ExecutorService ex = Executors.newSingleThreadExecutor();

  private String readLine(InputStream in) throws Exception {
    StringBuilder sb = new StringBuilder();
    int c;
    while ((c = in.read()) != -1) {
      if (c == '\n') break;
      if (c != '\r') sb.append((char) c);
    }
    return sb.toString();
  }

  private void shut() {
    try { if (sock != null) sock.close(); } catch (Exception e) { }
    sock = null;
    out = null;
  }

  @PluginMethod
  public void open(final PluginCall call) {
    new Thread(() -> {
      try {
        shut();
        String type = call.getString("type", "icecast");
        String host = call.getString("host", "");
        int port = call.getInt("port", 8000);
        String mount = call.getString("mount", "");
        String user = call.getString("user", "source");
        String pass = call.getString("pass", "");
        int kbps = call.getInt("kbps", 128);
        boolean tls = call.getBoolean("tls", false);
        boolean sc = "shoutcast".equals(type);
        int p = sc ? port + 1 : port;

        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, p), 8000);
        Socket s = plain;
        if (tls) {
          s = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, host, p, true);
        }
        s.setTcpNoDelay(true);
        s.setSoTimeout(8000);
        OutputStream o = s.getOutputStream();
        InputStream in = s.getInputStream();

        if (sc) {
          String pw = pass;
          if (mount != null && !mount.isEmpty() && !"1".equals(mount)) pw = pass + ":#" + mount;
          o.write((pw + "\r\n").getBytes("UTF-8"));
          o.flush();
          String r = readLine(in);
          if (!r.startsWith("OK")) {
            s.close();
            call.reject(r.isEmpty() ? "walang sagot ang server" : r);
            return;
          }
          s.setSoTimeout(1500);
          try {
            while (!readLine(in).isEmpty()) { }
          } catch (SocketTimeoutException e) { }
          String h = "icy-name:DJ Deck\r\nicy-pub:0\r\nicy-br:" + kbps + "\r\ncontent-type:audio/mpeg\r\n\r\n";
          o.write(h.getBytes("UTF-8"));
          o.flush();
        } else {
          String auth = Base64.encodeToString((user + ":" + pass).getBytes("UTF-8"), Base64.NO_WRAP);
          String m = (mount == null || mount.isEmpty()) ? "/" : (mount.startsWith("/") ? mount : "/" + mount);
          String h = "PUT " + m + " HTTP/1.1\r\n"
            + "Host: " + host + ":" + port + "\r\n"
            + "Authorization: Basic " + auth + "\r\n"
            + "User-Agent: DJDeck/1.0\r\n"
            + "Content-Type: audio/mpeg\r\n"
            + "ice-name: DJ Deck\r\n"
            + "ice-public: 0\r\n"
            + "ice-audio-info: bitrate=" + kbps + "\r\n\r\n";
          o.write(h.getBytes("UTF-8"));
          o.flush();
          s.setSoTimeout(1500);
          try {
            String st = readLine(in);
            if (!(st.contains(" 200") || st.contains(" 100"))) {
              s.close();
              call.reject(st.isEmpty() ? "walang sagot ang server" : st);
              return;
            }
          } catch (SocketTimeoutException e) { }
        }

        s.setSoTimeout(0);
        sock = s;
        out = o;
        final Socket mine = s;
        final InputStream din = in;
        new Thread(() -> {
          try {
            byte[] buf = new byte[512];
            while (din.read(buf) != -1) { }
          } catch (Exception e) { }
          if (sock == mine) {
            shut();
            notifyListeners("closed", new JSObject());
          }
        }).start();
        call.resolve();
      } catch (Exception e) {
        shut();
        call.reject(e.getMessage() == null ? e.toString() : e.getMessage());
      }
    }).start();
  }

  @PluginMethod
  public void write(final PluginCall call) {
    final OutputStream o = out;
    if (o == null) {
      call.reject("closed");
      return;
    }
    final byte[] d = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
    ex.execute(() -> {
      try {
        o.write(d);
        o.flush();
      } catch (Exception e) {
        if (out == o) {
          shut();
          notifyListeners("closed", new JSObject());
        }
      }
    });
    call.resolve();
  }

  @PluginMethod
  public void close(PluginCall call) {
    shut();
    call.resolve();
  }
}
