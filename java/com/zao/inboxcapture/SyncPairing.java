package com.zao.inboxcapture;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** The embedded instance shares one explicitly accepted folder with one computer. */
final class SyncPairing {
    static final String BOTH = "sendreceive";
    static final String READ = "receiveonly";
    static final String WRITE = "sendonly";

    static final class Offer {
        final String id, label;
        Offer(String id, String label) { this.id = id; this.label = label; }
    }

    static final class State {
        String local = "", peer = "", address = "", folder = "", mode = BOTH;
        String label = "我的电脑", problem = "";
        boolean connected, previouslyConnected;
        final List<Offer> offers = new ArrayList<>();

        String status() {
            if (peer.isEmpty()) return "未配对";
            if (connected) return "已连接";
            return previouslyConnected ? "等待重连" : "等待电脑确认";
        }

        String displayKey() {
            StringBuilder key = new StringBuilder(local).append('|').append(peer).append('|').append(address)
                    .append('|').append(label).append('|').append(folder).append('|').append(mode)
                    .append('|').append(status()).append('|').append(problem);
            for (Offer offer : offers) key.append('|').append(offer.id).append(':').append(offer.label);
            return key.toString();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("computer_pairing", Context.MODE_PRIVATE);
    }

    static String summary(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getString("peer", "").isEmpty()) return "未配对";
        return p.getBoolean("seen", false) ? "已配对" : "待确认";
    }

    private static JSONObject get(Context c, String route) throws Exception {
        return new JSONObject(SyncEngine.request(c, "GET", "/rest/" + route, null));
    }

    private static JSONArray list(Context c, String route) throws Exception {
        return new JSONArray(SyncEngine.request(c, "GET", "/rest/" + route, null));
    }

    private static void post(Context c, String route, JSONObject payload) throws Exception {
        SyncEngine.request(c, "POST", "/rest/" + route, payload);
    }

