package com.zao.inboxcapture;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Base64;

import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Owns the app-private Syncthing process. Only the service starts and stops it. */
final class SyncEngine {
    private static Process child;
    private static SyncWifi wifi;
    // Empty until an authenticated LAN handshake or previously accepted share proves the peer.
    private static String remotePeer = "";
    private static volatile String apiKey = "";
    private static volatile String failure = "";

    private SyncEngine() {}

    static String failure() { return failure; }
    static void setStartDenied() { failure = "系统暂未允许启动同步，请重新打开连接电脑"; }

    private static File home(Context context) {
        return new File(context.getNoBackupFilesDir(), "sync");
    }

    private static File socket(Context context) {
        return new File(home(context), "api.sock");
    }

    private static SharedPreferences pairing(Context context) {
        return context.getSharedPreferences("computer_pairing", Context.MODE_PRIVATE);
    }

    static boolean wasConfirmed(Context context, String peer) {
        return !peer.isEmpty() && peer.equals(pairing(context).getString("confirmedPeer", ""));
    }

    private static String confirmedPeer(Context context, String peer) {
        if (peer.isEmpty()) return "";
        return pairing(context).getBoolean("seen", false) || wasConfirmed(context, peer) ? peer : "";
    }

    private static boolean connected(Context context, String peer) {
        try {
            JSONObject all = new JSONObject(request(context, "GET", "/rest/system/connections", null));
            JSONObject device = all.getJSONObject("connections").optJSONObject(peer);
            return device != null && device.optBoolean("connected");
        } catch (Exception unavailable) { return false; }
    }

    // Used by pairing callbacks and by the foreground service's periodic supervision.
    static synchronized void pairingChanged(Context context) {
        if (pairing(context).getString("peer", "").isEmpty())
            pairing(context).edit().remove("confirmedPeer").apply();
        if (child != null && child.isAlive()) ensureStarted(context);
    }

    static synchronized void routeChanged(Context context) {
        if (child == null || !child.isAlive()) return;
        String peer = pairing(context).getString("peer", "");
        if (remotePeer.isEmpty() && confirmedPeer(context, peer).isEmpty()) return;
        // An old TCP/relay stream may still look connected long after Android moved the
        // default route. Recreate both peer and discovery sockets on the new network.
        stop(context);
        ensureStarted(context);
    }

