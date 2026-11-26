package com.zao.inboxcapture;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
import java.io.File;
import java.io.IOException;

/** Maps only the user's persistently selected local test tree to a path the native engine can open. */
final class SyncStorage {
    private SyncStorage() {}

    static boolean needsAllFilesAccess(Context context) {
        if (Build.VERSION.SDK_INT >= 30) return !Environment.isExternalStorageManager();
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
    }

    static void requestAccess(Activity activity) {
        if (Build.VERSION.SDK_INT < 30) {
            activity.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 86);
            return;
        }
        Intent perApp = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", activity.getPackageName(), null));
        try {
            activity.startActivity(perApp);
        } catch (ActivityNotFoundException unavailable) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    static File authorizedPath(Context context) throws IOException {
        String selected = Prefs.treeUri(context);
        if (selected.isEmpty()) throw new IOException("请先在收纳设置里选择 test 文件夹");
        Uri tree = Uri.parse(selected);
        if (!"com.android.externalstorage.documents".equals(tree.getAuthority())
                || !DocumentsContract.isTreeUri(tree)) {
            throw new IOException("请选择手机存储或 SD 卡里的 test 文件夹");
        }
        boolean granted = false;
        for (UriPermission grant : context.getContentResolver().getPersistedUriPermissions()) {
            if (tree.equals(grant.getUri()) && grant.isReadPermission() && grant.isWritePermission()) {
                granted = true;
                break;
            }
        }
        if (!granted) throw new IOException("test 的目录授权已失效，请重新选择");
        if (needsAllFilesAccess(context)) throw new IOException("请允许同步引擎访问本机文件");
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        if (colon < 1 || colon == id.length() - 1) throw new IOException("无效的收纳目录");
        String volume = id.substring(0, colon);
        String relative = id.substring(colon + 1);
        File root = volumeRoot(context, volume);
        if (root == null) throw new IOException("存储卷未挂载");
        return checkFolder(root, relative);
    }

    static File checkFolder(File root, String relative) throws IOException {
        File mounted = root.getCanonicalFile();
        String[] parts = relative.split("/", -1);
        for (String part : parts) {
            if (!NoteWriter.simpleName(part)) throw new IOException("无效的收纳目录");
        }
        File selected = new File(mounted, relative);
        if (!NoteWriter.FOLDER_NAME.equals(selected.getName())
                || !selected.equals(selected.getCanonicalFile())
                || !selected.isDirectory() || !selected.canRead() || !selected.canWrite()) {
            throw new IOException("无法读写所选的 test 目录，请检查授权或存储卷");
        }
        return selected;
    }

    private static File volumeRoot(Context context, String volume) {
        if ("primary".equalsIgnoreCase(volume)) return Environment.getExternalStorageDirectory();
        StorageManager manager = context.getSystemService(StorageManager.class);
        if (Build.VERSION.SDK_INT >= 30) {
            for (StorageVolume device : manager.getStorageVolumes()) {
                if (volume.equalsIgnoreCase(device.getUuid())) return device.getDirectory();
            }
        } else {
            for (File appDir : context.getExternalFilesDirs(null)) {
                if (appDir == null) continue;
                StorageVolume device = manager.getStorageVolume(appDir);
                if (device == null || !volume.equalsIgnoreCase(device.getUuid())) continue;
                // Android 10 returns <volume>/Android/data/<package>/files here.
                File marker = appDir;
                for (String segment : new String[]{"files", context.getPackageName(), "data", "Android"}) {
                    if (marker == null || !segment.equals(marker.getName())) return null;
                    marker = marker.getParentFile();
                }
                return marker;
            }
        }
        return null;
    }
}
