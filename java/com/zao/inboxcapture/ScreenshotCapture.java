package com.zao.inboxcapture;

import android.accessibilityservice.AccessibilityService;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Display;

import java.io.OutputStream;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 用无障碍服务的 takeScreenshot（Android 11+）截取当前屏幕，
 * 存进用户选定的同一个 00-Inbox 文件夹。
 */
public final class ScreenshotCapture {
    public interface Callback {
        void onCaptured(CapturedShot shot);
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private ScreenshotCapture() {}

    public static void capture(final Context context, AccessibilityService service,
                               final Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback.onCaptured(null);
            return;
        }
        if (Prefs.treeUri(context).isEmpty()) {
            callback.onCaptured(null);
            return;
        }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            Bitmap bitmap = null;
                            HardwareBuffer buffer = result.getHardwareBuffer();
                            try {
                                bitmap = Bitmap.wrapHardwareBuffer(
                                        buffer, result.getColorSpace());
                                if (bitmap != null) {
                                    bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                                }
                            } catch (Exception ignored) {
                                bitmap = null;
                            } finally {
                                if (buffer != null) {
                                    buffer.close();
                                }
                            }
                            if (bitmap == null) {
                                callback.onCaptured(null);
                                return;
                            }
                            CapturedShot shot = save(context, bitmap);
                            bitmap.recycle();
                            callback.onCaptured(shot);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            callback.onCaptured(null);
                        }
                    });
        } catch (Exception exc) {
            callback.onCaptured(null);
        }
    }

    private static CapturedShot save(Context context, Bitmap bitmap) {
        ContentResolver resolver = context.getContentResolver();
        Uri tree;
        String treeId;
        try {
            tree = Uri.parse(Prefs.treeUri(context));
            treeId = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception exc) {
            return null;
        }
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId);
        String id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + "-" + String.format(Locale.US, "%04x", RANDOM.nextInt(0x10000));
        String name = id + ".jpg";

        Uri created = null;
        try {
            created = DocumentsContract.createDocument(resolver, parent, "image/jpeg", name);
        } catch (Exception ignored) {
            created = null;
        }
        if (created == null) {
            try {
                created = DocumentsContract.createDocument(
                        resolver, parent, "application/octet-stream", name);
            } catch (Exception ignored) {
                created = null;
            }
        }
        if (created == null) {
            return null;
        }

        String actual = queryName(resolver, created);
        if (actual != null && !actual.toLowerCase(Locale.US).endsWith(".jpg")) {
            try {
                DocumentsContract.renameDocument(resolver, created, name);
            } catch (Exception ignored) {
                // 名字不对也能写，只是 Obsidian 可能不预览。
            }
        }

        try (OutputStream out = resolver.openOutputStream(created, "w")) {
            if (out == null) {
                ShotStore.deleteUri(resolver, created);
                return null;
            }
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                ShotStore.deleteUri(resolver, created);
                return null;
            }
            out.flush();
        } catch (Exception exc) {
            ShotStore.deleteUri(resolver, created);
            return null;
        }
        return new CapturedShot(name, created);
    }

    private static String queryName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
            // 名字查询失败不影响保存。
        }
        return null;
    }
}
