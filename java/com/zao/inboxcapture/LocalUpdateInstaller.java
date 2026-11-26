package com.zao.inboxcapture;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

final class LocalUpdateInstaller {
    static final String ACTION_INSTALL_RESULT = "com.zao.inspirationbox.LOCAL_UPDATE_RESULT";
    private static final String[] CHILD_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
    };

    private static volatile int activeSessionId = -1;
    // These fields are used only on Android's main thread.
    private static WeakReference<MainActivity> foreground = new WeakReference<>(null);
    private static Intent pendingConfirmation;

    private LocalUpdateInstaller() {}

    static final class UpdateCheck {
        final String message, installedVersion, newVersion;
        final long versionCode;
        final File apk;
        UpdateCheck(String message) { this(message, "", "", 0, null); }
        UpdateCheck(String message, String installedVersion, String newVersion, long versionCode, File apk) {
            this.message = message;
            this.installedVersion = installedVersion;
            this.newVersion = newVersion;
            this.versionCode = versionCode;
            this.apk = apk;
        }
        void discard() { if (apk != null) apk.delete(); }
    }

    static synchronized UpdateCheck check(Context context) throws Exception {
        String savedTreeUri = Prefs.treeUri(context);
        if (savedTreeUri.isEmpty()) {
            return new UpdateCheck("请先选择收纳目录");
        }

        PackageManager packageManager = context.getPackageManager();
        PackageInstaller installer = packageManager.getPackageInstaller();
        int currentSessionId = activeSessionId;
        if (currentSessionId >= 0 && installer.getSessionInfo(currentSessionId) != null) {
            return new UpdateCheck("更新已提交，等待系统处理");
        }

        Uri tree = Uri.parse(savedTreeUri);
        ContentResolver resolver = context.getContentResolver();
        String releaseId = findReleaseDirectory(resolver, tree);
        if (releaseId == null) {
            return new UpdateCheck("收纳目录下没有 release 文件夹");
        }

        String packageName = context.getPackageName();
        PackageInfo installed = packageManager.getPackageInfo(
                packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        long newestVersion = installed.getLongVersionCode();
        Signature[] trustedSigners = installed.signingInfo == null
                ? null : installed.signingInfo.getApkContentsSigners();
        if (trustedSigners == null || trustedSigners.length == 0) {
            return new UpdateCheck("无法读取当前安装签名");
        }

        File newestApk = null;
        String displayVersion = "";
        boolean sawApk = false, sawInstalledOrOlder = false;
        File cache = new File(context.getCacheDir(), "checked-updates");
        if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("无法暂存更新包");
        File[] stale = cache.listFiles();
        if (stale != null) {
            long cutoff = System.currentTimeMillis() - 86400000L;
            for (File file : stale) if (file.lastModified() < cutoff && file.isFile()) file.delete();
        }
        try (Cursor cursor = listChildren(resolver, tree, releaseId)) {
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))
                        || name == null || !name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    continue;
                }
                sawApk = true;
                Uri source = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                File candidate = File.createTempFile("lingan-update-", ".apk", cache);
                boolean keepCandidate = false;
                try {
                    // Validate and install the same private copy even if sync changes the source.
                    try (InputStream in = resolver.openInputStream(source);
                         OutputStream out = new FileOutputStream(candidate)) {
                        if (in == null) continue;
                        copy(in, out);
                    }
                    PackageInfo info = packageManager.getPackageArchiveInfo(
                            candidate.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
                    if (info == null || !packageName.equals(info.packageName)
                            || (info.applicationInfo != null
                                    && info.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
                            || !matchesSigningCertificate(trustedSigners, info)) {
                        continue;
                    }
                    if (info.getLongVersionCode() <= installed.getLongVersionCode()) sawInstalledOrOlder = true;
                    if (info.getLongVersionCode() <= newestVersion) continue;
                    if (newestApk != null) newestApk.delete();
                    newestApk = candidate;
                    newestVersion = info.getLongVersionCode();
                    displayVersion = info.versionName != null ? info.versionName : Long.toString(newestVersion);
                    keepCandidate = true;
                } catch (IOException | RuntimeException ignored) {
                    // Ignore an unreadable or partially synced APK and check other candidates.
                } finally {
                    if (!keepCandidate) candidate.delete();
                }
            }
            if (newestApk == null) {
                return new UpdateCheck(sawInstalledOrOlder || !sawApk
                        ? "已是最新版本" : "没有找到适用的更新包，请检查签名或稍后重试");
            }
            UpdateCheck result = new UpdateCheck("", installed.versionName, displayVersion, newestVersion, newestApk);
            newestApk = null; // The confirmation dialog now owns this exact snapshot.
            return result;
        } finally {
            if (newestApk != null) newestApk.delete();
        }
    }

    static synchronized String installConfirmed(Context context, UpdateCheck update) throws Exception {
        PackageManager pm = context.getPackageManager();
        if (!pm.canRequestPackageInstalls()) return "安装权限未开启";
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), 0);
        if (installed.getLongVersionCode() >= update.versionCode) return "已是最新版本";
        if (!update.apk.isFile()) return "暂存的更新包已清理，请重新检查更新";
        PackageInstaller installer = pm.getPackageInstaller();
        if (activeSessionId >= 0 && installer.getSessionInfo(activeSessionId) != null)
            return "更新已提交，等待系统处理";
        submit(context, installer, context.getPackageName(), update.apk);
        return "已提交新版安装，等待系统处理";
    }

    static String findReleaseDirectory(ContentResolver resolver, Uri tree) throws IOException {
        try (Cursor cursor = listChildren(resolver, tree, DocumentsContract.getTreeDocumentId(tree))) {
            while (cursor.moveToNext()) {
                if ("release".equals(cursor.getString(1))
                        && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    return cursor.getString(0);
                }
            }
        }
        return null;
    }

    static Cursor listChildren(ContentResolver resolver, Uri tree, String parentId)
            throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        Cursor cursor = resolver.query(children, CHILD_COLUMNS, null, null, null);
        if (cursor == null) throw new IOException("无法读取收纳目录");
        return cursor;
    }

    private static boolean matchesSigningCertificate(Signature[] trustedSigners, PackageInfo archive) {
        if (archive.signingInfo == null) return false;
        Signature[] signers = archive.signingInfo.getApkContentsSigners();
        return signers != null && signers.length == trustedSigners.length
                && new HashSet<>(Arrays.asList(signers)).equals(
                        new HashSet<>(Arrays.asList(trustedSigners)));
    }

    private static void submit(Context context, PackageInstaller installer, String packageName, File apk)
            throws IOException {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int sessionId = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                copy(in, out);
                session.fsync(out);
            }
            Intent result = new Intent(context, UpdateInstallReceiver.class)
                    .setAction(ACTION_INSTALL_RESULT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            // The installer fills in the session status. Only the explicit private receiver receives it.
            PendingIntent callback = PendingIntent.getBroadcast(context, sessionId, result, flags);
            activeSessionId = sessionId;
            session.commit(callback.getIntentSender());
            committed = true;
        } finally {
            if (!committed) {
                activeSessionId = -1;
                abandon(installer, sessionId);
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static void abandon(PackageInstaller installer, int sessionId) {
        try {
            installer.abandonSession(sessionId);
        } catch (IllegalStateException | SecurityException ignored) {
            // The system can close the session before cleanup runs.
        }
    }

    static void activityResumed(MainActivity activity) {
        foreground = new WeakReference<>(activity);
        showPendingConfirmation();
    }

    static void activityPaused(MainActivity activity) {
        if (foreground.get() == activity) foreground.clear();
    }

    static void confirmInstall(Intent confirmation) {
        pendingConfirmation = confirmation;
        showPendingConfirmation();
    }

    private static void showPendingConfirmation() {
        MainActivity activity = foreground.get();
        if (activity == null || pendingConfirmation == null) return;
        Intent confirmation = pendingConfirmation;
        pendingConfirmation = null;
        try {
            activity.startActivity(confirmation);
        } catch (RuntimeException exc) {
            int sessionId = activeSessionId;
            activeSessionId = -1;
            if (sessionId >= 0) {
                abandon(activity.getPackageManager().getPackageInstaller(), sessionId);
            }
            Toast.makeText(activity, "无法打开系统安装确认，请重试", Toast.LENGTH_LONG).show();
        }
    }

    static void finishInstall(int sessionId) {
        if (activeSessionId == sessionId || activeSessionId < 0) {
            activeSessionId = -1;
            pendingConfirmation = null;
        }
    }
}