    // Called on the service's single worker. generate never listens on the network.
    static synchronized void ensureStarted(Context context) {
        String peer = pairing(context).getString("peer", "");
        if (child != null && child.isAlive()) {
            if (remotePeer.isEmpty() && !peer.isEmpty() && connected(context, peer)) {
                if (!wasConfirmed(context, peer))
                    pairing(context).edit().putString("confirmedPeer", peer).apply();
                // Preserve the first LAN session while the user accepts its folder offer.
                // As soon as that Wi-Fi connection drops, restart without the Wi-Fi proxy.
                return;
            }
            if (remotePeer.equals(confirmedPeer(context, peer))) return;
            stop(context);
        }
        child = null;
        closeWifi();
        try {
            File dir = home(context);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建同步配置目录");
            File executable = new File(context.getApplicationInfo().nativeLibraryDir, "libsyncthing.so");
            if (!executable.canExecute()) throw new IOException("此设备无法运行安装包中的同步引擎");
            File config = new File(dir, "config.xml");
            if (!config.isFile()) {
                byte[] secret = new byte[32];
                new SecureRandom().nextBytes(secret);
                String password = Base64.encodeToString(secret, Base64.NO_WRAP | Base64.URL_SAFE);
                Process generate = command(context, executable, dir, "generate", "--gui-user=lingan",
                        "--gui-password=-", "--no-port-probing").start();
                try {
                    try (OutputStream input = generate.getOutputStream()) {
                        input.write((password + "\n").getBytes(StandardCharsets.US_ASCII));
                    }
                    if (!generate.waitFor(25, TimeUnit.SECONDS) || generate.exitValue() != 0)
                        throw new IOException("同步引擎初始化失败");
                } finally {
                    if (generate.isAlive()) generate.destroyForcibly();
                }
            }
            Document doc = readConfig(config);
            Element gui = section(doc.getDocumentElement(), "gui");
            apiKey = value(gui, "apikey");
            if (apiKey.isEmpty() || value(gui, "password").isEmpty() || value(gui, "user").isEmpty())
                throw new IOException("同步引擎的本机身份配置不完整");

            // A child surviving Java process recreation has lost its Wi-Fi socket owner.
            // Restart it with this service's new endpoint, including after an app upgrade.
            if (socket(context).exists()) {
                boolean running = false;
                try {
                    request(context, "GET", "/rest/system/ping", null);
                    running = true;
                } catch (IOException unavailable) {
                    // Go replaces stale Unix socket files when the listener starts.
                }
                if (running) {
                    request(context, "POST", "/rest/system/shutdown", null);
                    for (int i = 0; i < 50; i++) {
                        Thread.sleep(100);
                        try { request(context, "GET", "/rest/system/ping", null); }
                        catch (IOException stopped) { running = false; break; }
                    }
                    if (running) throw new IOException("正在关闭上次的同步连接");
                    doc = readConfig(config);
                }
            }
            peer = pairing(context).getString("peer", "");
            String verified = confirmedPeer(context, peer);
            if (!peer.isEmpty() && sharesFolder(doc.getDocumentElement(), peer)) verified = peer;
            String nextPeer = configure(doc, config, socket(context).getAbsolutePath(), verified);
            if (!nextPeer.isEmpty() && !wasConfirmed(context, nextPeer))
                pairing(context).edit().putString("confirmedPeer", nextPeer).apply();
            ProcessBuilder serve = command(context, executable, dir, "serve", "--no-browser",
                    "--no-upgrade", "--no-restart");
            // Run the server itself, so Service.onDestroy can stop the actual child.
            serve.environment().put("STMONITORED", "yes");
            if (nextPeer.isEmpty()) {
                wifi = new SyncWifi(context);
                wifi.configure(serve);
            } else {
                for (String key : new String[]{"ALL_PROXY", "all_proxy", "ALL_PROXY_NO_FALLBACK"})
                    serve.environment().remove(key);
            }
            child = serve.start();
            remotePeer = nextPeer;
            child.getOutputStream().close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                if (!child.isAlive()) throw new IOException("同步引擎启动失败（" + child.exitValue() + "）");
                try {
                    request(context, "GET", "/rest/system/ping", null);
                    failure = "";
                    return;
                } catch (IOException unavailable) {
                    Thread.sleep(100);
                }
            }
            throw new IOException("同步引擎启动超时");
        } catch (Exception error) {
            failure = error instanceof IOException && error.getMessage() != null
                    ? error.getMessage() : "同步引擎初始化失败";
            if (child != null && child.isAlive()) child.destroy();
            closeWifi();
        }
    }

    private static ProcessBuilder command(Context context, File executable, File dir,
                                          String action, String... arguments) {
        List<String> command = new ArrayList<>(Arrays.asList(executable.getPath(), action, "--home=" + dir));
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder process = new ProcessBuilder(command).directory(dir).redirectErrorStream(true);
        process.redirectOutput(new File("/dev/null"));
        process.environment().put("HOME", dir.getPath());
        process.environment().put("TMPDIR", context.getCacheDir().getPath());
        process.environment().put("STNOUPGRADE", "1");
        return process;
    }

    private static Document readConfig(File file) throws Exception {
        byte[] xml = Files.readAllBytes(file.toPath());
        if (new String(xml, StandardCharsets.UTF_8).contains("<!DOCTYPE"))
            throw new IOException("同步配置格式无效");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static Element section(Element parent, String tag) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && tag.equals(node.getNodeName())) return (Element) node;
        }
        Element result = parent.getOwnerDocument().createElement(tag);
        parent.appendChild(result);
        return result;
    }

    private static String value(Element parent, String tag) { return section(parent, tag).getTextContent().trim(); }

    private static void removeChildren(Element parent, String tag) {
        for (Node node = parent.getLastChild(); node != null;) {
            Node previous = node.getPreviousSibling();
            if (tag.equals(node.getNodeName())) parent.removeChild(node);
            node = previous;
        }
    }

    private static boolean sharesFolder(Element root, String peer) {
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element) || !"folder".equals(n.getNodeName())) continue;
            for (Node d = n.getFirstChild(); d != null; d = d.getNextSibling())
                if (d instanceof Element && "device".equals(d.getNodeName()) &&
                        peer.equals(((Element) d).getAttribute("id"))) return true;
        }
        return false;
    }

    // Runs before the first serve. Never share Syncthing's generated example folder.
    static String configure(Document doc, File file, String socketPath, String verified) throws Exception {
        Element root = doc.getDocumentElement();
        Element gui = section(root, "gui");
        if (!value(gui, "address").startsWith("/")) {
            // A newly generated profile may contain Syncthing's stock "default" folder.
            for (Node node = root.getLastChild(); node != null;) {
                Node previous = node.getPreviousSibling();
                if (node instanceof Element && "folder".equals(node.getNodeName()) &&
                        "default".equals(((Element) node).getAttribute("id"))) root.removeChild(node);
                node = previous;
            }
        }
        gui.setAttribute("enabled", "true");
        gui.setAttribute("tls", "false");
        section(gui, "address").setTextContent(socketPath);
        section(gui, "unixSocketPermissions").setTextContent("0600");
        Element paired = null;
        int deviceCount = 0;
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element) || !"device".equals(n.getNodeName())) continue;
            deviceCount++;
            if (!verified.isEmpty() && verified.equals(((Element) n).getAttribute("id"))) paired = (Element) n;
        }
        boolean remote = paired != null && deviceCount == 2; // the phone and the confirmed computer
        if (remote) {
            boolean dynamic = false;
            for (Node n = paired.getFirstChild(); n != null; n = n.getNextSibling())
                if ("address".equals(n.getNodeName()) && "dynamic".equals(n.getTextContent().trim())) dynamic = true;
            if (!dynamic) paired.appendChild(doc.createElement("address")).setTextContent("dynamic");
        }
        Element options = section(root, "options");
        for (String key : new String[]{"localAnnounceEnabled",
                "natEnabled", "startBrowser", "announceLANAddresses", "crashReportingEnabled"}) {
            section(options, key).setTextContent("false");
        }
        section(options, "globalAnnounceEnabled").setTextContent(Boolean.toString(remote));
        section(options, "relaysEnabled").setTextContent(Boolean.toString(remote));
        removeChildren(options, "globalAnnounceServer");
        section(options, "globalAnnounceServer").setTextContent("default");
        section(options, "urAccepted").setTextContent("-1");
        section(options, "autoUpgradeIntervalH").setTextContent("0");
        section(options, "reconnectionIntervalS").setTextContent("15");
        removeChildren(options, "listenAddress");
        // A relay-pool listener is an OUTBOUND registration; never create public TCP/QUIC listeners.
        section(options, "listenAddress").setTextContent(remote
                ? "dynamic+https://relays.syncthing.net/endpoint" : "tcp://127.0.0.1:0");

        File temporary = File.createTempFile("sync-config-", ".xml", file.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(output));
                output.getFD().sync();
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            temporary.delete();
        }
        return remote ? verified : "";
    }

    static synchronized void stop(Context context) {
        closeWifi();
        try {
            if (!apiKey.isEmpty()) request(context, "POST", "/rest/system/shutdown", null);
        } catch (IOException ignored) {}
        if (child != null) {
            try {
                if (!child.waitFor(3, TimeUnit.SECONDS)) {
                    child.destroy();
                    if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                child.destroy();
                Thread.currentThread().interrupt();
            }
            child = null;
        }
        apiKey = "";
        remotePeer = "";
        failure = "";
    }

    private static void closeWifi() {
        if (wifi != null) {
            wifi.close();
            wifi = null;
        }
    }

    // No TCP GUI: the filesystem socket and its containing directory are app-private.
    static String request(Context context, String method, String path, JSONObject json) throws IOException {
        try {
            return exchange(context, method, path, json);
        } catch (IOException first) {
            // A disconnected/restarting local engine can close the socket between connect and read.
            // Only replay idempotent reads, and never retry a response that carried an HTTP error.
            if (!"GET".equals(method) || first instanceof RestFailure || apiKey.isEmpty()) throw first;
            try { Thread.sleep(120); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw first;
            }
            return exchange(context, method, path, null);
        }
    }

    private static String exchange(Context context, String method, String path, JSONObject json) throws IOException {
        String token = apiKey;
        if (token.isEmpty()) throw new IOException("同步引擎正在启动");
        if (!method.matches("GET|POST|PATCH|PUT|DELETE") || !path.startsWith("/rest/") ||
                !path.matches("[!-~]+")) throw new IOException("无效的同步请求");
        byte[] body = json == null ? new byte[0] : json.toString().getBytes(StandardCharsets.UTF_8);
        String headers = method + " " + path + " HTTP/1.0\r\nHost: localhost\r\nX-API-Key: " + token +
                "\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n";
        try (LocalSocket local = new LocalSocket()) {
            local.connect(new LocalSocketAddress(socket(context).getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
            local.setSoTimeout(5000);
            OutputStream out = local.getOutputStream();
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            return response(new BufferedInputStream(local.getInputStream()));
        }
    }

    private static final class RestFailure extends IOException {
        RestFailure(String status) { super("同步引擎请求失败（" + status + "）"); }
    }

    static String response(InputStream in) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream(256);
        int end = 0;
        while (end != 0x0D0A0D0A) {
            int next = in.read();
            if (next < 0 || header.size() > 32768) throw new IOException("同步引擎响应不完整");
            end = (end << 8) | next;
            header.write(next);
        }
        String[] status = header.toString("US-ASCII").split("\r\n", 2)[0].split(" ", 3);
        if (status.length < 2 || !status[1].matches("2[0-9][0-9]"))
            throw new RestFailure(status.length < 2 ? "?" : status[1]);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (bytes.size() + count > 4 * 1024 * 1024) throw new IOException("同步引擎响应过大");
            bytes.write(buffer, 0, count);
        }
        return bytes.toString("UTF-8");
    }
}
