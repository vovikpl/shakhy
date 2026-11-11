package ua.family.shakhy;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;
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
        nearbyStopAll();
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

    // ================= ГРА ПОРУЧ (Google Nearby: Bluetooth + Wi-Fi Direct) =================

    static final String SERVICE_ID = "ua.family.shakhy.chess";
    static final int PERM_REQ = 77;

    private ConnectionsClient nearby;
    private volatile String transport = "wifi";
    private volatile String myName = "Гравець";
    private volatile String nearbyPeer = null;
    private volatile boolean nearbyHosting = false;
    private Runnable pendingAfterPerms = null;

    private ConnectionsClient nc() {
        if (nearby == null) nearby = Nearby.getConnectionsClient(this);
        return nearby;
    }

    private final PayloadCallback payloadCb = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String endpointId, Payload payload) {
            if (payload.getType() != Payload.Type.BYTES) return;
            byte[] b = payload.asBytes();
            if (b == null) return;
            String text = new String(b, UTF8);
            for (String line : text.split("\n")) {
                if (line.length() > 0) js("onMessage", line);
            }
        }

        @Override
        public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) { }
    };

    private final ConnectionLifecycleCallback lifecycleCb = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(String endpointId, ConnectionInfo info) {
            nc().acceptConnection(endpointId, payloadCb);
        }

        @Override
        public void onConnectionResult(String endpointId, ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                String old = nearbyPeer;
                if (old != null && !old.equals(endpointId)) nc().disconnectFromEndpoint(old);
                nearbyPeer = endpointId;
                if (nearbyHosting) nc().stopAdvertising();
                nc().stopDiscovery();
                js("onConnected", endpointId);
            } else if (!nearbyHosting) {
                js("onConnectFailed", endpointId);
            }
        }

        @Override
        public void onDisconnected(String endpointId) {
            if (endpointId.equals(nearbyPeer)) {
                nearbyPeer = null;
                js("onDisconnected", "");
                if (nearbyHosting) startAdvertisingInternal();
            }
        }
    };

    private final EndpointDiscoveryCallback discoveryCb = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
            if (!SERVICE_ID.equals(info.getServiceId())) return;
            try {
                JSONObject o = new JSONObject();
                o.put("name", info.getEndpointName());
                o.put("ip", endpointId);
                js("onHostFound", o.toString());
            } catch (Exception ignored) { }
        }

        @Override
        public void onEndpointLost(String endpointId) {
            js("onHostLost", endpointId);
        }
    };

    private void startAdvertisingInternal() {
        AdvertisingOptions opts = new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build();
        nc().startAdvertising(myName, SERVICE_ID, lifecycleCb, opts)
            .addOnSuccessListener(v -> js("onHosting", ""))
            .addOnFailureListener(e -> {
                if (nearbyHosting && !isAlreadyRunning(e))
                    js("onNearbyFail", "Не вдалося створити гру. Перевір, що увімкнено Bluetooth і геолокацію.");
            });
    }

    private void startDiscoveryInternal() {
        DiscoveryOptions opts = new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build();
        nc().stopDiscovery();
        nc().startDiscovery(SERVICE_ID, discoveryCb, opts)
            .addOnFailureListener(e -> {
                if (!isAlreadyRunning(e))
                    js("onNearbyFail", "Не вдалося почати пошук. Перевір, що увімкнено Bluetooth і геолокацію.");
            });
    }

    private boolean isAlreadyRunning(Exception e) {
        String m = e == null ? null : e.getMessage();
        return m != null && (m.contains("ALREADY") || m.contains("8001") || m.contains("8002"));
    }

    private void nearbyStopAll() {
        nearbyHosting = false;
        nearbyPeer = null;
        if (nearby != null) {
            nearby.stopAdvertising();
            nearby.stopDiscovery();
            nearby.stopAllEndpoints();
        }
    }

    // ---------- Дозволи ----------
    private String[] neededPerms() {
        List<String> p = new ArrayList<String>();
        int sdk = Build.VERSION.SDK_INT;
        p.add("android.permission.ACCESS_FINE_LOCATION");
        p.add("android.permission.ACCESS_COARSE_LOCATION");
        if (sdk >= 31) {
            p.add("android.permission.BLUETOOTH_SCAN");
            p.add("android.permission.BLUETOOTH_ADVERTISE");
            p.add("android.permission.BLUETOOTH_CONNECT");
        }
        if (sdk >= 33) p.add("android.permission.NEARBY_WIFI_DEVICES");
        return p.toArray(new String[0]);
    }

    private boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean permsOk() {
        boolean loc = has("android.permission.ACCESS_FINE_LOCATION") || has("android.permission.ACCESS_COARSE_LOCATION");
        if (!loc) return false;
        for (String p : neededPerms()) {
            if (p.endsWith("_LOCATION")) continue;
            if (!has(p)) return false;
        }
        return true;
    }

    private void withPerms(final Runnable action) {
        runOnUiThread(() -> {
            if (permsOk()) { action.run(); return; }
            pendingAfterPerms = action;
            requestPermissions(neededPerms(), PERM_REQ);
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERM_REQ) return;
        Runnable r = pendingAfterPerms;
        pendingAfterPerms = null;
        if (permsOk()) { if (r != null) r.run(); }
        else js("onNearbyDenied", "");
    }

    /** "" — усе гаразд; "noplay" — немає сервісів Google; "bt" — вимкнено Bluetooth; "loc" — вимкнено геолокацію */
    private String nearbyCheck() {
        try {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) != ConnectionResult.SUCCESS)
                return "noplay";
        } catch (Exception e) { return "noplay"; }
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt != null && !bt.isEnabled()) return "bt";
        } catch (Exception ignored) { }
        if (Build.VERSION.SDK_INT < 31) {
            try {
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    boolean on = Build.VERSION.SDK_INT >= 28 ? lm.isLocationEnabled()
                        : (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
                    if (!on) return "loc";
                }
            } catch (Exception ignored) { }
        }
        return "";
    }

    // ---------- Міст для JavaScript ----------
    public class Bridge {
        @JavascriptInterface
        public void setTransport(String t, String name) {
            transport = "nearby".equals(t) ? "nearby" : "wifi";
            myName = (name == null || name.length() == 0) ? "Гравець" : name;
        }

        @JavascriptInterface
        public String nearbyCheck() { return MainActivity.this.nearbyCheck(); }

        @JavascriptInterface
        public void openSettings(final String kind) {
            runOnUiThread(() -> {
                try {
                    Intent i;
                    if ("bt".equals(kind)) i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                    else if ("loc".equals(kind)) i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    else {
                        i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.setData(Uri.parse("package:" + getPackageName()));
                    }
                    startActivity(i);
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void host(String name) {
            stopAll();
            if ("nearby".equals(transport)) {
                if (name != null && name.length() > 0) myName = name;
                withPerms(() -> { nearbyHosting = true; startAdvertisingInternal(); });
                return;
            }
            hostName = name == null ? "" : name;
            final int gen = ++hostGen;
            new Thread(new Runnable() { @Override public void run() { acceptLoop(gen); } }).start();
            new Thread(new Runnable() { @Override public void run() { broadcastLoop(gen); } }).start();
        }

        @JavascriptInterface
        public void startScan() {
            if ("nearby".equals(transport)) {
                withPerms(() -> startDiscoveryInternal());
                return;
            }
            stopScanInternal();
            final int gen = ++scanGen;
            new Thread(new Runnable() { @Override public void run() { scanLoop(gen); } }).start();
        }

        @JavascriptInterface
        public void stopScan() {
            if ("nearby".equals(transport)) { runOnUiThread(() -> { if (nearby != null) nearby.stopDiscovery(); }); return; }
            stopScanInternal();
        }

        @JavascriptInterface
        public void connect(final String target) {
            if ("nearby".equals(transport)) {
                runOnUiThread(() -> {
                    nc().stopDiscovery();
                    nc().requestConnection(myName, target, lifecycleCb)
                        .addOnFailureListener(e -> js("onConnectFailed", target));
                });
                return;
            }
            stopScanInternal();
            new Thread(new Runnable() {
                @Override public void run() {
                    Socket s = new Socket();
                    try {
                        s.connect(new InetSocketAddress(target, TCP_PORT), 5000);
                        startConnection(s);
                    } catch (Exception e) {
                        try { s.close(); } catch (IOException ignored) { }
                        js("onConnectFailed", target);
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public boolean send(final String line) {
            if ("nearby".equals(transport)) {
                final String peer = nearbyPeer;
                if (peer == null) return false;
                final byte[] data = (line.replace('\n', ' ') + "\n").getBytes(UTF8);
                runOnUiThread(() -> nc().sendPayload(peer, Payload.fromBytes(data)));
                return true;
            }
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
        public void disconnect() {
            if ("nearby".equals(transport)) {
                runOnUiThread(() -> {
                    String peer = nearbyPeer;
                    nearbyPeer = null;
                    if (peer != null) nc().disconnectFromEndpoint(peer);
                    if (nearbyHosting) startAdvertisingInternal();
                });
                return;
            }
            closeConn();
        }

        @JavascriptInterface
        public void stopHosting() {
            if ("nearby".equals(transport)) {
                runOnUiThread(() -> { nearbyHosting = false; if (nearby != null) nearby.stopAdvertising(); });
                return;
            }
            stopHostingInternal();
        }

        @JavascriptInterface
        public void stopAll() {
            stopHostingInternal();
            stopScanInternal();
            closeConn();
            runOnUiThread(() -> nearbyStopAll());
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
