package ua.family.shakhy;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Шахи по Wi-Fi.
 * Гра (дошка, правила, інтерфейс) живе в assets/index.html.
 * Цей клас відповідає лише за мережу:
 *  - "Створити гру": відкриває TCP-порт 50505 і щосекунди кричить у мережу (UDP 50506) "тут є гра";
 *  - "Приєднатися": слухає UDP 50506, показує знайдені ігри, підключається по TCP.
 * Повідомлення між телефонами — рядки JSON, по одному на рядок.
 */
public class MainActivity extends Activity {

    static final int TCP_PORT = 50505;
    static final int UDP_PORT = 50506;
    static final String MAGIC = "SHAKHY1";
    static final Charset UTF8 = Charset.forName("UTF-8");

    private WebView web;
    private final Object lock = new Object();
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    private volatile String hostName = "";
    private volatile ServerSocket server;
    private volatile Socket conn;
    private volatile BufferedWriter out;
    private volatile int scanGen = 0;
    private volatile int hostGen = 0;
    private volatile DatagramSocket scanSocket;
    private volatile boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null) web.evaluateJavascript("onBack()", null);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopHostingInternal();
        stopScanInternal();
        closeConn();
        sender.shutdownNow();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    // ---------- Виклик функцій у грі (JavaScript) ----------
    private void js(final String fn, final String arg) {
        if (destroyed) return;
        final String code = fn + "(" + JSONObject.quote(arg == null ? "" : arg) + ")";
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (web != null && !destroyed) web.evaluateJavascript(code, null);
            }
        });
    }

    // ---------- З'єднання ----------
    private void startConnection(final Socket s) {
        synchronized (lock) {
            closeConnLocked();
            try {
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), UTF8));
                conn = s;
            } catch (IOException e) {
                try { s.close(); } catch (IOException ignored) { }
                return;
            }
        }
        InetAddress a = s.getInetAddress();
        js("onConnected", a != null ? a.getHostAddress() : "");
        new Thread(new Runnable() {
            @Override public void run() { readLoop(s); }
        }).start();
    }

    private void readLoop(Socket s) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.length() > 0) js("onMessage", line);
            }
        } catch (IOException ignored) { }
        boolean wasCurrent;
        synchronized (lock) {
            wasCurrent = (conn == s);
            if (wasCurrent) closeConnLocked();
        }
        if (wasCurrent) js("onDisconnected", "");
    }

    private void closeConn() {
        synchronized (lock) { closeConnLocked(); }
    }

    private void closeConnLocked() {
        Socket c = conn;
        conn = null;
        out = null;
        if (c != null) { try { c.close(); } catch (IOException ignored) { } }
    }

    // ---------- Створення гри ----------
    private void acceptLoop(int gen) {
        ServerSocket ss = null;
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(TCP_PORT));
                    break;
                } catch (IOException e) {
                    if (ss != null) { try { ss.close(); } catch (IOException ignored) { } ss = null; }
                    if (attempt >= 10 || gen != hostGen) throw e;
                    try { Thread.sleep(300); } catch (InterruptedException ie) { throw e; }
                }
            }
            if (gen != hostGen) return;
            server = ss;
            js("onHosting", ipList());
            while (gen == hostGen) {
                Socket s = ss.accept();
                if (gen != hostGen) { s.close(); break; }
                startConnection(s); // нове підключення замінює старе (повернення після обриву)
            }
        } catch (IOException e) {
            if (gen == hostGen) js("onNetError", "Не вдалося створити гру. Перезапусти застосунок і спробуй ще.");
        } finally {
            if (ss != null) { try { ss.close(); } catch (IOException ignored) { } }
        }
    }

    private void broadcastLoop(int gen) {
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket();
            ds.setBroadcast(true);
            while (gen == hostGen) {
                if (conn == null) {
                    byte[] data = (MAGIC + "|" + hostName).getBytes(UTF8);
                    for (InetAddress a : broadcastAddrs()) {
                        try { ds.send(new DatagramPacket(data, data.length, a, UDP_PORT)); }
                        catch (Exception ignored) { }
                    }
                }
                Thread.sleep(1000);
            }
        } catch (Exception ignored) {
        } finally {
            if (ds != null) ds.close();
        }
    }

    private void stopHostingInternal() {
        hostGen++;
        ServerSocket ss = server;
        server = null;
        if (ss != null) { try { ss.close(); } catch (IOException ignored) { } }
    }

    // ---------- Пошук ігор ----------
    private void scanLoop(int gen) {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock mlock = null;
        DatagramSocket ds = null;
        try {
            if (wm != null) {
                mlock = wm.createMulticastLock("shakhy");
                mlock.setReferenceCounted(false);
                mlock.acquire();
            }
            for (int attempt = 0; ds == null && attempt < 10 && gen == scanGen; attempt++) {
                try {
                    DatagramSocket d = new DatagramSocket(null);
                    d.setReuseAddress(true);
                    d.setBroadcast(true);
                    d.bind(new InetSocketAddress(UDP_PORT));
                    d.setSoTimeout(1000);
                    ds = d;
                } catch (IOException e) {
                    Thread.sleep(300);
                }
            }
            if (ds == null) {
                if (gen == scanGen) js("onNetError", "Не вдалося почати пошук. Спробуй ще раз.");
                return;
            }
            scanSocket = ds;
            byte[] buf = new byte[512];
            while (gen == scanGen) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { ds.receive(p); } catch (SocketTimeoutException t) { continue; }
                String msg = new String(p.getData(), 0, p.getLength(), UTF8);
                if (msg.startsWith(MAGIC + "|") && p.getAddress() != null) {
                    JSONObject o = new JSONObject();
                    o.put("name", msg.substring(MAGIC.length() + 1));
                    o.put("ip", p.getAddress().getHostAddress());
                    js("onHostFound", o.toString());
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (ds != null) ds.close();
            if (scanSocket == ds) scanSocket = null;
            if (mlock != null && mlock.isHeld()) mlock.release();
        }
    }

    private void stopScanInternal() {
        scanGen++;
        DatagramSocket d = scanSocket;
        scanSocket = null;
        if (d != null) d.close();
    }

    // ---------- Адреси ----------
    private List<InetAddress> broadcastAddrs() {
        List<InetAddress> list = new ArrayList<InetAddress>();
        try { list.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) { }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !list.contains(b)) list.add(b);
                }
            }
        } catch (Exception ignored) { }
        return list;
    }

    private String ipList() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                if (n != null && (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy"))) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) { }
        return sb.toString();
    }

    // ---------- Міст для JavaScript ----------
    public class Bridge {
        @JavascriptInterface
        public void host(String name) {
            stopScanInternal();
            stopHostingInternal();
            closeConn();
            hostName = name == null ? "" : name;
            final int gen = ++hostGen;
            new Thread(new Runnable() { @Override public void run() { acceptLoop(gen); } }).start();
            new Thread(new Runnable() { @Override public void run() { broadcastLoop(gen); } }).start();
        }

        @JavascriptInterface
        public void startScan() {
            stopScanInternal();
            final int gen = ++scanGen;
            new Thread(new Runnable() { @Override public void run() { scanLoop(gen); } }).start();
        }

        @JavascriptInterface
        public void stopScan() { stopScanInternal(); }

        @JavascriptInterface
        public void connect(final String ip) {
            stopScanInternal();
            new Thread(new Runnable() {
                @Override public void run() {
                    Socket s = new Socket();
                    try {
                        s.connect(new InetSocketAddress(ip, TCP_PORT), 5000);
                        startConnection(s);
                    } catch (Exception e) {
                        try { s.close(); } catch (IOException ignored) { }
                        js("onConnectFailed", ip);
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public boolean send(final String line) {
            if (out == null || sender.isShutdown()) return false;
            sender.execute(new Runnable() {
                @Override public void run() {
                    BufferedWriter w = out;
                    if (w == null) return;
                    try {
                        w.write(line.replace('\n', ' '));
                        w.write('\n');
                        w.flush();
                    } catch (IOException e) {
                        Socket c = conn;
                        if (c != null) { try { c.close(); } catch (IOException ignored) { } }
                    }
                }
            });
            return true;
        }

        @JavascriptInterface
        public void disconnect() { closeConn(); }

        @JavascriptInterface
        public void stopHosting() { stopHostingInternal(); }

        @JavascriptInterface
        public void stopAll() {
            stopHostingInternal();
            stopScanInternal();
            closeConn();
        }

        @JavascriptInterface
        public String myIps() { return ipList(); }

        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) v.vibrate(ms);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void exit() {
            runOnUiThread(new Runnable() { @Override public void run() { finish(); } });
        }
    }
}
