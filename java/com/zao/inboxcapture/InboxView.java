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
import android.util.LruCache;
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
import android.widget.AbsListView;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 灵感收纳的分日列表、详情和设置。 */
public final class InboxView extends FrameLayout {
    public interface Host {
        boolean isAccessibilityEnabled();
        boolean isOldInstalled();
        void onOpenOverlaySettings();
        void onOpenAccessibilitySettings();
        void onOpenBatterySettings();
        void onPickFolder();
        void onTestPopup();
        void onCheckUpdate();
        void onConnectComputer();
        void onCleanupOldVersion();
    }

    private static final ExecutorService THUMB_EXEC = Executors.newFixedThreadPool(2);
    private static final ExecutorService DELETE_EXEC = Executors.newSingleThreadExecutor();
    private static final ExecutorService HISTORY_EXEC = Executors.newSingleThreadExecutor();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA);
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA);
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA);

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExpandableListView archive;
    private final ArchiveAdapter adapter = new ArchiveAdapter();
    private final Map<LocalDate, Boolean> chosenExpansion = new HashMap<>();
    private final LruCache<String, Bitmap> thumbnails = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
    private final TextView statusLabel;
    private final View statusDot;
    private final TextView folderLabel;
    private final TextView emptyLabel;
    private final LinearLayout statusBar;
    private LocalDate today = LocalDate.now();
    private String currentTree;
    private Future<?> historyJob;
    private int generation;
    private SwipeDeleteRow openedRow;

    public InboxView(Context context, Host host) {
        super(context);
        this.host = host;
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(-1, -1));

        statusBar = new LinearLayout(context);
        statusBar.setGravity(Gravity.CENTER_VERTICAL);
        int inset = UiKit.dp(context, 20);
        statusBar.setPadding(inset, 0, inset, 0);
        statusBar.setMinimumHeight(UiKit.dp(context, 44));
        statusDot = new View(context);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                UiKit.dp(context, 7), UiKit.dp(context, 7));
        dotParams.rightMargin = UiKit.dp(context, 8);
        statusBar.addView(statusDot, dotParams);
        statusLabel = listText(context, 14, UiKit.PAPER_INK);
        statusLabel.setTypeface(Typeface.DEFAULT_BOLD);
        statusBar.addView(statusLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        folderLabel = listText(context, 14, UiKit.PAPER_SECOND);
        folderLabel.setCompoundDrawablesWithIntrinsicBounds(
                new ArchiveFolderIcon(context), null, null, null);
        folderLabel.setCompoundDrawablePadding(UiKit.dp(context, 6));
        statusBar.addView(folderLabel);
        column.addView(statusBar);
        View rule = new View(context);
        rule.setBackgroundColor(0xFFDAD7CF);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(-1, 1);
        ruleParams.setMargins(inset, 0, inset, UiKit.dp(context, 6));
        column.addView(rule, ruleParams);

        FrameLayout body = new FrameLayout(context);
        column.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        archive = new ExpandableListView(context);
        archive.setGroupIndicator(null);
        archive.setChildDivider(null);
        archive.setDivider(null);
        archive.setVerticalScrollBarEnabled(false);
        archive.setCacheColorHint(Color.TRANSPARENT);
        archive.setAdapter(adapter);
        archive.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                if (state != SCROLL_STATE_IDLE) closeOpenedRow();
            }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) {}
        });
        archive.setOnGroupClickListener((list, row, index, id) -> {
            closeOpenedRow();
            boolean expand = !archive.isGroupExpanded(index);
            chosenExpansion.put(adapter.getGroup(index).date, expand);
            UiKit.hapticTick(row);
            if (expand) archive.expandGroup(index);
            else archive.collapseGroup(index);
            return true;
        });
        archive.setRecyclerListener(view -> {
            if (view instanceof SwipeDeleteRow) {
                SwipeDeleteRow row = (SwipeDeleteRow) view;
                row.close(false);
                ((RecordLine) row.fgView).cancelThumbnail();
            }
        });
        body.addView(archive, new LayoutParams(-1, -1));
        emptyLabel = listText(context, 15, UiKit.PAPER_SECOND);
        emptyLabel.setGravity(Gravity.CENTER);
        emptyLabel.setPadding(inset, UiKit.dp(context, 54), inset, 0);
        body.addView(emptyLabel, new LayoutParams(-1, -2));
        archive.setEmptyView(emptyLabel);
    }

    public void closeOpenedSwipeRow() {
        closeOpenedRow();
    }

    private void closeOpenedRow() {
        if (openedRow != null) openedRow.close(true);
    }

    public void refresh() {
        Context ctx = getContext();
        Context app = ctx.getApplicationContext();
        String tree = Prefs.treeUri(ctx);
        boolean changed = !tree.equals(currentTree);
        currentTree = tree;
        if (changed) {
            chosenExpansion.clear();
            adapter.groups.clear();
            adapter.notifyDataSetChanged();
            thumbnails.evictAll();
        }
        int request = ++generation;
        if (historyJob != null) historyJob.cancel(true);
        boolean overlay = Settings.canDrawOverlays(ctx);
        boolean accessibility = host.isAccessibilityEnabled();
        updateStatus(overlay, accessibility, tree.isEmpty() ? "" : NoteWriter.FOLDER_NAME);
        if (adapter.groups.isEmpty()) emptyLabel.setText("读取中…");
        historyJob = HISTORY_EXEC.submit(() -> {
            try {
                String folder = NoteWriter.folderName(app);
                List<Day> days = groupByDay(CaptureHistory.load(app), ZoneId.systemDefault());
                main.post(() -> {
                    if (generation != request) return;
                    updateStatus(overlay, accessibility, folder);
                    emptyLabel.setText("暂无收纳");
                    showDays(days);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (generation != request) return;
                    emptyLabel.setText("无法读取收纳目录");
                    updateStatus(overlay, accessibility, "");
                    if (!adapter.groups.isEmpty())
                        Toast.makeText(ctx, "无法刷新收纳目录", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Override protected void onDetachedFromWindow() {
        generation++;
        if (historyJob != null) historyJob.cancel(true);
        super.onDetachedFromWindow();
    }

    private void updateStatus(boolean overlay, boolean accessibility, String folder) {
        boolean ready = overlay && accessibility && NoteWriter.FOLDER_NAME.equals(folder);
        statusDot.setBackground(UiKit.roundRect(getContext(), ready ? 0xFF24765A : UiKit.WARN_AMBER, 8));
        statusLabel.setText(ready ? "自动收纳已开启" : "自动收纳待设置 ›");
        statusLabel.setTextColor(ready ? UiKit.PAPER_INK : 0xFFA53822);
        statusBar.setOnClickListener(ready ? null : v -> openSettingsSheet());
        statusBar.setClickable(!ready);
        folderLabel.setText(folder.isEmpty() ? "未选择" : folder);
    }

    private static final class Day {
        final LocalDate date;
        final List<Prefs.CaptureRecord> records = new ArrayList<>();
        Day(LocalDate date) { this.date = date; }
    }

    private static List<Day> groupByDay(List<Prefs.CaptureRecord> records, ZoneId zone) {
        // CaptureHistory returns captures in descending time order.
        Map<LocalDate, Day> days = new LinkedHashMap<>();
        for (Prefs.CaptureRecord item : records) {
            LocalDate date = Instant.ofEpochMilli(item.timeMs).atZone(zone).toLocalDate();
            days.computeIfAbsent(date, Day::new).records.add(item);
        }
        return new ArrayList<>(days.values());
    }

    private void showDays(List<Day> days) {
        closeOpenedRow();
        today = LocalDate.now();
        adapter.groups = days;
        adapter.notifyDataSetChanged();
        LocalDate yesterday = today.minusDays(1);
        for (int i = 0; i < days.size(); i++) {
            LocalDate date = days.get(i).date;
            boolean expanded = chosenExpansion.getOrDefault(date,
                    date.equals(today) || date.equals(yesterday));
            if (expanded != archive.isGroupExpanded(i)) {
                if (expanded) archive.expandGroup(i);
                else archive.collapseGroup(i);
            }
        }
    }

    private static TextView listText(Context ctx, int size, int color) {
        TextView text = new TextView(ctx);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        return text;
    }

    private final class DayHeader extends LinearLayout {
        final TextView name, count, arrow;
        DayHeader(Context ctx) {
            super(ctx);
            int inset = UiKit.dp(ctx, 14);
            setPadding(inset, UiKit.dp(ctx, 3), inset, UiKit.dp(ctx, 3));
            LinearLayout band = new LinearLayout(ctx);
            band.setGravity(Gravity.CENTER_VERTICAL);
            band.setPadding(UiKit.dp(ctx, 10), 0, UiKit.dp(ctx, 10), 0);
            band.setBackground(UiKit.roundRect(ctx, 0xFFECE9E1, 5));
            band.setMinimumHeight(UiKit.dp(ctx, 36));
            addView(band, new LinearLayout.LayoutParams(-1, -2));
            name = listText(ctx, 15, UiKit.PAPER_INK);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            count = listText(ctx, 13, UiKit.PAPER_SECOND);
            arrow = listText(ctx, 19, UiKit.PAPER_SECOND);
            arrow.setGravity(Gravity.CENTER);
            band.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            band.addView(count);
            band.addView(arrow, new LinearLayout.LayoutParams(UiKit.dp(ctx, 24), -2));
        }

        void bind(Day day, boolean expanded) {
            LocalDate date = day.date;
            String prefix = date.equals(today) ? "今天 · " : date.equals(today.minusDays(1)) ? "昨天 · " : "";
            name.setText(prefix + (date.getYear() == today.getYear() ? MONTH_DAY : FULL_DATE).format(date));
            count.setText(day.records.size() + " 条");
            arrow.setText("›");
            arrow.setRotation(expanded ? 90f : 0f);
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    private final class ArchiveAdapter extends BaseExpandableListAdapter {
        List<Day> groups = new ArrayList<>();
        @Override public int getGroupCount() { return groups.size(); }
        @Override public int getChildrenCount(int group) { return getGroup(group).records.size(); }
        @Override public Day getGroup(int group) { return groups.get(group); }
        @Override public Prefs.CaptureRecord getChild(int group, int child) {
            return getGroup(group).records.get(child);
        }
        @Override public long getGroupId(int group) { return getGroup(group).date.toEpochDay(); }
        @Override public long getChildId(int group, int child) {
            return getChild(group, child).fileName.hashCode() & 0xffffffffL;
        }
        @Override public boolean hasStableIds() { return true; }
        @Override public boolean isChildSelectable(int group, int child) { return true; }

        @Override public View getGroupView(int group, boolean expanded, View reuse, ViewGroup parent) {
            DayHeader header = reuse instanceof DayHeader ? (DayHeader) reuse : new DayHeader(getContext());
            header.bind(getGroup(group), expanded);
            return header;
        }

        @Override public View getChildView(int group, int child, boolean last, View reuse, ViewGroup parent) {
            SwipeDeleteRow row = reuse instanceof SwipeDeleteRow ? (SwipeDeleteRow) reuse : null;
            Context ctx = getContext();
            if (row == null) {
                RecordLine line = new RecordLine(ctx);
                row = new SwipeDeleteRow(ctx, line,
                        v -> openDetailSheet(ctx, line.record),
                        v -> deleteRecordFromRow(ctx, line.record, (SwipeDeleteRow) v.getParent(), (TextView) v));
            }
            RecordLine line = (RecordLine) row.fgView;
            Prefs.CaptureRecord record = getChild(group, child);
            if (line.record != record) {
                row.close(false);
                line.bind(record);
                row.setContentDescription(line.title.getText() + "，" + line.source.getText()
                        + "，" + line.clock.getText());
            } else {
                line.ensureThumbnail();
            }
            row.showDeleting(deletingFiles.contains(record.docUri + record.fileName));
            return row;
        }
    }

    private final class RecordLine extends LinearLayout {
        final FrameLayout thumb;
        final ImageView image;
        final TextView title, source, clock;
        Prefs.CaptureRecord record;
        Future<?> thumbnailJob;

        RecordLine(Context ctx) {
            super(ctx);
            setOrientation(VERTICAL);
            setBackgroundColor(UiKit.PAPER_BG);
            int pad = UiKit.dp(ctx, 20);
            setPadding(pad, 0, pad, 0);
            LinearLayout line = new LinearLayout(ctx);
            line.setMinimumHeight(UiKit.dp(ctx, 56));
            line.setGravity(Gravity.CENTER_VERTICAL);
            addView(line, new LinearLayout.LayoutParams(-1, -2));
            thumb = new FrameLayout(ctx);
            thumb.setBackground(UiKit.roundRect(ctx, UiKit.PAPER_MUTED_BG, 5));
            thumb.setClipToOutline(true);
            LinearLayout.LayoutParams square = new LinearLayout.LayoutParams(UiKit.dp(ctx, 38), UiKit.dp(ctx, 38));
            square.rightMargin = UiKit.dp(ctx, 11);
            line.addView(thumb, square);
            image = new ImageView(ctx);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.addView(image, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout copy = new LinearLayout(ctx);
            copy.setOrientation(VERTICAL);
            title = listText(ctx, 15, UiKit.PAPER_INK);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            source = listText(ctx, 12, UiKit.PAPER_SECOND);
            copy.addView(title);
            copy.addView(source);
            line.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            clock = listText(ctx, 12, UiKit.PAPER_SECOND);
            clock.setFontFeatureSettings("tnum");
            clock.setPadding(UiKit.dp(ctx, 7), 0, 0, 0);
            line.addView(clock);
            View rule = new View(ctx);
            rule.setBackgroundColor(0xFFDEDAD3);
            addView(rule, new LinearLayout.LayoutParams(-1, 1));
        }

        void bind(Prefs.CaptureRecord next) {
            cancelThumbnail();
            if (record == null || !record.platform.equals(next.platform)) {
                if (thumb.getChildCount() > 1) thumb.removeViewAt(0);
                thumb.addView(new UiKit.PlaceholderArtView(getContext(), next.platform),
                        0, new FrameLayout.LayoutParams(-1, -1));
            }
            record = next;
            ArchiveCopy copy = new ArchiveCopy(next.title, next.platform, NoteWriter.platformLabel(next.platform));
            title.setText(copy.title);
            source.setText(copy.source);
            clock.setText(CLOCK.format(Instant.ofEpochMilli(next.timeMs).atZone(ZoneId.systemDefault())));
            image.setImageDrawable(null);
            ensureThumbnail();
        }

        void cancelThumbnail() {
            if (thumbnailJob != null) thumbnailJob.cancel(true);
            thumbnailJob = null;
        }

        void ensureThumbnail() {
            String uri = record.shotUri;
            if (uri.isEmpty() || image.getDrawable() != null || thumbnailJob != null) return;
            Bitmap cached = thumbnails.get(uri);
            if (cached != null) {
                image.setImageBitmap(cached);
                return;
            }
            Context app = getContext().getApplicationContext();
            final Prefs.CaptureRecord requested = record;
            int target = UiKit.dp(app, 48);
            thumbnailJob = THUMB_EXEC.submit(() -> {
                Bitmap bitmap;
                try {
                    bitmap = app.getContentResolver().loadThumbnail(Uri.parse(uri),
                            new android.util.Size(target, target), null);
                } catch (Exception ignored) {
                    if (Thread.currentThread().isInterrupted()) return;
                    bitmap = decodeBoundedBitmap(app, Uri.parse(uri), target * 2);
                }
                if (bitmap == null || Thread.currentThread().isInterrupted()) return;
                thumbnails.put(uri, bitmap);
                main.post(() -> {
                    if (record == requested) image.setImageBitmap(thumbnails.get(uri));
                });
            });
        }
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
        fileFoot.setText((folder.isEmpty() ? NoteWriter.FOLDER_NAME : folder) + "/" + rec.fileName);
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
                            fileFoot.setText(rec.fileName + " · 当前不在收纳目录");
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

    private final java.util.Set<String> deletingFiles = new java.util.HashSet<>();

    private void deleteRecordFromRow(Context ctx, Prefs.CaptureRecord rec, SwipeDeleteRow row, TextView button) {
        if (!deletingFiles.add(rec.docUri + rec.fileName)) return;
        row.showDeleting(true);
        UiKit.hapticConfirm(ctx, button);
        Context app = ctx.getApplicationContext();
        DELETE_EXEC.execute(() -> {
            NoteWriter.DeleteResult result = NoteWriter.deleteCapture(app, rec);
            if (result.error == null) Prefs.removeCapture(app, rec);
            main.post(() -> {
                deletingFiles.remove(rec.docUri + rec.fileName);
                if (!isAttachedToWindow()) return;
                if (((RecordLine) row.fgView).record == rec) {
                    row.showDeleting(false);
                    row.close(false);
                }
                if (result.error != null) {
                    Toast.makeText(app, result.error, Toast.LENGTH_LONG).show();
                    adapter.notifyDataSetChanged();
                } else {
                    refresh();
                }
            });
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
            setOnClickListener(v -> onRowClick.onClick(fgView));
            this.touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
            int actionW = UiKit.dp(ctx, 76);
            int rightMargin = UiKit.dp(ctx, 20);
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

            LayoutParams delLp = new LayoutParams(actionW, UiKit.dp(ctx, 44));
            delLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
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

        void showDeleting(boolean active) {
            if (deleting == active) return;
            deleting = active;
            deleteBtn.setEnabled(!active);
            deleteBtn.setText(active ? "删除中" : "删除");
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


    public void openSettingsSheet() {
        Context ctx = getContext();
        Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView sheet = new ScrollView(ctx);
        sheet.setBackground(UiKit.topRoundSheet(ctx, 0xFFFAF9F6, 24));
        sheet.setVerticalScrollBarEnabled(false);
        LinearLayout rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(UiKit.dp(ctx, 20), UiKit.dp(ctx, 10),
                UiKit.dp(ctx, 20), UiKit.dp(ctx, 12));
        sheet.addView(rows);

        View handle = new View(ctx);
        handle.setBackground(UiKit.roundRect(ctx, 0xFFCFCCC6, 2));
        LinearLayout.LayoutParams handleSize = new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 32), UiKit.dp(ctx, 4));
        handleSize.gravity = Gravity.CENTER_HORIZONTAL;
        rows.addView(handle, handleSize);

        TextView heading = new TextView(ctx);
        heading.setText("收纳设置");
        heading.setTextSize(22);
        heading.setTypeface(Typeface.create("serif", Typeface.BOLD));
        heading.setTextColor(UiKit.PAPER_INK);
        heading.setPadding(0, UiKit.dp(ctx, 14), 0, UiKit.dp(ctx, 8));
        heading.setAccessibilityHeading(true);
        rows.addView(heading);

        boolean overlay = Settings.canDrawOverlays(ctx);
        boolean access = host.isAccessibilityEnabled();
        String folder = NoteWriter.folderName(ctx);
        boolean folderAvailable = NoteWriter.FOLDER_NAME.equals(folder);
        final int enabled = 0xFF187751;
        final int action = 0xFFAD432C;
        addSetting(rows, dialog, "overlay", "悬浮窗权限", overlay ? "已开启" : "去开启",
                overlay ? enabled : action, host::onOpenOverlaySettings, false);
        addSetting(rows, dialog, "accessibility", "无障碍监听", access ? "已开启" : "去开启",
                access ? enabled : action, host::onOpenAccessibilitySettings, true);
        boolean batteryExempt = SyncPowerSettings.isExempt(ctx);
        addSetting(rows, dialog, "battery", "后台运行", batteryExempt ? "已关闭电池优化" : "去设置",
                batteryExempt ? enabled : UiKit.PAPER_SECOND, host::onOpenBatterySettings, true);
        addSetting(rows, dialog, "folder", "收纳目录", folderAvailable ? folder : "选择目录",
                folderAvailable ? UiKit.PAPER_SECOND : action, host::onPickFolder, true);
        addSetting(rows, dialog, "computer", "连接电脑", SyncPairing.summary(ctx),
                UiKit.PAPER_SECOND, host::onConnectComputer, true);
        String installedVersion;
        try {
            installedVersion = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            installedVersion = "";
        }
        addSetting(rows, dialog, "update", "检查更新", installedVersion,
                UiKit.PAPER_SECOND, host::onCheckUpdate, true);
        addSetting(rows, dialog, "preview", "弹窗预览", "",
                UiKit.PAPER_SECOND, host::onTestPopup, true);
        if (host.isOldInstalled()) {
            addSetting(rows, dialog, "remove", "清理旧版", "去卸载",
                    UiKit.DANGER_RED, host::onCleanupOldVersion, true);
        }

        dialog.setContentView(sheet);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.38f);
        }
        dialog.show();
    }

    private void addSetting(LinearLayout rows, Dialog dialog, String icon, String title, String value,
                            int valueColor, Runnable action, boolean dividerBefore) {
        Context ctx = rows.getContext();
        if (dividerBefore) {
            View rule = new View(ctx);
            rule.setBackgroundColor(0xFFDEDBD6);
            rows.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }

        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(ctx, 48));
        row.setPadding(0, UiKit.dp(ctx, 6), 0, UiKit.dp(ctx, 6));
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x14000000), null,
                UiKit.roundRect(ctx, Color.WHITE, 5)));
        row.setFocusable(true);
        row.setContentDescription(TextUtils.isEmpty(value) ? title : title + "，" + value);
        row.setOnClickListener(v -> {
            UiKit.hapticTick(v);
            dialog.dismiss();
            action.run();
        });
        ImageView glyph = new ImageView(ctx);
        glyph.setImageResource(ctx.getResources().getIdentifier(
                "setting_" + icon, "drawable", ctx.getPackageName()));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = UiKit.dp(ctx, 19);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(size, size);
        iconSize.rightMargin = UiKit.dp(ctx, 13);
        row.addView(glyph, iconSize);

        TextView label = new TextView(ctx);
        label.setText(title);
        label.setTextColor(UiKit.PAPER_INK);
        label.setTextSize(16);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!TextUtils.isEmpty(value)) {
            TextView state = new TextView(ctx);
            state.setText(value);
            state.setTextColor(valueColor);
            state.setTextSize(14);
            state.setGravity(Gravity.END);
            state.setMaxWidth(ctx.getResources().getDisplayMetrics().widthPixels / 3);
            state.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(state);
        }
        ImageView arrow = new ImageView(ctx);
        arrow.setImageResource(ctx.getResources().getIdentifier(
                "setting_chevron", "drawable", ctx.getPackageName()));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int arrowSize = UiKit.dp(ctx, 15);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(arrowSize, arrowSize);
        arrowParams.leftMargin = UiKit.dp(ctx, 6);
        row.addView(arrow, arrowParams);
        rows.addView(row);
    }

    public static int platformDotColor(String platform) {
        if ("xiaohongshu".equals(platform)) return UiKit.DOT_XHS;
        if ("douyin".equals(platform)) return UiKit.DOT_DY;
        if ("wechat".equals(platform)) return UiKit.DOT_WX;
        if ("bilibili".equals(platform)) return UiKit.DOT_BILI;
        return UiKit.CORAL_END;
    }

}
