package com.zao.inboxcapture;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Restricts the first, unconfirmed handshake to the user's Wi-Fi. */
final class SyncWifi implements Closeable {
    private final ConnectivityManager manager;
    private final Context context;
    private final ServerSocket listener;
    private final String password = UUID.randomUUID().toString();
    private final ArrayList<Network> networks = new ArrayList<>();
    private final Set<Socket> sessions = new HashSet<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 24, 15, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> new Thread(r, "sync-wifi"));

    private final ConnectivityManager.NetworkCallback changes = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { update(network, true); }
        @Override public void onLost(Network network) { update(network, false); }
    };

    SyncWifi(Context context) throws IOException {
        this.context = context.getApplicationContext();
        manager = context.getSystemService(ConnectivityManager.class);
        listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        try {
            NetworkRequest wifi = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
            manager.registerNetworkCallback(wifi, changes);
        } catch (RuntimeException denied) {
            listener.close();
            throw new IOException("无法监听 Wi-Fi 网络状态", denied);
        }
        new Thread(this::accept, "sync-wifi-listener").start();
    }

    static boolean available(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return true;
        }
        return false;
    }

    void configure(ProcessBuilder process) {
        Map<String,String> env = process.environment();
        for (String key : new String[]{"NO_PROXY", "no_proxy", "http_proxy", "HTTP_PROXY", "https_proxy",
                "HTTPS_PROXY", "all_proxy"}) env.remove(key);
        env.put("ALL_PROXY", "socks5://sync:" + password + "@127.0.0.1:" + listener.getLocalPort());
        env.put("ALL_PROXY_NO_FALLBACK", "1");
    }

    private Network network() {
        return networks.isEmpty() ? null : networks.get(networks.size() - 1);
    }

    private synchronized void update(Network network, boolean connected) {
        if (listener.isClosed()) return;
        Network previous = network();
        networks.remove(network);
        if (connected) networks.add(network);
        if (previous != null && !previous.equals(network())) {
            for (Socket socket : sessions) closeQuietly(socket);
            SyncService.refreshTransport(context);
        }
    }

    private synchronized void bind(Socket socket) throws IOException {
        Network wifi = network();
        if (wifi == null) throw new IOException("等待 Wi-Fi");
        wifi.bindSocket(socket);
        sessions.add(socket);
    }

    private boolean pairedAddress(InetAddress address, int port) throws IOException {
        try {
            JSONArray devices = new JSONArray(SyncEngine.request(context, "GET", "/rest/config/devices", null));
            for (int i = 0; i < devices.length(); i++) {
                JSONArray addresses = devices.getJSONObject(i).optJSONArray("addresses");
                if (addresses == null) continue;
                for (int j = 0; j < addresses.length(); j++) {
                    String value = addresses.optString(j);
                    if (!value.startsWith("tcp://")) continue;
                    try {
                        URI uri = URI.create(value);
                        String host = uri.getHost();
                        if (host == null || uri.getPort() != port) continue;
                        if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
                        if (address.equals(InetAddresses.parseNumericAddress(host))) return true;
                    } catch (IllegalArgumentException invalid) { /* not a literal address */ }
                }
            }
        } catch (JSONException invalid) { throw new IOException("无法读取电脑地址", invalid); }
        return false;
    }

    private void accept() {
        while (!listener.isClosed()) {
            try {
                Socket incoming = listener.accept();
                try { workers.execute(() -> serve(incoming)); }
                catch (RejectedExecutionException full) { closeQuietly(incoming); }
            } catch (IOException closed) { return; }
        }
    }

    // RFC 1928 / 1929. Only the authenticated native engine can request a numeric LAN address.
    private void serve(Socket incoming) {
        try (Socket local = incoming; Socket remote = new Socket(Proxy.NO_PROXY)) {
            local.setSoTimeout(3500);
            DataInputStream in = new DataInputStream(local.getInputStream());
            OutputStream out = local.getOutputStream();
            if (in.readUnsignedByte() != 5) return;
            byte[] methods = field(in);
            boolean auth = false;
            for (byte method : methods) if (method == 2) auth = true;
            out.write(new byte[]{5, auth ? (byte) 2 : (byte) 0xff});
            if (!auth || in.readUnsignedByte() != 1) return;
            byte[] user = field(in), pass = field(in);
            boolean ok = MessageDigest.isEqual(user, "sync".getBytes(StandardCharsets.US_ASCII))
                    && MessageDigest.isEqual(pass, password.getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[]{1, ok ? (byte) 0 : (byte) 1});
            if (!ok || in.readUnsignedByte() != 5 || in.readUnsignedByte() != 1 || in.readUnsignedByte() != 0) return;
            int family = in.readUnsignedByte();
            if (family != 1 && family != 4) return;
            byte[] ip = new byte[family == 1 ? 4 : 16];
            in.readFully(ip);
            InetAddress address = InetAddress.getByAddress(ip);
            int port = in.readUnsignedShort();
            String host = address.getHostAddress();
            SyncPairing.tcpAddress((family == 4 ? "[" + host + "]" : host) + ":" + port);
            if (!pairedAddress(address, port)) return;
            try {
                bind(remote);
                remote.connect(new InetSocketAddress(address, port), 5000);
                out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
                local.setSoTimeout(0);
                workers.execute(() -> {
                    try { copy(remote, local); }
                    catch (IOException closed) {}
                    finally { closeQuietly(local); }
                });
                copy(local, remote);
            } finally {
                synchronized (this) { sessions.remove(remote); }
            }
        } catch (IOException | RejectedExecutionException closed) {
            // Dial failure makes Syncthing retry only through this bridge.
        }
    }

    private static byte[] field(DataInputStream in) throws IOException {
        byte[] bytes = new byte[in.readUnsignedByte()];
        in.readFully(bytes);
        return bytes;
    }

    private static void copy(Socket from, Socket to) throws IOException {
        InputStream in = from.getInputStream();
        OutputStream out = to.getOutputStream();
        byte[] buffer = new byte[32768];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    }

    private static void closeQuietly(Closeable socket) {
        try { socket.close(); } catch (IOException ignored) {}
    }

    @Override public synchronized void close() {
        closeQuietly(listener);
        manager.unregisterNetworkCallback(changes);
        networks.clear();
        for (Socket socket : sessions) closeQuietly(socket);
        workers.shutdownNow();
    }
}
