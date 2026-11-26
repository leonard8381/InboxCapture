package com.zao.inboxcapture;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 用一个 1x1 的透明焦点窗口读取剪贴板。
 *
 * Android 10+ 只有“当前有焦点”的应用能读剪贴板；AccessibilityService 本身
 * 没有焦点，所以复制动作发生后，临时开一个不可见窗口拿到焦点，读完立刻移除。
 * 只有识别到支持的链接时才弹确认窗，避免每次复制都打扰。
 */
public final class ClipboardProbe {
    public interface Callback {
        void onText(String text);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static View probeView;
    private static WindowManager probeWm;

    private ClipboardProbe() {}

    public static void read(final Context context, final Callback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    read(context, callback);
                }
            });
            return;
        }
        final Context app = context.getApplicationContext();
        if (!Settings.canDrawOverlays(app)) {
            callback.onText(null);
            return;
        }
        removeProbe();
        final WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            callback.onText(null);
            return;
        }

        final View probe = new View(app);
        probe.setFocusable(true);
        probe.setFocusableInTouchMode(true);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1,
                1,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        params.setTitle("clipboard-probe");

        try {
            wm.addView(probe, params);
        } catch (Exception exc) {
            callback.onText(null);
            return;
        }
        probeView = probe;
        probeWm = wm;
        probe.requestFocus();

        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                String text = readNow(app);
                if (text == null || text.isEmpty()) {
                    MAIN.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            String retry = readNow(app);
                            removeProbe();
                            callback.onText(retry);
                        }
                    }, 180);
                    return;
                }
                removeProbe();
                callback.onText(text);
            }
        }, 260);
    }

    private static String readNow(Context context) {
        ClipboardManager manager =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null || !manager.hasPrimaryClip()) {
            return null;
        }
        ClipData clip = manager.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        CharSequence value = clip.getItemAt(0).coerceToText(context);
        return value == null ? null : value.toString();
    }

    private static void removeProbe() {
        View view = probeView;
        WindowManager wm = probeWm;
        probeView = null;
        probeWm = null;
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
                // 已经被系统移除。
            }
        }
    }
}
