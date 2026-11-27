package com.zao.inboxcapture;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 用无障碍服务的 takeScreenshot（Android 11+）截取当前屏幕，
 * 先存入应用缓存，确认收纳后再随笔记写入日期目录。
 */
public final class ScreenshotCapture {
    public interface Callback {
        void onCaptured(CapturedShot shot);
    }

    private ScreenshotCapture() {}

    public static void capture(final Context context, AccessibilityService service,
                               final Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback.onCaptured(null);
            return;
        }
        if (NoteWriter.captureTree(context) == null) {
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
        File pending = null;
        boolean complete = false;
        try {
            pending = File.createTempFile("capture-", ".jpg", context.getCacheDir());
            try (OutputStream output = new FileOutputStream(pending)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    return null;
                }
            }
            complete = true;
            return new CapturedShot(pending);
        } catch (Exception exc) {
            return null;
        } finally {
            if (!complete && pending != null) {
                pending.delete();
            }
        }
    }
}