    static State read(Context c) throws Exception {
        State s = new State();
        s.local = get(c, "system/status").getString("myID");
        SharedPreferences p = prefs(c);
        s.mode = p.getString("mode", BOTH);
        JSONArray devices = list(c, "config/devices");
        for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.getJSONObject(i);
            String id = device.getString("deviceID");
            if (id.equals(s.local)) continue;
            if (!s.peer.isEmpty()) throw new IOException("配置中存在多台电脑，无法确定收纳目录的对端");
            s.peer = id;
            s.label = device.optString("name", "我的电脑");
            if (s.label.trim().isEmpty()) s.label = "我的电脑";
            JSONArray addresses = device.optJSONArray("addresses");
            if (addresses != null && addresses.length() > 0) {
                s.address = addresses.optString(0).replaceFirst("^tcp://", "");
            }
        }
        if (!s.peer.isEmpty()) {
            JSONObject connections = get(c, "system/connections").getJSONObject("connections");
            JSONObject remote = connections.optJSONObject(s.peer);
            s.connected = remote != null && remote.optBoolean("connected");
            s.previouslyConnected = s.connected || SyncEngine.wasConfirmed(c, s.peer)
                    || (s.peer.equals(p.getString("peer", "")) && p.getBoolean("seen", false));
            JSONArray folders = list(c, "config/folders");
            for (int i = 0; i < folders.length(); i++) {
                JSONObject folder = folders.getJSONObject(i);
                JSONArray shared = folder.optJSONArray("devices");
                if (shared == null) continue;
                for (int j = 0; j < shared.length(); j++) {
                    if (s.peer.equals(shared.getJSONObject(j).optString("deviceID"))) {
                        s.folder = folder.getString("id");
                        s.mode = folder.getString("type");
                        s.previouslyConnected = true;
                        if (folder.optBoolean("paused")) s.problem = "此文件夹已暂停";
                        break;
                    }
                }
            }
            if (s.folder.isEmpty() && s.connected) {
                JSONObject pending = get(c, "cluster/pending/folders?device=" + Uri.encode(s.peer));
                Iterator<String> ids = pending.keys();
                while (ids.hasNext()) {
                    String id = ids.next();
                    JSONObject byPeer = pending.getJSONObject(id).getJSONObject("offeredBy").optJSONObject(s.peer);
                    if (byPeer == null) continue;
                    if (byPeer.optBoolean("receiveEncrypted") || byPeer.optBoolean("remoteEncrypted")) {
                        s.problem = "请从电脑共享普通收纳文件夹";
                        continue;
                    }
                    s.offers.add(new Offer(id, byPeer.optString("label", "")));
                }
                s.offers.sort(Comparator.comparing(offer -> offer.id));
            } else if (!s.folder.isEmpty() && s.problem.isEmpty()) {
                JSONObject db = get(c, "db/status?folder=" + Uri.encode(s.folder));
                if ("error".equals(db.optString("state"))) s.problem = "文件夹同步异常，请检查目录和存储权限";
                else if (db.optInt("pullErrors", 0) > 0) s.problem = "部分文件尚未同步，请检查目录和剩余空间";
            }
        }
        if (!s.peer.equals(p.getString("peer", "")) || s.previouslyConnected != p.getBoolean("seen", false)) {
            p.edit().putString("peer", s.peer).putBoolean("seen", s.previouslyConnected).apply();
            if (s.previouslyConnected) SyncService.refreshTransport(c);
        }
        if (!s.peer.isEmpty() && !SyncService.isConfigured(c)) SyncService.markPaired(c);
        if (s.peer.isEmpty() && SyncService.isConfigured(c)) SyncService.clearPaired(c);
        return s;
    }

    /** Literal private LAN IPs only. Never resolve names or dial a default/public service. */
    static String tcpAddress(String input) throws IOException {
        String raw = input.trim();
        String host = raw, port = "22000";
        boolean ipv6 = raw.startsWith("[") || raw.indexOf(':') != raw.lastIndexOf(':');
        if (ipv6 && raw.startsWith("[")) {
            int end = raw.indexOf(']');
            if (end <= 1) throw invalidAddress();
            host = raw.substring(1, end);
            if (end + 1 < raw.length()) {
                if (raw.charAt(end + 1) != ':') throw invalidAddress();
                port = raw.substring(end + 2);
            }
        } else if (!ipv6 && raw.contains(":")) {
            int n = raw.indexOf(':');
            host = raw.substring(0, n);
            port = raw.substring(n + 1);
        }
        if (!port.matches("[0-9]{1,5}")) throw invalidAddress();
        int number = Integer.parseInt(port);
        if (number < 1 || number > 65535) throw invalidAddress();
        if (ipv6) {
            if (!host.matches("[0-9a-fA-F:]+") || !host.contains(":")) throw invalidAddress();
            InetAddress parsed;
            try { parsed = InetAddress.getByName(host); } catch (Exception invalid) { throw invalidAddress(); }
            if (!(parsed instanceof Inet6Address) || (parsed.getAddress()[0] & 0xfe) != 0xfc) throw invalidAddress();
            return "tcp://[" + host.toLowerCase(Locale.ROOT) + "]:" + number;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) throw invalidAddress();
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw invalidAddress();
            n[i] = Integer.parseInt(parts[i]);
            if (n[i] > 255) throw invalidAddress();
        }
        boolean privateIp = n[0] == 10 || (n[0] == 192 && n[1] == 168)
                || (n[0] == 172 && n[1] >= 16 && n[1] <= 31) || (n[0] == 169 && n[1] == 254);
        if (!privateIp) throw invalidAddress();
        return "tcp://" + host + ":" + number;
    }

    private static IOException invalidAddress() {
        return new IOException("请输入电脑在此 WiFi 下的 IP 地址，例如 192.168.1.10");
    }

    static void configureDevice(Context c, String enteredId) throws Exception {
        String raw = enteredId.trim().replaceAll("\\s+", "");
        if (raw.length() > 100 || !raw.matches("[-A-Za-z0-9]+")) throw new IOException("请粘贴完整的电脑设备 ID");
        JSONObject verified = get(c, "svc/deviceid?id=" + Uri.encode(raw));
        String peer = verified.optString("id");
        if (peer.isEmpty()) throw new IOException("电脑设备 ID 校验失败，请重新复制");
        State current = read(c);
        if (peer.equals(current.local)) throw new IOException("请填写电脑 ID，此处填入了本设备 ID");
        if (!current.peer.isEmpty() && !current.peer.equals(peer)) throw new IOException("已有电脑待配对，请先取消之前的配对");
        String address = SyncPeerHint.resolve(c, peer);
        JSONObject device = peer.equals(current.peer)
                ? get(c, "config/devices/" + Uri.encode(peer)) : get(c, "config/defaults/device");
        device.put("deviceID", peer).put("name", current.label).put("addresses", new JSONArray().put(address))
                .put("autoAcceptFolders", false).put("introducer", false).put("paused", false);
        post(c, "config/devices", device);
        SyncService.markPaired(c);
        prefs(c).edit().putString("peer", peer).apply();
    }

    static void cancelPending(Context c, String peer) throws Exception {
        disconnect(c, peer, "");
    }

    static void disconnect(Context c, String peer, String folderId) throws Exception {
        State current = read(c);
        if (peer.isEmpty() || !peer.equals(current.peer) || !folderId.equals(current.folder)) {
            throw new IOException("连接状态已变化，请重试");
        }
        if (!folderId.isEmpty()) {
            SyncEngine.request(c, "DELETE", "/rest/config/folders/" + Uri.encode(folderId), null);
        }
        // Removing a folder configuration stops sharing. The existing files remain in test.
        SyncEngine.request(c, "DELETE", "/rest/config/devices/" + Uri.encode(peer), null);
        prefs(c).edit().remove("peer").remove("seen").remove("mode").apply();
        SyncService.clearPaired(c);
        SyncEngine.pairingChanged(c);
    }

    static void accept(Context c, String peer, String folderId) throws Exception {
        State current = read(c);
        if (!current.peer.equals(peer) || !current.folder.isEmpty()) throw new IOException("共享状态已变化，请刷新");
        Offer offered = null;
        for (Offer offer : current.offers) if (offer.id.equals(folderId)) offered = offer;
        if (offered == null) throw new IOException("电脑已撤回共享邀请，请重新共享");
        File directory = SyncStorage.authorizedPath(c);
        JSONArray existing = list(c, "config/folders");
        for (int i = 0; i < existing.length(); i++) {
            JSONObject folder = existing.getJSONObject(i);
            if (folderId.equals(folder.getString("id")) ||
                    directory.getCanonicalFile().equals(new File(folder.getString("path")).getCanonicalFile())) {
                throw new IOException("收纳目录已绑定，请刷新状态");
            }
        }
        JSONObject folder = get(c, "config/defaults/folder");
        folder.put("id", folderId).put("label", offered.label.isEmpty() ? "test" : offered.label)
                .put("path", directory.getCanonicalPath()).put("type", current.mode)
                .put("paused", false).put("ignorePerms", true).put("fsWatcherEnabled", true)
                .put("devices", new JSONArray().put(new JSONObject().put("deviceID", current.local))
                        .put(new JSONObject().put("deviceID", current.peer)));
        post(c, "config/folders", folder);
    }

    static void setMode(Context c, String mode) throws Exception {
        if (!READ.equals(mode) && !WRITE.equals(mode) && !BOTH.equals(mode)) throw new IOException("同步方式无效");
        State current = read(c);
        if (!current.folder.isEmpty()) {
            JSONObject folder = get(c, "config/folders/" + Uri.encode(current.folder));
            folder.put("type", mode);
            post(c, "config/folders", folder);
        }
        prefs(c).edit().putString("mode", mode).apply();
    }

    private SyncPairing() {}
}
