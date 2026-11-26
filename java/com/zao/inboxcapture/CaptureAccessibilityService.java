package com.zao.inboxcapture;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

/**
 * 监听“复制”动作，弹出收纳窗。
 *
 * Android 10+ 后台不能直接读剪贴板，所以这里只负责发现复制动作；
 * 真正的读取发生在悬浮窗获得焦点之后。
 */
public class CaptureAccessibilityService extends AccessibilityService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastProbe;
    private boolean capturing;
    private ClipboardManager clipboardManager;

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    probeNow();
                }
            };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (clipboardManager != null) {
            clipboardManager.removePrimaryClipChangedListener(clipListener);
        }
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            clipboardManager.addPrimaryClipChangedListener(clipListener);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            handleEvent(event);
        } catch (Exception ignored) {
            // 个别 App 的节点会抛异常，不能让无障碍服务崩掉。
        }
    }

    private void handleEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        if (packageName != null && getPackageName().contentEquals(packageName)) {
            return;
        }
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String toastText = collectText(event).toLowerCase(Locale.ROOT);
            if (toastText.contains("已复制") || toastText.contains("复制成功")
                    || toastText.contains("链接已复制") || toastText.contains("已复制到")
                    || toastText.contains("链接复制成功") || toastText.contains("复制链接成功")
                    || toastText.contains("copied")) {
                scheduleProbe(120);
                scheduleProbe(650);
            }
            return;
        }
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED
                && type != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            return;
        }

        StringBuilder haystack = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence text : event.getText()) {
                haystack.append(' ').append(text);
            }
        }
        if (event.getContentDescription() != null) {
            haystack.append(' ').append(event.getContentDescription());
        }

        AccessibilityNodeInfo node = event.getSource();
        for (int depth = 0; depth < 4 && node != null; depth++) {
            if (node.getText() != null) {
                haystack.append(' ').append(node.getText());
            }
            if (node.getContentDescription() != null) {
                haystack.append(' ').append(node.getContentDescription());
            }
            String viewId = node.getViewIdResourceName();
            if (viewId != null) {
                haystack.append(' ').append(viewId);
            }
            if (depth == 0) {
                try {
                    List<AccessibilityNodeInfo> found =
                            node.findAccessibilityNodeInfosByText("复制");
                    if (found != null && !found.isEmpty()) {
                        haystack.append(" 复制");
                    }
                } catch (Exception ignored) {
                    // 某些节点不支持按文字查找。
                }
            }
            node = node.getParent();
        }

        String lower = haystack.toString().toLowerCase(Locale.ROOT);
        if (lower.contains("复制") || lower.contains("拷贝") || lower.contains("copy")) {
            // 点按“复制”时剪贴板可能还没写入，稍等再读。
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    probeNow();
                }
            }, 320);
        }
    }

    private void scheduleProbe(long delayMs) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                probeNow();
            }
        }, delayMs);
    }

    private String collectText(AccessibilityEvent event) {
        StringBuilder builder = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence text : event.getText()) {
                builder.append(' ').append(text);
            }
        }
        if (event.getContentDescription() != null) {
            builder.append(' ').append(event.getContentDescription());
        }
        AccessibilityNodeInfo source = event.getSource();
        for (int depth = 0; depth < 2 && source != null; depth++) {
            if (source.getText() != null) {
                builder.append(' ').append(source.getText());
            }
            source = source.getParent();
        }
        return builder.toString();
    }

    private void probeNow() {
        long now = System.currentTimeMillis();
        if (now - lastProbe < 700) {
            return;
        }
        lastProbe = now;
        final CaptureAccessibilityService service = this;
        ClipboardProbe.read(this, new ClipboardProbe.Callback() {
            @Override
            public void onText(String text) {
                if (text == null || text.trim().isEmpty()) {
                    return;
                }
                if (NoteWriter.findSupportedUrl(text) == null) {
                    return; // 普通文字不打扰
                }
                if (!PopupController.shouldShow(text)) {
                    return;
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    PopupController.show(service, text, null, false);
                    return;
                }
                if (capturing) {
                    return;
                }
                capturing = true;
                ScreenshotCapture.capture(service, service, new ScreenshotCapture.Callback() {
                    @Override
                    public void onCaptured(CapturedShot shot) {
                        capturing = false;
                        if (!PopupController.shouldShow(text)) {
                            ShotStore.delete(service, shot);
                            return;
                        }
                        try {
                            PopupController.show(service, text, shot, false);
                        } catch (Exception ignored) {
                            // 悬浮窗被系统限制时不崩溃。
                        }
                    }
                });
            }
        });
    }

    @Override
    public void onInterrupt() {
        // 不需要处理。
    }

    @Override
    public void onDestroy() {
        capturing = false;
        if (clipboardManager != null) {
            clipboardManager.removePrimaryClipChangedListener(clipListener);
        }
        PopupController.dismissCurrent();
        super.onDestroy();
    }
}
