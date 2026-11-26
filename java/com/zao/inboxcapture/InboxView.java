package com.zao.inboxcapture;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「灵感」页（无框杂志流）：
 * - 平时只呈现安静的状态行 + 按今天/本周分组的内容画廊流（无描边卡片框）
 * - 权限与 Obsidian 目录收进右上角 ⚙ 瓷白底部抽屉；未开启全权限时顶部出一条温和引导
 */
public final class InboxView extends FrameLayout {
    public interface Host {
        boolean isAccessibilityEnabled();
        boolean isOldInstalled();
        void onOpenOverlaySettings();
        void onOpenAccessibilitySettings();
        void onOpenBatterySettings();
        void onPickFolder();
        void onTestPopup();
        void onCleanupOldVersion();
    }

    private static final ExecutorService THUMB_EXEC = Executors.newFixedThreadPool(2);
    private static final ExecutorService DELETE_EXEC = Executors.newSingleThreadExecutor();
    private final Host host;
    private final LinearLayout content;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int renderGeneration = 0;
    private SwipeDeleteRow openedRow;

    public InboxView(Context context, Host host) {
        super(context);
        this.host = host;

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnScrollChangeListener(new OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                if (Math.abs(scrollY - oldScrollY) > 4) {
                    closeOpenedRow();
                }
            }
        });

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, UiKit.dp(context, 10), 0, UiKit.dp(context, 48));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        refresh();
    }

    public void closeOpenedSwipeRow() {
        closeOpenedRow();
    }

    private void closeOpenedRow() {
        if (openedRow != null) {
            SwipeDeleteRow target = openedRow;
            openedRow = null;
            target.close(true);
        }
    }

    public void refresh() {
        final Context ctx = getContext();
        renderGeneration++;
        final int myGen = renderGeneration;
        openedRow = null;
        content.removeAllViews();

        boolean overlay = Settings.canDrawOverlays(ctx);
        boolean access = host.isAccessibilityEnabled();
        String folder = NoteWriter.folderName(ctx);
        boolean folderOk = !folder.isEmpty();
        boolean allReady = overlay && access && folderOk;
        int total = Prefs.savedCount(ctx);

        // 1. Quiet Subbar (● 自动收纳已开启   |   Obsidian · 128 条)
        LinearLayout subbar = new LinearLayout(ctx);
        subbar.setOrientation(LinearLayout.HORIZONTAL);
        subbar.setGravity(Gravity.CENTER_VERTICAL);
        subbar.setPadding(UiKit.dp(ctx, 24), UiKit.dp(ctx, 2),
                UiKit.dp(ctx, 24), UiKit.dp(ctx, 14));

        View dot = new View(ctx);
        dot.setBackground(UiKit.roundRect(ctx, allReady ? UiKit.OK_GREEN : UiKit.CORAL_END, 999));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 6), UiKit.dp(ctx, 6));
        dLp.rightMargin = UiKit.dp(ctx, 7);
        subbar.addView(dot, dLp);

        TextView statusTxt = new TextView(ctx);
        if (allReady) {
            statusTxt.setText("自动收纳已开启");
            statusTxt.setTextColor(UiKit.PAPER_SECOND);
        } else {
            int missing = (overlay ? 0 : 1) + (access ? 0 : 1) + (folderOk ? 0 : 1);
            statusTxt.setText("还有 " + missing + " 项权限待开启 · 去设置 ›");
            statusTxt.setTextColor(UiKit.CORAL_ACCENT);
            subbar.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.hapticTick(v);
                    openSettingsSheet();
                }
            });
        }
        statusTxt.setTextSize(12);
        statusTxt.setTypeface(Typeface.DEFAULT_BOLD);
        subbar.addView(statusTxt, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView countTxt = new TextView(ctx);
        countTxt.setText((folderOk ? folder : "Obsidian") + " · " + total + " 条");
        countTxt.setTextSize(12);
        countTxt.setTextColor(UiKit.PAPER_MUTED);
        countTxt.setFontFeatureSettings("tnum");
        subbar.addView(countTxt);
        content.addView(subbar);

        // 2. Editorial Stream (grouped by 今天 / 更早)
        List<Prefs.CaptureRecord> feed = Prefs.recentCaptures(ctx);
        if (feed.isEmpty()) {
            LinearLayout emptyBox = new LinearLayout(ctx);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            emptyBox.setPadding(UiKit.dp(ctx, 24), UiKit.dp(ctx, 48),
                    UiKit.dp(ctx, 24), UiKit.dp(ctx, 32));

            TextView eh = new TextView(ctx);
            eh.setText("在 B 站、抖音、小红书或公众号\n复制链接，或点右上角「添加」。");
            eh.setTextSize(18);
            eh.setTypeface(Typeface.DEFAULT_BOLD);
            eh.setTextColor(UiKit.PAPER_INK);
            eh.setLineSpacing(UiKit.dp(ctx, 6), 1f);
            emptyBox.addView(eh);

            String last = Prefs.lastSaved(ctx);
            TextView es = new TextView(ctx);
            es.setText(last.isEmpty()
                    ? "收纳后生成 Markdown，复制链接时可自动附上截图。"
                    : "最近一次收纳：" + last);
            es.setTextSize(13);
            es.setTextColor(UiKit.PAPER_MUTED);
            es.setLineSpacing(UiKit.dp(ctx, 5), 1f);
            es.setPadding(0, UiKit.dp(ctx, 12), 0, UiKit.dp(ctx, 22));
            emptyBox.addView(es);

            TextView testBtn = UiKit.makePrimaryPill(ctx, "试一下收纳弹窗");
            testBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.hapticTick(v);
                    host.onTestPopup();
                }
            });
            emptyBox.addView(testBtn, new LinearLayout.LayoutParams(
                    UiKit.dp(ctx, 180), UiKit.dp(ctx, 48)));

            content.addView(emptyBox);
            return;
        }

        List<Prefs.CaptureRecord> todayList = new ArrayList<>();
        List<Prefs.CaptureRecord> earlierList = new ArrayList<>();
        long todayStart = startOfTodayMs();
        for (Prefs.CaptureRecord rec : feed) {
            if (rec.timeMs >= todayStart) todayList.add(rec);
            else earlierList.add(rec);
        }

        if (!todayList.isEmpty()) {
            content.addView(groupLabel(ctx, "今天", false));
            for (Prefs.CaptureRecord rec : todayList) {
                content.addView(buildStreamItem(ctx, rec, myGen));
            }
        }
        if (!earlierList.isEmpty()) {
            content.addView(groupLabel(ctx, "更早", !todayList.isEmpty()));
            for (Prefs.CaptureRecord rec : earlierList) {
                content.addView(buildStreamItem(ctx, rec, myGen));
            }
        }

        TextView swipeHint = new TextView(ctx);
        swipeHint.setText("点击查看详情或回原 App · 左滑同步删除仓库文件");
        swipeHint.setTextSize(11);
        swipeHint.setTextColor(UiKit.PAPER_MUTED);
        swipeHint.setGravity(Gravity.CENTER);
        swipeHint.setPadding(UiKit.dp(ctx, 24), UiKit.dp(ctx, 8), UiKit.dp(ctx, 24), 0);
        content.addView(swipeHint);
    }

    private View groupLabel(Context ctx, String text, boolean extraTop) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(UiKit.PAPER_MUTED);
        tv.setLetterSpacing(0.06f);
        tv.setPadding(UiKit.dp(ctx, 24), UiKit.dp(ctx, extraTop ? 20 : 6),
                UiKit.dp(ctx, 24), UiKit.dp(ctx, 10));
        return tv;
    }

    private View buildStreamItem(final Context ctx, final Prefs.CaptureRecord rec, final int gen) {
        // Foreground row (opaque PAPER_BG so red delete pill stays cleanly hidden until swiped)
        LinearLayout fgRow = new LinearLayout(ctx);
        fgRow.setOrientation(LinearLayout.HORIZONTAL);
        fgRow.setGravity(Gravity.CENTER_VERTICAL);
        fgRow.setBackgroundColor(UiKit.PAPER_BG);
        fgRow.setClipChildren(false);
        fgRow.setClipToPadding(false);
        fgRow.setPadding(UiKit.dp(ctx, 24), UiKit.dp(ctx, 2),
                UiKit.dp(ctx, 24), UiKit.dp(ctx, 6));

        // Left Thumbnail Frame (72 x 90dp rounded, soft shadow, zero border)
        FrameLayout thumbWrap = new FrameLayout(ctx);
        thumbWrap.setBackground(UiKit.roundRect(ctx, UiKit.PAPER_MUTED_BG, 16));
        thumbWrap.setClipToOutline(true);
        thumbWrap.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), UiKit.dpf(ctx, 16));
            }
        });
        UiKit.applySoftShadow(thumbWrap, 6, 0x2614120E);

        UiKit.PlaceholderArtView art = new UiKit.PlaceholderArtView(ctx, rec.platform);
        thumbWrap.addView(art, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (!rec.shotUri.isEmpty()) {
            final ImageView img = new ImageView(ctx);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setVisibility(View.GONE);
            thumbWrap.addView(img, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            loadThumbInto(ctx, rec.shotUri, img, gen);
        }

        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 72), UiKit.dp(ctx, 90));
        tLp.rightMargin = UiKit.dp(ctx, 15);
        fgRow.addView(thumbWrap, tLp);

        // Right Editorial Copy
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(ctx);
        title.setText(rec.title.isEmpty() ? rec.fileName : rec.title);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.PAPER_INK);
        title.setLineSpacing(UiKit.dp(ctx, 4), 1f);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(title);

        LinearLayout meta = new LinearLayout(ctx);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = UiKit.dp(ctx, 7);

        View pdot = new View(ctx);
        pdot.setBackground(UiKit.roundRect(ctx, platformDotColor(rec.platform), 999));
        LinearLayout.LayoutParams pdLp = new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 6), UiKit.dp(ctx, 6));
        pdLp.rightMargin = UiKit.dp(ctx, 6);
        meta.addView(pdot, pdLp);

        TextView metaText = new TextView(ctx);
        metaText.setText(NoteWriter.platformLabel(rec.platform) + " · " + formatRelTime(rec.timeMs));
        metaText.setTextSize(12);
        metaText.setTextColor(UiKit.PAPER_MUTED);
        metaText.setFontFeatureSettings("tnum");
        meta.addView(metaText);

        body.addView(meta, mLp);
        fgRow.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final SwipeDeleteRow swipeRow = new SwipeDeleteRow(ctx, fgRow,
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openDetailSheet(ctx, rec);
                    }
                },
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        deleteRecordFromRow(ctx, rec, (SwipeDeleteRow) v.getParent(), (TextView) v);
                    }
                });
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = UiKit.dp(ctx, 12);
        swipeRow.setLayoutParams(rowLp);
        return swipeRow;
    }

    /** 点击灵感条目弹出详情抽屉：可阅读仓库里的完整内容、查看大图截图、跳转回原 App 或复制链接。 */
    private void openDetailSheet(final Context ctx, final Prefs.CaptureRecord rec) {
        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(ctx, 22), UiKit.dp(ctx, 12),
                UiKit.dp(ctx, 22), UiKit.dp(ctx, 26));
        root.setBackground(UiKit.topRoundSheet(ctx, 0xFFFAF9F6, 32));

        View handle = new View(ctx);
        handle.setBackground(UiKit.roundRect(ctx, 0xFFDFDCD5, 999));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 36), UiKit.dp(ctx, 4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = UiKit.dp(ctx, 14);
        root.addView(handle, hLp);

        // 顶部元信息栏：平台色点 + 平台与时间 + 状态标签
        LinearLayout metaBar = new LinearLayout(ctx);
        metaBar.setOrientation(LinearLayout.HORIZONTAL);
        metaBar.setGravity(Gravity.CENTER_VERTICAL);
        metaBar.setPadding(0, 0, 0, UiKit.dp(ctx, 12));

        View pdot = new View(ctx);
        pdot.setBackground(UiKit.roundRect(ctx, platformDotColor(rec.platform), 999));
        LinearLayout.LayoutParams pdLp = new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 7), UiKit.dp(ctx, 7));
        pdLp.rightMargin = UiKit.dp(ctx, 7);
        metaBar.addView(pdot, pdLp);

        TextView platTime = new TextView(ctx);
        platTime.setText(NoteWriter.platformLabel(rec.platform) + " · " + formatDetailTime(rec.timeMs));
        platTime.setTextSize(13);
        platTime.setTypeface(Typeface.DEFAULT_BOLD);
        platTime.setTextColor(UiKit.PAPER_SECOND);
        platTime.setFontFeatureSettings("tnum");
        metaBar.addView(platTime, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView statusBadge = new TextView(ctx);
        statusBadge.setTextSize(11);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setPadding(UiKit.dp(ctx, 9), UiKit.dp(ctx, 3),
                UiKit.dp(ctx, 9), UiKit.dp(ctx, 3));
        statusBadge.setVisibility(View.GONE);
        metaBar.addView(statusBadge);
        root.addView(metaBar);

        // 中部可滚动内容区（高度受限，确保底部按钮永远不裁切）
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int maxScrollH = Math.min(UiKit.dp(ctx, 430), (int) (dm.heightPixels * 0.56f));
        ScrollView bodyScroll = new ScrollView(ctx);
        bodyScroll.setVerticalScrollBarEnabled(false);

        LinearLayout bodyCol = new LinearLayout(ctx);
        bodyCol.setOrientation(LinearLayout.VERTICAL);

        // 截图预览区（有图才显示，点开可全屏查看长图）
        final FrameLayout shotWrap = new FrameLayout(ctx);
        shotWrap.setBackground(UiKit.roundRect(ctx, UiKit.PAPER_MUTED_BG, 18));
        shotWrap.setClipToOutline(true);
        shotWrap.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), UiKit.dpf(ctx, 18));
            }
        });
        UiKit.applySoftShadow(shotWrap, 6, 0x2214120E);
        shotWrap.setVisibility(View.GONE);

        final ImageView shotImg = new ImageView(ctx);
        shotImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        shotWrap.addView(shotImg, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 200)));

        TextView shotTag = new TextView(ctx);
        shotTag.setText("点看完整截图");
        shotTag.setTextSize(11);
        shotTag.setTypeface(Typeface.DEFAULT_BOLD);
        shotTag.setTextColor(Color.WHITE);
        shotTag.setBackground(UiKit.roundRect(ctx, 0x99121212, 999));
        shotTag.setPadding(UiKit.dp(ctx, 10), UiKit.dp(ctx, 4),
                UiKit.dp(ctx, 10), UiKit.dp(ctx, 4));
        LayoutParams tagLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.gravity = Gravity.END | Gravity.TOP;
        tagLp.topMargin = UiKit.dp(ctx, 10);
        tagLp.rightMargin = UiKit.dp(ctx, 10);
        shotWrap.addView(shotTag, tagLp);

        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 200));
        swLp.bottomMargin = UiKit.dp(ctx, 14);
        bodyCol.addView(shotWrap, swLp);

        final TextView bodyText = new TextView(ctx);
        bodyText.setText(rec.title.isEmpty() ? "正在读取笔记内容…" : rec.title);
        bodyText.setTextSize(15);
        bodyText.setTextColor(UiKit.PAPER_INK);
        bodyText.setLineSpacing(UiKit.dp(ctx, 6), 1f);
        bodyText.setTextIsSelectable(true);
        bodyCol.addView(bodyText);

        final TextView fileFoot = new TextView(ctx);
        String folder = NoteWriter.folderName(ctx);
        fileFoot.setText((folder.isEmpty() ? "00-Inbox" : folder) + "/" + rec.fileName);
        fileFoot.setTextSize(11);
        fileFoot.setTextColor(UiKit.PAPER_MUTED);
        fileFoot.setTypeface(Typeface.MONOSPACE);
        fileFoot.setPadding(0, UiKit.dp(ctx, 12), 0, UiKit.dp(ctx, 4));
        bodyCol.addView(fileFoot);

        bodyScroll.addView(bodyCol, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyScroll.setLayoutParams(scLp);
        bodyCol.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                int contentH = b - t;
                ViewGroup.LayoutParams lp = bodyScroll.getLayoutParams();
                int targetH = contentH > maxScrollH ? maxScrollH : ViewGroup.LayoutParams.WRAP_CONTENT;
                if (lp.height != targetH) {
                    lp.height = targetH;
                    bodyScroll.setLayoutParams(lp);
                }
            }
        });
        root.addView(bodyScroll);

        // 底部主按钮：跳回对应 App 看原文
        final String[] currentUrl = new String[]{rec.url};
        final Uri[] currentShotUri = new Uri[]{rec.shotUri.isEmpty() ? null : Uri.parse(rec.shotUri)};

        final TextView openBtn = UiKit.makePrimaryPill(ctx, openButtonLabel(rec.platform));
        LinearLayout.LayoutParams obLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 52));
        obLp.topMargin = UiKit.dp(ctx, 16);
        openBtn.setEnabled(!currentUrl[0].isEmpty());
        openBtn.setAlpha(currentUrl[0].isEmpty() ? 0.45f : 1f);
        openBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentUrl[0].isEmpty()) return;
                UiKit.hapticTick(v);
                openOriginalAppOrUrl(ctx, rec.platform, currentUrl[0]);
            }
        });
        root.addView(openBtn, obLp);

        // 次操作行：复制链接 · 删除这条灵感
        LinearLayout secRow = new LinearLayout(ctx);
        secRow.setOrientation(LinearLayout.HORIZONTAL);
        secRow.setGravity(Gravity.CENTER);
        secRow.setPadding(0, UiKit.dp(ctx, 12), 0, 0);

        final TextView copyLinkBtn = new TextView(ctx);
        copyLinkBtn.setText("复制链接");
        copyLinkBtn.setTextSize(13);
        copyLinkBtn.setTypeface(Typeface.DEFAULT_BOLD);
        copyLinkBtn.setTextColor(UiKit.PAPER_SECOND);
        copyLinkBtn.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 6),
                UiKit.dp(ctx, 12), UiKit.dp(ctx, 6));
        copyLinkBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentUrl[0].isEmpty()) {
                    Toast.makeText(ctx, "这条记录里没有原文链接", Toast.LENGTH_SHORT).show();
                    return;
                }
                UiKit.hapticTick(v);
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("url", currentUrl[0]));
                    Toast.makeText(ctx, "已复制原文链接", Toast.LENGTH_SHORT).show();
                }
            }
        });
        secRow.addView(copyLinkBtn);

        TextView sep = new TextView(ctx);
        sep.setText("·");
        sep.setTextSize(13);
        sep.setTextColor(0xFFD4D0C7);
        secRow.addView(sep);

        final TextView delLinkBtn = new TextView(ctx);
        delLinkBtn.setText("删除这条灵感");
        delLinkBtn.setTextSize(13);
        delLinkBtn.setTypeface(Typeface.DEFAULT_BOLD);
        delLinkBtn.setTextColor(UiKit.DANGER_RED);
        delLinkBtn.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 6),
                UiKit.dp(ctx, 12), UiKit.dp(ctx, 6));
        delLinkBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                delLinkBtn.setEnabled(false);
                delLinkBtn.setText("删除中…");
                UiKit.hapticConfirm(ctx, v);
                final Context appCtx = ctx.getApplicationContext();
                DELETE_EXEC.execute(new Runnable() {
                    @Override
                    public void run() {
                        final NoteWriter.DeleteResult res = NoteWriter.deleteCapture(appCtx, rec);
                        if (res.error == null) {
                            Prefs.removeCapture(appCtx, rec);
                        }
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (res.error != null) {
                                    delLinkBtn.setEnabled(true);
                                    delLinkBtn.setText("删除这条灵感");
                                    Toast.makeText(appCtx, res.error, Toast.LENGTH_LONG).show();
                                    return;
                                }
                                dlg.dismiss();
                                String msg = (res.mdDeleted && res.shotDeleted) ? "已同步删除笔记与截图"
                                        : (res.mdDeleted ? "已同步删除仓库笔记"
                                        : "仓库中已无此文件，已从列表移除");
                                Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show();
                                if (isAttachedToWindow()) {
                                    refresh();
                                }
                            }
                        });
                    }
                });
            }
        });
        secRow.addView(delLinkBtn);
        root.addView(secRow);

        shotWrap.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentShotUri[0] != null) {
                    UiKit.hapticTick(v);
                    openFullScreenshotDialog(ctx, currentShotUri[0]);
                }
            }
        });

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.36f);
        }
        dlg.show();

        // 异步读取仓库里的最新 Markdown 正文、链接、状态与高清截图预览
        final Context appCtx = ctx.getApplicationContext();
        THUMB_EXEC.execute(new Runnable() {
            @Override
            public void run() {
                final NoteWriter.CaptureDetail detail = NoteWriter.readCaptureDetail(appCtx, rec);
                final Bitmap shotBmp = (detail.shotDocUri != null)
                        ? decodeBoundedBitmap(appCtx, detail.shotDocUri, 1080)
                        : null;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!dlg.isShowing()) {
                            if (shotBmp != null) shotBmp.recycle();
                            return;
                        }
                        if (!detail.url.isEmpty()) {
                            currentUrl[0] = detail.url;
                            openBtn.setEnabled(true);
                            openBtn.setAlpha(1f);
                        }
                        if (detail.shotDocUri != null) {
                            currentShotUri[0] = detail.shotDocUri;
                        }
                        if (shotBmp != null) {
                            shotImg.setImageBitmap(shotBmp);
                            shotWrap.setVisibility(View.VISIBLE);
                        }
                        if (!detail.body.isEmpty()) {
                            bodyText.setText(detail.body);
                        }
                        if (!detail.foundInVault) {
                            fileFoot.setText(rec.fileName + " · 当前不在 00-Inbox");
                        }
                        if ("done".equalsIgnoreCase(detail.status)) {
                            statusBadge.setText("已整理");
                            statusBadge.setTextColor(UiKit.OK_GREEN);
                            statusBadge.setBackground(UiKit.roundRect(ctx, 0xFFEAF6EF, 999));
                            statusBadge.setVisibility(View.VISIBLE);
                        } else if ("pending".equalsIgnoreCase(detail.status)) {
                            statusBadge.setText("待整理");
                            statusBadge.setTextColor(UiKit.PAPER_SECOND);
                            statusBadge.setBackground(UiKit.roundRect(ctx, 0xFFEFECE4, 999));
                            statusBadge.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        });
    }

    private void openFullScreenshotDialog(final Context ctx, final Uri shotUri) {
        final Dialog dlg = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B0D);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(UiKit.dp(ctx, 20), UiKit.dp(ctx, 18),
                UiKit.dp(ctx, 20), UiKit.dp(ctx, 12));

        TextView lbl = new TextView(ctx);
        lbl.setText("收纳截图预览");
        lbl.setTextSize(14);
        lbl.setTextColor(0xCCB8B6B0);
        bar.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView closeBtn = new TextView(ctx);
        closeBtn.setText("关闭");
        closeBtn.setTextSize(13);
        closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setBackground(UiKit.roundRect(ctx, 0x26FFFFFF, 999));
        closeBtn.setPadding(UiKit.dp(ctx, 14), UiKit.dp(ctx, 6),
                UiKit.dp(ctx, 14), UiKit.dp(ctx, 6));
        closeBtn.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        bar.addView(closeBtn);
        root.addView(bar);

        ScrollView sc = new ScrollView(ctx);
        sc.setVerticalScrollBarEnabled(false);
        final ImageView fullImg = new ImageView(ctx);
        fullImg.setAdjustViewBounds(true);
        fullImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fullImg.setPadding(UiKit.dp(ctx, 12), 0, UiKit.dp(ctx, 12), UiKit.dp(ctx, 24));
        fullImg.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        sc.addView(fullImg, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dlg.setContentView(root);
        dlg.show();

        final Context appCtx = ctx.getApplicationContext();
        THUMB_EXEC.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = decodeBoundedBitmap(appCtx, shotUri, 1600);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (dlg.isShowing() && bmp != null) {
                            fullImg.setImageBitmap(bmp);
                        } else if (bmp != null) {
                            bmp.recycle();
                        }
                    }
                });
            }
        });
    }

    private static Bitmap decodeBoundedBitmap(Context ctx, Uri uri, int maxSidePx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                if (is != null) BitmapFactory.decodeStream(is, null, bounds);
            }
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            if (maxDim <= 0) return null;
            int sample = 1;
            while (maxDim / (sample * 2) >= maxSidePx) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                if (is != null) return BitmapFactory.decodeStream(is, null, opts);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String openButtonLabel(String platform) {
        if ("xiaohongshu".equals(platform)) return "在小红书打开原文 ↗";
        if ("douyin".equals(platform)) return "在抖音打开原文 ↗";
        if ("wechat".equals(platform)) return "打开公众号原文 ↗";
        if ("bilibili".equals(platform)) return "在 B 站打开原文 ↗";
        return "打开原文链接 ↗";
    }

    private static void openOriginalAppOrUrl(Context ctx, String platform, String url) {
        Uri uri = Uri.parse(url);
        String[] preferredPkgs;
        if ("xiaohongshu".equals(platform)) {
            preferredPkgs = new String[]{"com.xingin.xhs"};
        } else if ("douyin".equals(platform)) {
            preferredPkgs = new String[]{"com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite"};
        } else if ("bilibili".equals(platform)) {
            preferredPkgs = new String[]{"tv.danmaku.bili"};
        } else {
            preferredPkgs = new String[0];
        }

        for (String pkg : preferredPkgs) {
            try {
                Intent direct = new Intent(Intent.ACTION_VIEW, uri);
                direct.setPackage(pkg);
                direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(direct);
                return;
            } catch (ActivityNotFoundException ignored) {
                // 该短链或 App 版本未直接接管该 Intent，回退到系统默认跳转
            } catch (Exception ignored) {
            }
        }

        try {
            Intent fallback = new Intent(Intent.ACTION_VIEW, uri);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(fallback);
        } catch (Exception exc) {
            Toast.makeText(ctx, "无法打开链接，可先点「复制链接」", Toast.LENGTH_SHORT).show();
        }
    }

    private static String formatDetailTime(long ms) {
        if (ms <= 0) return "";
        return new SimpleDateFormat("M/d HH:mm", Locale.US).format(new Date(ms));
    }

    private void deleteRecordFromRow(final Context ctx, final Prefs.CaptureRecord rec,
                                     final SwipeDeleteRow row, final TextView delBtn) {
        if (row.deleting) return;
        row.deleting = true;
        delBtn.setEnabled(false);
        delBtn.setText("删除中");
        delBtn.setAlpha(0.75f);
        UiKit.hapticConfirm(ctx, delBtn);

        final Context appCtx = ctx.getApplicationContext();
        DELETE_EXEC.execute(new Runnable() {
            @Override
            public void run() {
                final NoteWriter.DeleteResult res = NoteWriter.deleteCapture(appCtx, rec);
                if (res.error == null) {
                    Prefs.removeCapture(appCtx, rec);
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (res.error != null) {
                            row.deleting = false;
                            delBtn.setEnabled(true);
                            delBtn.setText("删除");
                            delBtn.setAlpha(1f);
                            row.close(true);
                            Toast.makeText(appCtx, res.error, Toast.LENGTH_LONG).show();
                            return;
                        }

                        String msg;
                        if (res.mdDeleted && res.shotDeleted) {
                            msg = "已同步删除笔记与截图";
                        } else if (res.mdDeleted) {
                            msg = "已同步删除仓库笔记";
                        } else if (res.shotDeleted) {
                            msg = "笔记已不在仓库，已清理截图并移出列表";
                        } else {
                            msg = "仓库中已无此文件，已从列表移除";
                        }
                        Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show();

                        if (!isAttachedToWindow()) return;
                        if (openedRow == row) {
                            openedRow = null;
                        }
                        row.animate()
                                .alpha(0f)
                                .translationX(-UiKit.dp(ctx, 48))
                                .setDuration(170)
                                .setInterpolator(new DecelerateInterpolator())
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (isAttachedToWindow()) {
                                            refresh();
                                        }
                                    }
                                })
                                .start();
                    }
                });
            }
        });
    }

    /** 左滑露出红色「删除」圆角卡片；点击打开详情；绝不拦截右滑以免与全面屏返回手势冲突。 */
    private final class SwipeDeleteRow extends FrameLayout {
        private final View fgView;
        private final TextView deleteBtn;
        private final OnClickListener onRowClick;
        private final float revealPx;
        private final float actionRightHitPx;
        private final int touchSlop;

        private float downX;
        private float downY;
        private float startTx;
        private boolean horizontalLocked;
        private boolean verticalLocked;
        private boolean tappedFgWhileOpen;
        private boolean closedOtherOnDown;
        private VelocityTracker velocityTracker;
        private ValueAnimator snapAnim;
        boolean deleting;

        SwipeDeleteRow(Context ctx, View fgView, OnClickListener onRowClick, OnClickListener onDeleteClick) {
            super(ctx);
            this.fgView = fgView;
            this.onRowClick = onRowClick;
            this.touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
            int actionW = UiKit.dp(ctx, 76);
            int rightMargin = UiKit.dp(ctx, 24);
            int gap = UiKit.dp(ctx, 4);
            this.revealPx = actionW + rightMargin + gap;
            this.actionRightHitPx = actionW + rightMargin;

            setClipChildren(false);
            setClipToPadding(false);

            deleteBtn = new TextView(ctx);
            deleteBtn.setText("删除");
            deleteBtn.setTextSize(14);
            deleteBtn.setTypeface(Typeface.DEFAULT_BOLD);
            deleteBtn.setTextColor(Color.WHITE);
            deleteBtn.setGravity(Gravity.CENTER);
            deleteBtn.setBackground(UiKit.rippleWrap(
                    UiKit.roundRect(ctx, UiKit.DANGER_RED, 16), 0x33FFFFFF));
            deleteBtn.setOnClickListener(onDeleteClick);
            deleteBtn.setVisibility(View.INVISIBLE);
            deleteBtn.setAlpha(0f);
            deleteBtn.setScaleX(0.88f);
            deleteBtn.setScaleY(0.88f);

            LayoutParams delLp = new LayoutParams(actionW, UiKit.dp(ctx, 90));
            delLp.gravity = Gravity.END | Gravity.TOP;
            delLp.topMargin = UiKit.dp(ctx, 2);
            delLp.rightMargin = rightMargin;
            addView(deleteBtn, delLp);

            addView(fgView, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (snapAnim != null) {
                snapAnim.cancel();
                snapAnim = null;
            }
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
        }

        private boolean isOpen() {
            return fgView.getTranslationX() <= -revealPx * 0.5f;
        }

        private void setSwipeOffset(float tx) {
            fgView.setTranslationX(tx);
            float p = Math.max(0f, Math.min(1f, -tx / revealPx));
            if (p <= 0.02f) {
                deleteBtn.setVisibility(View.INVISIBLE);
                deleteBtn.setAlpha(0f);
            } else {
                if (deleteBtn.getVisibility() != View.VISIBLE) {
                    deleteBtn.setVisibility(View.VISIBLE);
                }
                deleteBtn.setAlpha(0.25f + 0.75f * p);
                float s = 0.88f + 0.12f * p;
                deleteBtn.setScaleX(s);
                deleteBtn.setScaleY(s);
            }
        }

        void open(boolean animate) {
            if (openedRow != null && openedRow != this) {
                openedRow.close(true);
            }
            openedRow = this;
            animateTo(-revealPx, animate, true);
        }

        void close(boolean animate) {
            if (openedRow == this) {
                openedRow = null;
            }
            animateTo(0f, animate, false);
        }

        private void animateTo(final float target, boolean animate, final boolean hapticOnEnd) {
            if (snapAnim != null) {
                snapAnim.cancel();
                snapAnim = null;
            }
            if (!animate) {
                setSwipeOffset(target);
                return;
            }
            float from = fgView.getTranslationX();
            if (Math.abs(from - target) < 0.5f) {
                setSwipeOffset(target);
                return;
            }
            snapAnim = ValueAnimator.ofFloat(from, target);
            snapAnim.setDuration(190);
            snapAnim.setInterpolator(new DecelerateInterpolator(2.0f));
            snapAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    setSwipeOffset((Float) animation.getAnimatedValue());
                }
            });
            snapAnim.start();
            if (hapticOnEnd) {
                UiKit.hapticTick(fgView);
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (deleting) return true;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    startTx = fgView.getTranslationX();
                    horizontalLocked = false;
                    verticalLocked = false;
                    tappedFgWhileOpen = isOpen() && downX < (getWidth() - actionRightHitPx);
                    closedOtherOnDown = (openedRow != null && openedRow != this);
                    if (closedOtherOnDown) {
                        openedRow.close(true);
                    }
                    // 点在已露出的删除按钮上时不拦截，让按钮直接响应点击
                    if (isOpen() && downX >= (getWidth() - actionRightHitPx)) {
                        return false;
                    }
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (!horizontalLocked && !verticalLocked) {
                        if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                            if (dx < 0 || startTx < -1f) {
                                horizontalLocked = true;
                                if (getParent() != null) {
                                    getParent().requestDisallowInterceptTouchEvent(true);
                                }
                                return true;
                            }
                        } else if (Math.abs(dy) > touchSlop) {
                            verticalLocked = true;
                        }
                    }
                    return horizontalLocked;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (deleting) return true;
            if (velocityTracker == null) {
                velocityTracker = VelocityTracker.obtain();
            }
            velocityTracker.addMovement(ev);

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    startTx = fgView.getTranslationX();
                    horizontalLocked = false;
                    verticalLocked = false;
                    tappedFgWhileOpen = isOpen() && downX < (getWidth() - actionRightHitPx);
                    if (tappedFgWhileOpen && getParent() != null) {
                        // 当前行已左滑展开时，优先让右滑把这一行关掉，不直接翻页
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (snapAnim != null) {
                        snapAnim.cancel();
                        snapAnim = null;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (!horizontalLocked && !verticalLocked) {
                        if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                            if (dx < 0 || startTx < -1f) {
                                horizontalLocked = true;
                                if (openedRow != null && openedRow != this) {
                                    openedRow.close(true);
                                }
                                if (getParent() != null) {
                                    getParent().requestDisallowInterceptTouchEvent(true);
                                }
                            } else {
                                return false;
                            }
                        } else if (Math.abs(dy) > touchSlop) {
                            verticalLocked = true;
                            closeOpenedRow();
                            return false;
                        }
                    }
                    if (horizontalLocked) {
                        float raw = startTx + dx;
                        if (raw > 0f) {
                            raw = 0f;
                        } else if (raw < -revealPx) {
                            raw = -revealPx + (raw + revealPx) * 0.25f;
                        }
                        setSwipeOffset(raw);
                        return true;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    velocityTracker.computeCurrentVelocity(1000);
                    float vx = velocityTracker.getXVelocity();
                    velocityTracker.recycle();
                    velocityTracker = null;

                    if (horizontalLocked) {
                        boolean shouldOpen;
                        if (vx < -450f) {
                            shouldOpen = true;
                        } else if (vx > 450f) {
                            shouldOpen = false;
                        } else {
                            shouldOpen = fgView.getTranslationX() < -revealPx * 0.42f;
                        }
                        if (shouldOpen) {
                            open(true);
                        } else {
                            close(true);
                        }
                    } else if (tappedFgWhileOpen && ev.getActionMasked() == MotionEvent.ACTION_UP) {
                        close(true);
                    } else if (!verticalLocked && !closedOtherOnDown && !isOpen()
                            && ev.getActionMasked() == MotionEvent.ACTION_UP) {
                        float moveX = Math.abs(ev.getX() - downX);
                        float moveY = Math.abs(ev.getY() - downY);
                        if (moveX < touchSlop && moveY < touchSlop && onRowClick != null) {
                            UiKit.hapticTick(fgView);
                            onRowClick.onClick(fgView);
                        }
                    }
                    horizontalLocked = false;
                    verticalLocked = false;
                    tappedFgWhileOpen = false;
                    closedOtherOnDown = false;
                    return true;
            }
            return super.onTouchEvent(ev);
        }
    }

    private void loadThumbInto(final Context ctx, final String uriStr,
                               final ImageView target, final int gen) {
        THUMB_EXEC.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    Uri uri = Uri.parse(uriStr);
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                        if (is != null) BitmapFactory.decodeStream(is, null, bounds);
                    }
                    int sample = 1;
                    int minSide = Math.min(bounds.outWidth, bounds.outHeight);
                    if (minSide <= 0) return;
                    while (minSide / (sample * 2) >= 220) sample *= 2;
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                        if (is != null) bmp = BitmapFactory.decodeStream(is, null, opts);
                    }
                } catch (Exception ignored) {
                }
                final Bitmap result = bmp;
                if (result != null) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (renderGeneration == gen && isAttachedToWindow()) {
                                target.setImageBitmap(result);
                                target.setVisibility(View.VISIBLE);
                            } else {
                                result.recycle();
                            }
                        }
                    });
                }
            }
        });
    }

    public void openSettingsSheet() {
        final Context ctx = getContext();
        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(ctx, 24), UiKit.dp(ctx, 12),
                UiKit.dp(ctx, 24), UiKit.dp(ctx, 28));
        root.setBackground(UiKit.topRoundSheet(ctx, 0xFFFAF9F6, 32));

        View handle = new View(ctx);
        handle.setBackground(UiKit.roundRect(ctx, 0xFFDFDCD5, 999));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 36), UiKit.dp(ctx, 4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = UiKit.dp(ctx, 18);
        root.addView(handle, hLp);

        TextView head = new TextView(ctx);
        head.setText("收纳设置");
        head.setTextSize(20);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(UiKit.PAPER_INK);
        root.addView(head);

        TextView sub = new TextView(ctx);
        sub.setText("全部本地保存 · 不联网、不上传");
        sub.setTextSize(12);
        sub.setTextColor(UiKit.PAPER_MUTED);
        sub.setPadding(0, UiKit.dp(ctx, 4), 0, UiKit.dp(ctx, 16));
        root.addView(sub);

        boolean overlay = Settings.canDrawOverlays(ctx);
        boolean access = host.isAccessibilityEnabled();
        String folder = NoteWriter.folderName(ctx);
        boolean folderOk = !folder.isEmpty();

        root.addView(settingRow(ctx, "悬浮窗权限",
                overlay ? "已开启" : "去开启 ›",
                overlay ? UiKit.OK_GREEN : UiKit.CORAL_ACCENT,
                new OnClickListener() {
                    @Override public void onClick(View v) { dlg.dismiss(); host.onOpenOverlaySettings(); }
                }));
        root.addView(thinDivider(ctx));

        root.addView(settingRow(ctx, "无障碍监听",
                access ? "已开启" : "去开启 ›",
                access ? UiKit.OK_GREEN : UiKit.CORAL_ACCENT,
                new OnClickListener() {
                    @Override public void onClick(View v) { dlg.dismiss(); host.onOpenAccessibilitySettings(); }
                }));
        root.addView(thinDivider(ctx));

        root.addView(settingRow(ctx, "防掉线（后台耗电设置）",
                "去设为不限制 ›", UiKit.PAPER_SECOND,
                new OnClickListener() {
                    @Override public void onClick(View v) { dlg.dismiss(); host.onOpenBatterySettings(); }
                }));
        root.addView(thinDivider(ctx));

        root.addView(settingRow(ctx, "Obsidian Inbox 目录",
                folderOk ? folder + " ›" : "去选择 ›",
                folderOk ? UiKit.PAPER_SECOND : UiKit.CORAL_ACCENT,
                new OnClickListener() {
                    @Override public void onClick(View v) { dlg.dismiss(); host.onPickFolder(); }
                }));
        root.addView(thinDivider(ctx));

        root.addView(settingRow(ctx, "试一下收纳弹窗",
                "弹出预览 ›", UiKit.PAPER_SECOND,
                new OnClickListener() {
                    @Override public void onClick(View v) { dlg.dismiss(); host.onTestPopup(); }
                }));

        if (host.isOldInstalled()) {
            root.addView(thinDivider(ctx));
            root.addView(settingRow(ctx, "清理旧版「灵感收纳」",
                    "去卸载 ›", UiKit.DANGER_RED,
                    new OnClickListener() {
                        @Override public void onClick(View v) { dlg.dismiss(); host.onCleanupOldVersion(); }
                    }));
        }

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.32f);
        }
        dlg.show();
    }

    private View settingRow(Context ctx, String title, String value, int valueColor,
                            OnClickListener click) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(ctx, 54));
        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                click.onClick(v);
            }
        });

        TextView l = new TextView(ctx);
        l.setText(title);
        l.setTextSize(15);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        l.setTextColor(UiKit.PAPER_INK);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView r = new TextView(ctx);
        r.setText(value);
        r.setTextSize(13);
        r.setTypeface(Typeface.DEFAULT_BOLD);
        r.setTextColor(valueColor);
        row.addView(r);
        return row;
    }

    private View thinDivider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(UiKit.PAPER_DIVIDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 1)));
        return v;
    }

    public static int platformDotColor(String platform) {
        if ("xiaohongshu".equals(platform)) return UiKit.DOT_XHS;
        if ("douyin".equals(platform)) return UiKit.DOT_DY;
        if ("wechat".equals(platform)) return UiKit.DOT_WX;
        if ("bilibili".equals(platform)) return UiKit.DOT_BILI;
        return UiKit.CORAL_END;
    }

    private static long startOfTodayMs() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static String formatRelTime(long ms) {
        if (ms <= 0) return "";
        long diff = Math.max(0, System.currentTimeMillis() - ms);
        if (diff < 60_000L) return "刚刚";
        if (diff < 3_600_000L) return (diff / 60_000L) + " 分钟前";
        if (diff < 86_400_000L) return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
        return new SimpleDateFormat("M/d", Locale.US).format(new Date(ms));
    }
}
