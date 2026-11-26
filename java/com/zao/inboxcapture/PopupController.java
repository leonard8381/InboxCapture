package com.zao.inboxcapture;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 悬浮窗：瓷白无内框底部贴片（#FAF9F6），圆角截图 + 平台小圆点 + 珊瑚橙全圆角胶囊按钮。 */
public final class PopupController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO_EXEC = Executors.newSingleThreadExecutor();
    private static View currentView;
    private static WindowManager currentWm;
    private static String currentHash;
    private static String dismissedHash;
    private static long dismissedAt;
    private static CapturedShot currentShot;
    private static Future<?> thumbTask;
    private static final long SUPPRESS_SAME_CLIP_MS = 10_000L;

    private PopupController() {}

    public static void show(final Context context, final String forcedText) {
        show(context, forcedText, null, false);
    }

    public static void show(final Context context, final String forcedText, final boolean force) {
        show(context, forcedText, null, force);
    }

    public static boolean shouldShow(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String hash = clipHash(text);
        if (currentView != null && hash.equals(currentHash)) {
            return false;
        }
        return !(hash.equals(dismissedHash)
                && System.currentTimeMillis() - dismissedAt < SUPPRESS_SAME_CLIP_MS);
    }

    public static void show(final Context context, final String forcedText,
                            final CapturedShot shot, final boolean force) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    show(context, forcedText, shot, force);
                }
            });
            return;
        }
        if (!force && !shouldShow(forcedText)) {
            return;
        }
        final String hash = forcedText == null ? null : clipHash(forcedText);
        final Context app = context.getApplicationContext();
        if (currentView != null && currentShot != null && currentShot != shot) {
            final CapturedShot staleShot = currentShot;
            IO_EXEC.execute(new Runnable() {
                @Override public void run() { ShotStore.delete(app, staleShot); }
            });
        }
        if (!Settings.canDrawOverlays(app)) {
            Toast.makeText(app, "请先在「灵感」里开启悬浮窗权限", Toast.LENGTH_LONG).show();
            return;
        }
        dismissCurrent();
        final WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return;
        }

        final LinearLayout card = new LinearLayout(app);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(app, 22), UiKit.dp(app, 4),
                UiKit.dp(app, 22), UiKit.dp(app, 20));
        card.setBackground(UiKit.roundRect(app, 0xFFFAF9F6, 36));
        UiKit.applySoftShadow(card, 22, 0x45000000);
        card.setFocusableInTouchMode(true);

        // Dedicated swipe-down handle
        FrameLayout handleArea = new FrameLayout(app);
        handleArea.setPadding(0, UiKit.dp(app, 10), 0, UiKit.dp(app, 14));
        View grab = new View(app);
        grab.setBackground(UiKit.roundRect(app, 0xFFDFDCD5, 999));
        handleArea.addView(grab, new FrameLayout.LayoutParams(
                UiKit.dp(app, 36), UiKit.dp(app, 4), Gravity.CENTER));
        card.addView(handleArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Body row: [68x88 screenshot] + [dot + platform tag / title]
        LinearLayout body = new LinearLayout(app);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);

        final ImageView thumb = new ImageView(app);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(UiKit.roundRect(app, UiKit.PAPER_MUTED_BG, 16));
        thumb.setClipToOutline(true);
        thumb.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), UiKit.dpf(app, 16));
            }
        });
        UiKit.applySoftShadow(thumb, 6, 0x2614120E);
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(
                UiKit.dp(app, 66), UiKit.dp(app, 86));
        thumbLp.rightMargin = UiKit.dp(app, 16);
        thumb.setVisibility(shot != null ? View.VISIBLE : View.GONE);
        body.addView(thumb, thumbLp);

        LinearLayout info = new LinearLayout(app);
        info.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tagRow = new LinearLayout(app);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        tagRow.setGravity(Gravity.CENTER_VERTICAL);

        final View platDot = new View(app);
        platDot.setBackground(UiKit.roundRect(app, UiKit.CORAL_END, 999));
        LinearLayout.LayoutParams pdLp = new LinearLayout.LayoutParams(
                UiKit.dp(app, 6), UiKit.dp(app, 6));
        pdLp.rightMargin = UiKit.dp(app, 6);
        tagRow.addView(platDot, pdLp);

        final TextView tagTxt = new TextView(app);
        tagTxt.setText(shot != null ? "正在识别 · 已自动截图" : "正在识别链接");
        tagTxt.setTextSize(12);
        tagTxt.setTypeface(Typeface.DEFAULT_BOLD);
        tagTxt.setTextColor(UiKit.PAPER_SECOND);
        tagTxt.setSingleLine(true);
        tagTxt.setEllipsize(TextUtils.TruncateAt.END);
        tagRow.addView(tagTxt);
        info.addView(tagRow);

        final TextView preview = new TextView(app);
        preview.setTextSize(16);
        preview.setTypeface(Typeface.DEFAULT_BOLD);
        preview.setTextColor(UiKit.PAPER_INK);
        preview.setMaxLines(2);
        preview.setLineSpacing(UiKit.dp(app, 4), 1f);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        preview.setText("正在读取剪贴板…");
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prevLp.topMargin = UiKit.dp(app, 6);
        info.addView(preview, prevLp);

        body.addView(info, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(body);

        // Action Row: [ 忽略 (quiet text) ]   [ 收纳到 Inbox (coral pill) ]
        LinearLayout row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = UiKit.dp(app, 20);

        final TextView ignore = new TextView(app);
        ignore.setText("忽略");
        ignore.setTextSize(15);
        ignore.setTypeface(Typeface.DEFAULT_BOLD);
        ignore.setTextColor(UiKit.PAPER_MUTED);
        ignore.setGravity(Gravity.CENTER);
        ignore.setPadding(UiKit.dp(app, 20), UiKit.dp(app, 14),
                UiKit.dp(app, 20), UiKit.dp(app, 14));
        UiKit.attachSpringPress(ignore);
        LinearLayout.LayoutParams ignoreLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(app, 52));
        ignoreLp.rightMargin = UiKit.dp(app, 12);
        row.addView(ignore, ignoreLp);

        final TextView save = UiKit.makePrimaryPill(app, "收纳到 Inbox");
        save.setMinHeight(UiKit.dp(app, 52));
        save.setTextSize(15);
        save.setAlpha(0.55f);
        save.setEnabled(false);
        row.addView(save, new LinearLayout.LayoutParams(0, UiKit.dp(app, 52), 1f));
        card.addView(row, rowLp);

        final TextView retry = new TextView(app);
        retry.setText("重试读取剪贴板");
        retry.setTextSize(13);
        retry.setTypeface(Typeface.DEFAULT_BOLD);
        retry.setTextColor(UiKit.CORAL_ACCENT);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(0, UiKit.dp(app, 10), 0, UiKit.dp(app, 2));
        retry.setVisibility(View.GONE);
        card.addView(retry);

        final FrameLayout wrapper = new FrameLayout(app);
        wrapper.setPadding(UiKit.dp(app, 12), 0, UiKit.dp(app, 12), UiKit.dp(app, 14));
        wrapper.setFocusableInTouchMode(true);
        wrapper.addView(card, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = UiKit.dp(app, 14);
        params.setTitle("收纳灵感");

        try {
            wm.addView(wrapper, params);
        } catch (Exception exc) {
            Toast.makeText(app, "悬浮窗打开失败，请检查悬浮窗权限", Toast.LENGTH_LONG).show();
            return;
        }
        currentView = wrapper;
        currentWm = wm;
        currentHash = hash;
        currentShot = shot;
        wrapper.requestFocus();
        card.requestFocus();

        if (shot != null && shot.uri != null) {
            loadThumbAsync(app, shot.uri, thumb, wrapper);
        }

        // Spring entrance
        card.setTranslationY(UiKit.dp(app, 86));
        card.setAlpha(0f);
        card.animate().translationY(0f).alpha(1f).setDuration(300)
                .setInterpolator(new OvershootInterpolator(0.8f)).start();

        final String[] clipboardText = new String[]{forcedText};
        final Runnable dismissWithCleanup = new Runnable() {
            @Override
            public void run() {
                if (shot != null) {
                    IO_EXEC.execute(new Runnable() {
                        @Override public void run() { ShotStore.delete(app, shot); }
                    });
                }
                markDismissed(clipboardText[0]);
                dismissCurrent();
            }
        };

        // Dedicated swipe-down handle
        final float[] downY = new float[1];
        handleArea.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY[0] = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = Math.max(0f, e.getRawY() - downY[0]);
                        card.setTranslationY(dy * 0.8f);
                        card.setAlpha(Math.max(0.25f, 1f - dy / UiKit.dp(app, 200)));
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (card.getTranslationY() > UiKit.dp(app, 50)) {
                            dismissWithCleanup.run();
                        } else {
                            card.animate().translationY(0f).alpha(1f).setDuration(180).start();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        card.animate().translationY(0f).alpha(1f).setDuration(180).start();
                        return true;
                }
                return false;
            }
        });

        final Runnable readClipboard = new Runnable() {
            @Override
            public void run() {
                if (currentView != wrapper) return;
                String text = forcedText;
                if (text == null) {
                    text = readClipboard(app);
                }
                if (text == null || text.trim().isEmpty()) {
                    preview.setText("还没读到剪贴板，请重新复制或点重试");
                    tagTxt.setText("需要读取剪贴板");
                    retry.setVisibility(View.VISIBLE);
                    save.setEnabled(false);
                    save.setAlpha(0.45f);
                    return;
                }
                clipboardText[0] = text;
                retry.setVisibility(View.GONE);
                bindText(app, text, shot != null, platDot, tagTxt, preview, save, ignore, wrapper);
                if (NoteWriter.findSupportedUrl(text) == null && shot != null) {
                    IO_EXEC.execute(new Runnable() {
                        @Override public void run() { ShotStore.delete(app, shot); }
                    });
                    thumb.setVisibility(View.GONE);
                }
            }
        };

        ignore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                UiKit.hapticTick(view);
                dismissWithCleanup.run();
            }
        });

        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                UiKit.hapticTick(view);
                retry.setVisibility(View.GONE);
                preview.setText("正在读取剪贴板…");
                MAIN.postDelayed(readClipboard, 120);
            }
        });

        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final String text = clipboardText[0];
                if (text == null || text.trim().isEmpty()) {
                    return;
                }
                UiKit.hapticConfirm(app, view);
                save.setEnabled(false);
                save.setText("收纳中…");
                IO_EXEC.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String shotName = shot == null ? null : shot.fileName;
                            String shotUri = (shot != null && shot.uri != null)
                                    ? shot.uri.toString() : null;
                            final NoteWriter.Saved saved = NoteWriter.save(
                                    app, text, shotName, shotUri);
                            MAIN.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(app, "已收纳到 Inbox（"
                                            + NoteWriter.platformLabel(saved.platform) + "）",
                                            Toast.LENGTH_SHORT).show();
                                    markDismissed(text);
                                    if (currentView == wrapper) dismissCurrent();
                                }
                            });
                        } catch (final NoteWriter.NotConfiguredException exc) {
                            if (shot != null) ShotStore.delete(app, shot);
                            MAIN.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(app, "请先在「灵感」里选择 00-Inbox 文件夹",
                                            Toast.LENGTH_LONG).show();
                                    openMain(app);
                                    if (currentView == wrapper) dismissCurrent();
                                }
                            });
                        } catch (final Exception exc) {
                            MAIN.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (currentView != wrapper) return;
                                    tagTxt.setText("写入失败：" + safeMessage(exc));
                                    save.setText("重试收纳");
                                    save.setEnabled(true);
                                }
                            });
                        }
                    }
                });
            }
        });

        MAIN.postDelayed(readClipboard, forcedText != null ? 80 : 380);
    }

    private static void loadThumbAsync(final Context app, final Uri uri,
                                       final ImageView target, final View ownerWrapper) {
        if (thumbTask != null) {
            thumbTask.cancel(true);
        }
        thumbTask = IO_EXEC.submit(new Runnable() {
            @Override
            public void run() {
                if (Thread.currentThread().isInterrupted()) return;
                Bitmap bmp = null;
                try {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    try (InputStream is = app.getContentResolver().openInputStream(uri)) {
                        if (is != null) BitmapFactory.decodeStream(is, null, bounds);
                    }
                    int sample = 1;
                    int minSide = Math.min(bounds.outWidth, bounds.outHeight);
                    while (minSide / (sample * 2) >= 220) {
                        sample *= 2;
                    }
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    try (InputStream is = app.getContentResolver().openInputStream(uri)) {
                        if (is != null) bmp = BitmapFactory.decodeStream(is, null, opts);
                    }
                } catch (Exception ignored) {
                }
                final Bitmap result = bmp;
                if (result != null) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            if (currentView == ownerWrapper) {
                                target.setImageBitmap(result);
                            } else {
                                result.recycle();
                            }
                        }
                    });
                }
            }
        });
    }

    private static void bindText(final Context app, String text, final boolean hasShot,
                                 View platDot, final TextView tagTxt, TextView preview,
                                 final TextView save, TextView ignore, final View ownerWrapper) {
        String url = NoteWriter.findSupportedUrl(text);
        if (url == null) {
            platDot.setBackground(UiKit.roundRect(app, UiKit.PAPER_MUTED, 999));
            tagTxt.setText("未识别到 B 站、抖音、小红书或公众号链接");
            preview.setText(compact(text, 120));
            save.setEnabled(false);
            save.setVisibility(View.GONE);
            ignore.setText("关闭");
            return;
        }
        final String plat = NoteWriter.platformOf(url);
        final String platLabel = NoteWriter.platformLabel(plat);
        platDot.setBackground(UiKit.roundRect(app, InboxView.platformDotColor(plat), 999));
        tagTxt.setText(hasShot ? (platLabel + " · 已自动截图") : platLabel);
        preview.setText(NoteWriter.extractTitlePreview(text, url));

        save.setVisibility(View.VISIBLE);
        save.setEnabled(true);
        save.setAlpha(1f);
        ignore.setText("忽略");

        IO_EXEC.execute(new Runnable() {
            @Override
            public void run() {
                final String folder = NoteWriter.folderName(app);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (currentView != ownerWrapper) return;
                        save.setText(folder.isEmpty() ? "去选 Inbox 目录" : ("收纳到 " + folder));
                    }
                });
            }
        });
    }

    private static String compact(String text, int limit) {
        String value = text.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() > limit ? value.substring(0, limit) + "…" : value;
    }

    private static String safeMessage(Exception exc) {
        String message = exc.getMessage();
        return (message == null || message.isEmpty()) ? "未知错误" : message;
    }

    private static String readClipboard(Context context) {
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

    private static void openMain(Context app) {
        Intent intent = new Intent(app, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            app.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private static void markDismissed(String text) {
        if (text == null) return;
        dismissedHash = clipHash(text);
        dismissedAt = System.currentTimeMillis();
    }

    private static String clipHash(String text) {
        return Integer.toHexString(text.hashCode()) + ":" + text.length();
    }

    public static void dismissCurrent() {
        if (thumbTask != null) {
            thumbTask.cancel(true);
            thumbTask = null;
        }
        View view = currentView;
        WindowManager wm = currentWm;
        currentView = null;
        currentWm = null;
        currentHash = null;
        currentShot = null;
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
            }
        }
    }
}
