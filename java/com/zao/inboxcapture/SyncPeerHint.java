package com.zao.inboxcapture;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** A private connection hint for one device. Syncthing authenticates the pasted ID over TLS. */
final class SyncPeerHint {
    private static final String NAME = "syncthing-peer.json";

    static String fromRelease(Context context, String peer) {
        String saved = Prefs.treeUri(context);
        if (saved.isEmpty()) return "";
        ContentResolver resolver = context.getContentResolver();
        Uri tree = Uri.parse(saved);
        try {
            String release = LocalUpdateInstaller.findReleaseDirectory(resolver, tree);
            if (release == null) return "";
            try (Cursor files = LocalUpdateInstaller.listChildren(resolver, tree, release)) {
                while (files.moveToNext()) {
                    if (!NAME.equals(files.getString(1))) continue;
                    Uri hint = DocumentsContract.buildDocumentUriUsingTree(tree, files.getString(0));
                    try (InputStream input = resolver.openInputStream(hint)) {
                        return read(input, peer);
                    }
                }
            }
        } catch (IOException | JSONException | RuntimeException unavailable) {
            // The folder can be unavailable or mid-sync. Retain any known working route.
        }
        return "";
    }

    static String resolve(Context context, String peer) throws IOException {
        String address = fromRelease(context, peer);
        if (!address.isEmpty()) return address;
        try (InputStream input = context.getAssets().open(NAME)) {
            address = read(input, peer);
        } catch (IOException | JSONException unavailable) {
            // A generic build has no computer-specific asset.
        }
        if (address.isEmpty()) throw new IOException("未找到这台电脑的配对信息，请检查电脑 ID 和收纳目录");
        return address;
    }

    private static String read(InputStream input, String peer) throws IOException, JSONException {
        if (input == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int n;
        while ((n = input.read(buf)) != -1) {
            if (out.size() + n > 4096) return "";
            out.write(buf, 0, n);
        }
        JSONObject hint = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        if (!peer.equals(hint.optString("id"))) return "";
        return SyncPairing.tcpAddress(hint.getString("address"));
    }

    private SyncPeerHint() {}
}
