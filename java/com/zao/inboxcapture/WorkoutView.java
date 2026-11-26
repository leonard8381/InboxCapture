package com.zao.inboxcapture;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 双场景「训练」控制器（支持递增组编辑 + 周日默认练腿方案 + 热身勾选）：
 * 场景 1 · 记录与修正（HubView）：
 *   默认沿用上次每一组的完整递增重量（无历史时默认载入周日练腿方案）；点动作可逐组增删改或一键同步全部组。
 * 场景 2 · 边练边记（StageView）：
 *   暗室专注模式，含总计时器 + 焦段选重 + 完成按钮原位液体休息倒计时；右上角唯一「结束」入口。
 */
public final class WorkoutView {
    public interface Host {
        void onNeedPickFolder();
        void onActiveStageChanged(boolean inStage);
        void onBodyWeightUpdated();
    }

    private static final ExecutorService IO_EXEC = Executors.newSingleThreadExecutor();
    private static final String[] WARMUP_OPTIONS = {"泡沫轴滚背", "最伟大拉伸", "青蛙趴"};
    private static final Map<String, String[]> TEMPLATES = new LinkedHashMap<>();
    private static final Map<String, String[]> CATEGORIES = new LinkedHashMap<>();

    static {
        TEMPLATES.put("下肢", new String[]{"深蹲", "硬拉", "坐姿腿屈伸", "内收肌夹腿"});
        TEMPLATES.put("上肢", new String[]{"卧推", "坐姿划船", "高位下拉", "哑铃肩推"});
        TEMPLATES.put("全身", new String[]{"腿举", "卧推", "坐姿划船", "哑铃肩推"});

        CATEGORIES.put("腿部", new String[]{"深蹲", "硬拉", "坐姿腿屈伸", "内收肌夹腿", "腿举", "腿弯举", "提踵"});
        CATEGORIES.put("胸部", new String[]{"卧推", "上斜哑铃卧推", "器械推胸", "蝴蝶机夹胸", "俯卧撑"});
        CATEGORIES.put("背部", new String[]{"高位下拉", "坐姿划船", "哑铃划船", "引体向上", "直臂下压"});
        CATEGORIES.put("肩臂", new String[]{"哑铃肩推", "侧平举", "面拉", "哑铃弯举", "绳索下压"});
    }

    /** 首页快速记录卡片里的单个动作（支持递增组，每组独立重量与次数）。 */
    private static final class QuickItem {
        String name;
        final List<Prefs.SetItem> sets = new ArrayList<>();
        boolean modified;

        QuickItem(String name) {
            this.name = name;
        }
    }

    private final Context ctx;
    private final Host host;

    private final ScrollView hubScroll;
    private final LinearLayout hubContent;
    private final FrameLayout stageRoot;
    private final UiKit.DarkroomSpotlightView spotlightBg;
    private final LinearLayout stageShell;

    // Scenario 1: Hub Quick-Log State
    private String quickModeKey = "";
    private String selectedDateStr = ""; // 空字符串或等于今天 = 今日模式；否则为过去某天 yyyy-MM-dd（补录模式）
    private final List<QuickItem> quickItems = new ArrayList<>();
    private final Set<String> activeWarmups = new LinkedHashSet<>(Arrays.asList(WARMUP_OPTIONS));

    // Scenario 2: Darkroom Active Workout State
    private TextView clockView;
    private TextView stageTitleView;
    private LinearLayout exerciseBar;
    private LinearLayout trailRow;
    private TextView bigWeightText;
    private TextView bigRepsText;
    private TextView stageSubHint;
    private LinearLayout weightStrip;
    private LinearLayout repsStrip;
    private FrameLayout morphDock;
    private TextView readyPillBtn;
    private UiKit.LiquidRestBarView restBarView;

    private boolean inStage = false;
    private String sessionTitle = "今日训练";
    private String sessionWarmup = "";
    private long sessionStartMs = 0L;
    private final List<Prefs.ExerciseBlock> activeExercises = new ArrayList<>();
    private final Map<String, Double> weightAnchors = new HashMap<>();
    private final Map<String, Integer> repsAnchors = new HashMap<>();
    private int activeIndex = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long restEndWallMs = 0L;
    private int restTotalSec = 90;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            if (inStage && clockView != null && sessionStartMs > 0) {
                int sec = (int) Math.max(0, (System.currentTimeMillis() - sessionStartMs) / 1000L);
                clockView.setText(formatClock(sec));
                handler.postDelayed(this, 1000);
            }
        }
    };

    private final Runnable restTick = new Runnable() {
        @Override
        public void run() {
            if (restEndWallMs <= 0L) return;
            int remain = (int) Math.ceil((restEndWallMs - System.currentTimeMillis()) / 1000.0);
            if (remain <= 0) {
                restEndWallMs = 0L;
                saveDraft();
                UiKit.vibrateRestDone(ctx);
                Toast.makeText(ctx, "休息结束，准备下一组", Toast.LENGTH_SHORT).show();
                renderStageBody();
            } else {
                updateRestVisual(remain);
                handler.postDelayed(this, 350);
            }
        }
    };

    public WorkoutView(Context context, Host host) {
        this.ctx = context;
        this.host = host;

        hubScroll = new ScrollView(ctx);
        hubScroll.setFillViewport(true);
        hubScroll.setVerticalScrollBarEnabled(false);
        hubContent = new LinearLayout(ctx);
        hubContent.setOrientation(LinearLayout.VERTICAL);
        hubContent.setPadding(0, UiKit.dp(ctx, 14), 0, UiKit.dp(ctx, 40));
        hubScroll.addView(hubContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        stageRoot = new FrameLayout(ctx);
        stageRoot.setVisibility(View.GONE);
        spotlightBg = new UiKit.DarkroomSpotlightView(ctx);
        stageRoot.addView(spotlightBg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        stageShell = new LinearLayout(ctx);
        stageShell.setOrientation(LinearLayout.VERTICAL);
        stageShell.setPadding(0, UiKit.dp(ctx, 14), 0, UiKit.dp(ctx, 30));
        stageRoot.addView(stageShell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        restoreDraftIfPresent();
        loadQuickPreset(defaultPresetKey());
        renderHub();
    }

    public View getHubView() {
        return hubScroll;
    }

    public View getStageView() {
        return stageRoot;
    }

    public boolean isInStage() {
        return inStage;
    }

    public void exitStageToHub() {
        if (!inStage) return;
        saveDraft();
        inStage = false;
        handler.removeCallbacks(clockTick);
        stageRoot.setVisibility(View.GONE);
        renderHub();
        host.onActiveStageChanged(false);
    }

    public void refresh() {
        if (inStage) {
            renderStageBody();
            syncRestFromWallClock();
        } else {
            renderHub();
        }
    }

    public void release() {
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(restTick);
    }

    // =========================================================================
    // 1. SCENARIO A: QUICK LOG & PYRAMID SET EDITING
    // =========================================================================
    private String defaultPresetKey() {
        List<Prefs.WorkoutRecord> history = Prefs.workoutHistory(ctx);
        if (!history.isEmpty() && !history.get(0).exercises.isEmpty()) {
            return "沿用上次";
        }
        return "下肢";
    }

    private void loadQuickPreset(String key) {
        quickModeKey = key;
        quickItems.clear();
        activeWarmups.clear();

        List<Prefs.WorkoutRecord> history = Prefs.workoutHistory(ctx);
        if ("沿用上次".equals(key) && !history.isEmpty()) {
            Prefs.WorkoutRecord last = history.get(0);
            if (last.warmup != null && !last.warmup.trim().isEmpty()) {
                for (String w : last.warmup.split("、")) {
                    if (!w.trim().isEmpty()) activeWarmups.add(w.trim());
                }
            } else {
                activeWarmups.addAll(Arrays.asList(WARMUP_OPTIONS));
            }
            for (Prefs.ExerciseBlock b : last.exercises) {
                QuickItem qi = new QuickItem(b.name);
                if (b.sets.isEmpty()) {
                    qi.sets.addAll(defaultSetsFor(b.name));
                } else {
                    for (Prefs.SetItem s : b.sets) {
                        qi.sets.add(new Prefs.SetItem(s.weightKg, s.reps, true));
                    }
                }
                quickItems.add(qi);
            }
            return;
        }
        if ("空白".equals(key)) {
            return;
        }
        if ("下肢".equals(key)) {
            activeWarmups.addAll(Arrays.asList(WARMUP_OPTIONS));
        }
        String[] names = TEMPLATES.get(key);
        if (names != null) {
            for (String n : names) {
                QuickItem qi = new QuickItem(n);
                List<Prefs.SetItem> prev = Prefs.lastExerciseSets(ctx, n);
                if (prev != null && !prev.isEmpty()) {
                    for (Prefs.SetItem s : prev) {
                        qi.sets.add(new Prefs.SetItem(s.weightKg, s.reps, true));
                    }
                } else {
                    qi.sets.addAll(defaultSetsFor(n));
                }
                quickItems.add(qi);
            }
        }
    }

    private void renderHub() {
        hubContent.removeAllViews();
        List<Prefs.WorkoutRecord> history = Prefs.workoutHistory(ctx);
        Prefs.WorkoutRecord latest = history.isEmpty() ? null : history.get(0);

        if (!activeExercises.isEmpty() && sessionStartMs > 0) {
            LinearLayout draftBar = new LinearLayout(ctx);
            draftBar.setOrientation(LinearLayout.HORIZONTAL);
            draftBar.setGravity(Gravity.CENTER_VERTICAL);
            draftBar.setPadding(UiKit.dp(ctx, 16), UiKit.dp(ctx, 12),
                    UiKit.dp(ctx, 16), UiKit.dp(ctx, 12));
            draftBar.setBackground(UiKit.roundRect(ctx, 0xFFE9F5EE, 999));
            UiKit.attachSpringPress(draftBar);
            draftBar.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.hapticTick(v);
                    enterStage();
                }
            });

            TextView dt = new TextView(ctx);
            dt.setText("边练边记中 · " + sessionTitle + "（已记 " + countDoneSets() + " 组）");
            dt.setTextSize(13);
            dt.setTypeface(Typeface.DEFAULT_BOLD);
            dt.setTextColor(UiKit.OK_GREEN);
            draftBar.addView(dt, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView dGo = new TextView(ctx);
            dGo.setText("回到训练 ›");
            dGo.setTextSize(12);
            dGo.setTypeface(Typeface.DEFAULT_BOLD);
            dGo.setTextColor(UiKit.OK_GREEN);
            draftBar.addView(dGo);

            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.leftMargin = UiKit.dp(ctx, 20);
            dLp.rightMargin = UiKit.dp(ctx, 20);
            dLp.bottomMargin = UiKit.dp(ctx, 14);
            hubContent.addView(draftBar, dLp);
        }

        final String targetDate = activeTargetDate();
        final boolean isBackfill = !targetDate.equals(todayDateStr());
        Prefs.WorkoutRecord selectedDayRec = findRecordByDate(history, targetDate);

        LinearLayout hero = new LinearLayout(ctx);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(UiKit.dp(ctx, 26), UiKit.dp(ctx, 6), UiKit.dp(ctx, 26), 0);

        TextView eyebrow = new TextView(ctx);
        eyebrow.setText(isBackfill
                ? ("补录模式 · " + formatMonthDayWeekday(targetDate))
                : buildEyebrowCopy(latest));
        eyebrow.setTextSize(12);
        eyebrow.setTypeface(Typeface.DEFAULT_BOLD);
        eyebrow.setTextColor(isBackfill ? UiKit.CORAL_ACCENT : UiKit.PAPER_MUTED);
        hero.addView(eyebrow);

        TextView h1 = new TextView(ctx);
        h1.setText(isBackfill
                ? (selectedDayRec != null
                        ? (weekdayShortLabel(targetDate) + "已有记录，\n可查看或继续补加。")
                        : ("补录" + weekdayShortLabel(targetDate) + "训练，\n微调好直接点保存。"))
                : buildEditorialHeadline(latest));
        h1.setTextSize(28);
        h1.setTypeface(Typeface.DEFAULT_BOLD);
        h1.setTextColor(UiKit.PAPER_INK);
        h1.setLineSpacing(UiKit.dp(ctx, 5), 1f);
        h1.setPadding(0, UiKit.dp(ctx, 6), 0, 0);
        hero.addView(h1);
        hubContent.addView(hero);

        hubContent.addView(buildWeekWhisper(ctx, targetDate));
        hubContent.addView(buildQuickLogSheet(ctx, latest, targetDate, selectedDayRec));

        if (!history.isEmpty()) {
            LinearLayout foot = new LinearLayout(ctx);
            foot.setOrientation(LinearLayout.VERTICAL);
            foot.setPadding(UiKit.dp(ctx, 26), UiKit.dp(ctx, 22), UiKit.dp(ctx, 26), 0);

            TextView lbl = new TextView(ctx);
            lbl.setText("最近记录");
            lbl.setTextSize(11);
            lbl.setTypeface(Typeface.DEFAULT_BOLD);
            lbl.setTextColor(UiKit.PAPER_MUTED);
            lbl.setLetterSpacing(0.06f);
            lbl.setPadding(0, 0, 0, UiKit.dp(ctx, 6));
            foot.addView(lbl);

            int showMax = Math.min(3, history.size());
            for (int i = 0; i < showMax; i++) {
                final Prefs.WorkoutRecord rec = history.get(i);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, UiKit.dp(ctx, 8), 0, UiKit.dp(ctx, 8));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        UiKit.hapticTick(v);
                        showHistoryDetailDialog(rec);
                    }
                });

                TextView dateTv = new TextView(ctx);
                dateTv.setText(shortDateLabel(rec.date));
                dateTv.setTextSize(13);
                dateTv.setTextColor(UiKit.PAPER_MUTED);
                dateTv.setFontFeatureSettings("tnum");
                row.addView(dateTv, new LinearLayout.LayoutParams(
                        UiKit.dp(ctx, 68), ViewGroup.LayoutParams.WRAP_CONTENT));

                String durSuffix = rec.durationSec > 0
                        ? (" · " + Math.max(1, Math.round(rec.durationSec / 60f)) + " 分钟")
                        : "";
                TextView mainTv = new TextView(ctx);
                mainTv.setText(rec.title + "   " + rec.totalSets + " 组" + durSuffix);
                mainTv.setTextSize(13);
                mainTv.setTypeface(Typeface.DEFAULT_BOLD);
                mainTv.setTextColor(0xFF3A3936);
                row.addView(mainTv, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                if (rec.prSummary != null && !rec.prSummary.isEmpty()) {
                    TextView prTv = new TextView(ctx);
                    prTv.setText(rec.prSummary);
                    prTv.setTextSize(12);
                    prTv.setTypeface(Typeface.DEFAULT_BOLD);
                    prTv.setTextColor(UiKit.CORAL_ACCENT);
                    prTv.setFontFeatureSettings("tnum");
                    row.addView(prTv);
                }
                foot.addView(row);
            }
            hubContent.addView(foot);
        }
    }

    /**
     * 滚动 7 天时间条（今天-5 .. 今天 .. 明天）：
     * - 例如今天是周四，依次展示：六 · 日 · 一 · 二 · 三 · 今 · 五
     * - 点击前 5 天任意一天切换至「补录模式」，直接补录到对应日期的 Obsidian 文档；点「今」切回今天
     */
    private View buildWeekWhisper(final Context ctx, final String activeDate) {
        Set<String> trainedDates = Prefs.trainedDateSet(ctx);
        Set<Integer> doneThisWeek = Prefs.trainedWeekdaysThisWeek(ctx);
        int goal = Prefs.weeklyGoal(ctx);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(UiKit.dp(ctx, 20), UiKit.dp(ctx, 16), UiKit.dp(ctx, 24), 0);

        LinearLayout days = new LinearLayout(ctx);
        days.setOrientation(LinearLayout.HORIZONTAL);
        days.setGravity(Gravity.CENTER_VERTICAL);
        days.setClipChildren(false);
        days.setClipToPadding(false);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String[] weekChars = {"", "日", "一", "二", "三", "四", "五", "六"};
        Calendar base = Calendar.getInstance();

        for (int offset = -5; offset <= 1; offset++) {
            Calendar c = (Calendar) base.clone();
            c.add(Calendar.DAY_OF_YEAR, offset);
            final String dateStr = sdf.format(c.getTime());
            final boolean isToday = (offset == 0);
            final boolean isFuture = (offset > 0);
            boolean isSelected = dateStr.equals(activeDate);
            boolean trained = trainedDates.contains(dateStr);

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setClipChildren(false);
            col.setClipToPadding(false);
            col.setPadding(UiKit.dp(ctx, 2), UiKit.dp(ctx, 5), UiKit.dp(ctx, 2), UiKit.dp(ctx, 6));
            if (isSelected) {
                col.setBackground(UiKit.roundRect(ctx, 0xFFEBE7DD, 12));
            }

            TextView t = new TextView(ctx);
            t.setText(isToday ? "今" : weekChars[c.get(Calendar.DAY_OF_WEEK)]);
            t.setTextSize(12);
            t.setSingleLine(true);
            t.setMaxLines(1);
            t.setIncludeFontPadding(true);
            t.setGravity(Gravity.CENTER);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            if (isFuture) {
                t.setTextColor(0xFFCECAC0);
            } else if (isSelected || isToday) {
                t.setTextColor(isSelected ? UiKit.PAPER_INK : UiKit.CORAL_ACCENT);
            } else {
                t.setTextColor(0xFF8F8B82);
            }
            t.setPadding(UiKit.dp(ctx, 3), UiKit.dp(ctx, 1), UiKit.dp(ctx, 3), UiKit.dp(ctx, 1));
            col.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            View dot = new View(ctx);
            int sizeDp = (trained || isToday) ? 10 : (isFuture ? 7 : 8);
            if (trained) {
                dot.setBackground(UiKit.coralPillGradient(ctx, 999));
                UiKit.applySoftShadow(dot, 3, 0x55EE5438);
            } else if (isToday) {
                dot.setBackground(UiKit.roundRectBordered(ctx, Color.TRANSPARENT, 999, UiKit.CORAL_ACCENT, 2f));
            } else if (isFuture) {
                dot.setBackground(UiKit.roundRect(ctx, 0xFFE8E5DE, 999));
            } else {
                dot.setBackground(UiKit.roundRect(ctx, 0xFFD9D5CC, 999));
            }
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                    UiKit.dp(ctx, sizeDp), UiKit.dp(ctx, sizeDp));
            dotLp.topMargin = UiKit.dp(ctx, (trained || isToday) ? 5 : 6);
            col.addView(dot, dotLp);

            col.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.hapticTick(v);
                    if (isFuture) {
                        Toast.makeText(ctx, "明天还没到，点前面 6 天可补录训练", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    selectedDateStr = isToday ? "" : dateStr;
                    renderHub();
                }
            });

            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            days.addView(col, colLp);
        }
        LinearLayout.LayoutParams daysLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        daysLp.rightMargin = UiKit.dp(ctx, 8);
        row.addView(days, daysLp);

        TextView goalTv = new TextView(ctx);
        goalTv.setText("本周 " + doneThisWeek.size() + " / " + goal);
        goalTv.setTextSize(12);
        goalTv.setSingleLine(true);
        goalTv.setIncludeFontPadding(true);
        goalTv.setTypeface(Typeface.DEFAULT_BOLD);
        goalTv.setTextColor(UiKit.PAPER_SECOND);
        goalTv.setFontFeatureSettings("tnum");
        goalTv.setPadding(UiKit.dp(ctx, 4), UiKit.dp(ctx, 4), UiKit.dp(ctx, 2), UiKit.dp(ctx, 4));
        goalTv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                int nextGoal = (Prefs.weeklyGoal(ctx) % 5) + 1;
                Prefs.setWeeklyGoal(ctx, nextGoal);
                Toast.makeText(ctx, "本周目标已设为 " + nextGoal + " 次", Toast.LENGTH_SHORT).show();
                renderHub();
            }
        });
        row.addView(goalTv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View buildQuickLogSheet(final Context ctx, final Prefs.WorkoutRecord latest,
                                    final String targetDate, final Prefs.WorkoutRecord selectedDayRec) {
        final boolean isBackfill = !targetDate.equals(todayDateStr());
        final List<String> tabKeys = new ArrayList<>();
        if (latest != null && !latest.exercises.isEmpty()) {
            tabKeys.add("沿用上次");
        }
        tabKeys.add("下肢");
        tabKeys.add("上肢");
        tabKeys.add("全身");
        tabKeys.add("空白");

        if (quickModeKey.isEmpty() || !tabKeys.contains(quickModeKey)) {
            loadQuickPreset(tabKeys.get(0));
        }

        LinearLayout sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(UiKit.dp(ctx, 20), UiKit.dp(ctx, 18),
                UiKit.dp(ctx, 20), UiKit.dp(ctx, 16));
        sheet.setBackground(UiKit.roundRect(ctx, UiKit.PAPER_SHEET, 32));
        UiKit.applySoftShadow(sheet, 14, 0x181C1914);

        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.leftMargin = UiKit.dp(ctx, 18);
        sLp.rightMargin = UiKit.dp(ctx, 18);
        sLp.topMargin = UiKit.dp(ctx, 16);
        sheet.setLayoutParams(sLp);

        // 补录当天已有记录时，卡片顶部轻提示并支持一键查看详情
        if (isBackfill && selectedDayRec != null) {
            LinearLayout existedBar = new LinearLayout(ctx);
            existedBar.setOrientation(LinearLayout.HORIZONTAL);
            existedBar.setGravity(Gravity.CENTER_VERTICAL);
            existedBar.setBackground(UiKit.roundRect(ctx, 0xFFEAF6EF, 14));
            existedBar.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 8),
                    UiKit.dp(ctx, 12), UiKit.dp(ctx, 8));
            existedBar.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.hapticTick(v);
                    showHistoryDetailDialog(selectedDayRec);
                }
            });

            TextView exTxt = new TextView(ctx);
            exTxt.setText("✓ " + weekdayShortLabel(targetDate) + "已记 · "
                    + selectedDayRec.title + " " + selectedDayRec.totalSets + " 组");
            exTxt.setTextSize(12);
            exTxt.setTypeface(Typeface.DEFAULT_BOLD);
            exTxt.setTextColor(UiKit.OK_GREEN);
            existedBar.addView(exTxt, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView exView = new TextView(ctx);
            exView.setText("查看 ›");
            exView.setTextSize(12);
            exView.setTypeface(Typeface.DEFAULT_BOLD);
            exView.setTextColor(UiKit.OK_GREEN);
            existedBar.addView(exView);

            LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ebLp.bottomMargin = UiKit.dp(ctx, 12);
            sheet.addView(existedBar, ebLp);
        }

        // Mode Tabs
        HorizontalScrollView tabScroll = new HorizontalScrollView(ctx);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(ctx);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        for (final String key : tabKeys) {
            boolean on = key.equals(quickModeKey);
            TextView tv = new TextView(ctx);
            tv.setText("沿用上次".equals(key) && latest != null ? ("上次 · " + latest.title) : key);
            tv.setTextSize(13);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setSingleLine(true);
            tv.setTextColor(on ? UiKit.PAPER_INK : UiKit.PAPER_MUTED);
            tv.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 6),
                    UiKit.dp(ctx, 12), UiKit.dp(ctx, 6));
            if (on) tv.setBackground(UiKit.roundRect(ctx, UiKit.PAPER_MUTED_BG, 999));
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.hapticTick(v);
                    loadQuickPreset(key);
                    renderHub();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = UiKit.dp(ctx, 6);
            tabRow.addView(tv, lp);
        }
        tabScroll.addView(tabRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(tabScroll);

        // Warmup Quick Toggle Row (泡沫轴滚背 · 最伟大拉伸 · 青蛙趴)
        HorizontalScrollView warmScroll = new HorizontalScrollView(ctx);
        warmScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout warmRow = new LinearLayout(ctx);
        warmRow.setOrientation(LinearLayout.HORIZONTAL);
        warmRow.setGravity(Gravity.CENTER_VERTICAL);
        warmRow.setPadding(UiKit.dp(ctx, 4), UiKit.dp(ctx, 12), UiKit.dp(ctx, 4), UiKit.dp(ctx, 4));

        TextView warmLbl = new TextView(ctx);
        warmLbl.setText("热身");
        warmLbl.setTextSize(12);
        warmLbl.setSingleLine(true);
        warmLbl.setIncludeFontPadding(true);
        warmLbl.setTypeface(Typeface.DEFAULT_BOLD);
        warmLbl.setTextColor(UiKit.PAPER_MUTED);
        warmLbl.setPadding(UiKit.dp(ctx, 2), UiKit.dp(ctx, 2), UiKit.dp(ctx, 2), UiKit.dp(ctx, 2));
        LinearLayout.LayoutParams wlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlLp.rightMargin = UiKit.dp(ctx, 10);
        warmRow.addView(warmLbl, wlLp);

        for (final String wName : WARMUP_OPTIONS) {
            boolean checked = activeWarmups.contains(wName);
            TextView wChip = new TextView(ctx);
            wChip.setText((checked ? "✓ " : "") + wName);
            wChip.setSingleLine(true);
            wChip.setTextSize(12);
            wChip.setTypeface(Typeface.DEFAULT_BOLD);
            wChip.setTextColor(checked ? UiKit.OK_GREEN : UiKit.PAPER_IDLE);
            wChip.setPadding(UiKit.dp(ctx, 10), UiKit.dp(ctx, 4),
                    UiKit.dp(ctx, 10), UiKit.dp(ctx, 4));
            if (checked) wChip.setBackground(UiKit.roundRect(ctx, 0xFFEAF6EF, 999));
            wChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.hapticTick(v);
                    if (activeWarmups.contains(wName)) activeWarmups.remove(wName);
                    else activeWarmups.add(wName);
                    renderHub();
                }
            });
            LinearLayout.LayoutParams wcLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wcLp.rightMargin = UiKit.dp(ctx, 6);
            warmRow.addView(wChip, wcLp);
        }
        warmScroll.addView(warmRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(warmScroll);

        // Exercises List (shows pyramid sets cleanly, tap row to edit per-set!)
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(UiKit.dp(ctx, 4), UiKit.dp(ctx, 4), UiKit.dp(ctx, 4), 0);

        for (int i = 0; i < quickItems.size(); i++) {
            if (i > 0) {
                View div = new View(ctx);
                div.setBackgroundColor(UiKit.PAPER_DIVIDER);
                list.addView(div, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 1)));
            }
            final int idx = i;
            final QuickItem item = quickItems.get(i);
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.VERTICAL);
            line.setPadding(0, UiKit.dp(ctx, 10), 0, UiKit.dp(ctx, 10));
            line.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.hapticTick(v);
                    showQuickEditItemDialog(idx);
                }
            });

            LinearLayout topRow = new LinearLayout(ctx);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView nTv = new TextView(ctx);
            nTv.setText(item.name);
            nTv.setTextSize(15);
            nTv.setTypeface(Typeface.DEFAULT_BOLD);
            nTv.setTextColor(0xFF1E1E1C);
            topRow.addView(nTv, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView cTv = new TextView(ctx);
            cTv.setText(item.sets.size() + " 组 ›");
            cTv.setTextSize(12);
            cTv.setTypeface(Typeface.DEFAULT_BOLD);
            cTv.setTextColor(item.modified ? UiKit.CORAL_ACCENT : UiKit.PAPER_MUTED);
            cTv.setFontFeatureSettings("tnum");
            topRow.addView(cTv);
            line.addView(topRow);

            TextView detailTv = new TextView(ctx);
            detailTv.setText(formatQuickVisualSets(item));
            detailTv.setTextSize(12.5f);
            detailTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            detailTv.setTextColor(item.modified ? UiKit.CORAL_ACCENT : UiKit.PAPER_SECOND);
            detailTv.setFontFeatureSettings("tnum");
            detailTv.setLineSpacing(UiKit.dp(ctx, 3), 1f);
            detailTv.setPadding(0, UiKit.dp(ctx, 4), 0, 0);
            line.addView(detailTv);

            list.addView(line);
        }

        TextView addRowBtn = new TextView(ctx);
        addRowBtn.setText("＋ 添加动作");
        addRowBtn.setTextSize(13);
        addRowBtn.setTypeface(Typeface.DEFAULT_BOLD);
        addRowBtn.setTextColor(UiKit.PAPER_MUTED);
        addRowBtn.setPadding(0, UiKit.dp(ctx, 10), 0, UiKit.dp(ctx, 6));
        addRowBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                showQuickAddExerciseDialog();
            }
        });
        list.addView(addRowBtn);
        sheet.addView(list);

        String saveLabel;
        if (quickItems.isEmpty()) {
            saveLabel = "添加动作";
        } else if (isBackfill) {
            saveLabel = "补录 " + shortDateLabel(targetDate) + " 训练";
        } else {
            saveLabel = "保存训练";
        }
        final TextView saveBtn = UiKit.makePrimaryPill(ctx, saveLabel);
        saveBtn.setEnabled(!quickItems.isEmpty());
        saveBtn.setAlpha(quickItems.isEmpty() ? 0.5f : 1f);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveQuickItemsToObsidian(saveBtn, latest, targetDate, isBackfill);
            }
        });
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 54));
        cLp.topMargin = UiKit.dp(ctx, 12);
        sheet.addView(saveBtn, cLp);

        LinearLayout subRow = new LinearLayout(ctx);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER);
        subRow.setPadding(0, UiKit.dp(ctx, 12), 0, UiKit.dp(ctx, 2));

        TextView subAction = new TextView(ctx);
        subAction.setText(isBackfill ? "回到今天 ›" : "边练边记 ›");
        subAction.setTextSize(12);
        subAction.setTypeface(Typeface.DEFAULT_BOLD);
        subAction.setTextColor(isBackfill ? UiKit.CORAL_ACCENT : UiKit.PAPER_SECOND);
        subAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                if (isBackfill) {
                    selectedDateStr = "";
                    renderHub();
                    return;
                }
                String title = "沿用上次".equals(quickModeKey) && latest != null
                        ? latest.title
                        : ("空白".equals(quickModeKey) ? "今日训练" : quickModeKey);
                startStageFromQuickItems(title);
            }
        });
        subRow.addView(subAction);
        sheet.addView(subRow);

        return sheet;
    }

    private String formatQuickVisualSets(QuickItem item) {
        if (item.sets.isEmpty()) return "未添加组";
        double firstW = item.sets.get(0).weightKg;
        boolean sameW = true;
        for (Prefs.SetItem s : item.sets) {
            if (Math.abs(s.weightKg - firstW) > 0.05) {
                sameW = false;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (sameW && item.sets.size() > 1) {
            String wStr = firstW <= 0.01 ? "自重" : FitnessStore.formatKg(firstW) + "kg";
            sb.append(wStr).append(" × ");
            for (int i = 0; i < item.sets.size(); i++) {
                if (i > 0) sb.append(" / ");
                sb.append(item.sets.get(i).reps);
            }
            return sb.toString();
        }
        for (int i = 0; i < item.sets.size(); i++) {
            Prefs.SetItem s = item.sets.get(i);
            if (i > 0) sb.append("  ·  ");
            String wStr = s.weightKg <= 0.01 ? "自重" : FitnessStore.formatKg(s.weightKg) + "kg";
            sb.append(wStr).append("×").append(s.reps);
        }
        return sb.toString();
    }

    private String currentWarmupSummary() {
        if (activeWarmups.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String w : WARMUP_OPTIONS) {
            if (activeWarmups.contains(w)) {
                if (sb.length() > 0) sb.append("、");
                sb.append(w);
            }
        }
        return sb.toString();
    }

    private void saveQuickItemsToObsidian(final TextView saveBtn, Prefs.WorkoutRecord latest,
                                          final String targetDate, final boolean isBackfill) {
        if (quickItems.isEmpty()) return;
        UiKit.hapticConfirm(ctx, saveBtn);

        final Prefs.WorkoutRecord rec = new Prefs.WorkoutRecord();
        rec.date = targetDate;
        rec.timestampMs = isBackfill ? timestampForDate(targetDate) : System.currentTimeMillis();
        rec.title = ("沿用上次".equals(quickModeKey) && latest != null)
                ? latest.title
                : ("空白".equals(quickModeKey) ? (isBackfill ? "补录训练" : "今日训练") : quickModeKey);
        rec.warmup = currentWarmupSummary();
        rec.durationSec = 0;

        StringBuilder prSb = new StringBuilder();
        for (QuickItem qi : quickItems) {
            Prefs.ExerciseBlock block = new Prefs.ExerciseBlock(qi.name);
            double prevBest = Prefs.bestWeightFor(ctx, qi.name);
            double maxThis = 0;
            for (Prefs.SetItem s : qi.sets) {
                block.sets.add(new Prefs.SetItem(s.weightKg, s.reps, true));
                rec.totalSets++;
                rec.totalVolumeKg += s.weightKg * s.reps;
                if (s.weightKg > maxThis) maxThis = s.weightKg;
            }
            rec.exercises.add(block);
            if (prevBest > 0 && maxThis > prevBest + 0.01) {
                if (prSb.length() > 0) prSb.append("、");
                prSb.append(qi.name).append(" +").append(FitnessStore.formatKg(maxThis - prevBest)).append("kg");
            }
        }
        rec.prSummary = prSb.toString();

        saveBtn.setEnabled(false);
        saveBtn.setText("写入 Obsidian 中…");
        IO_EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final FitnessStore.Saved saved = FitnessStore.saveSession(
                            ctx, rec.date, rec, "", isBackfill);
                    Prefs.saveWorkoutRecord(ctx, rec);
                    hubScroll.post(new Runnable() {
                        @Override
                        public void run() {
                            String verb = isBackfill ? "已补录到 " : "已保存到 ";
                            Toast.makeText(ctx, verb + saved.fileName, Toast.LENGTH_SHORT).show();
                            loadQuickPreset("沿用上次");
                            renderHub();
                        }
                    });
                } catch (NoteWriter.NotConfiguredException exc) {
                    hubScroll.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(ctx, "请先选择 00-Inbox 文件夹", Toast.LENGTH_LONG).show();
                            saveBtn.setEnabled(true);
                            saveBtn.setText(isBackfill ? ("补录 " + shortDateLabel(targetDate) + " 训练") : "保存训练");
                            host.onNeedPickFolder();
                        }
                    });
                } catch (final Exception exc) {
                    hubScroll.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(ctx, "保存失败：" + exc.getMessage(), Toast.LENGTH_LONG).show();
                            saveBtn.setEnabled(true);
                            saveBtn.setText("重试保存");
                        }
                    });
                }
            }
        });
    }

    private String todayDateStr() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private String activeTargetDate() {
        String today = todayDateStr();
        if (selectedDateStr == null || selectedDateStr.isEmpty()) return today;
        return selectedDateStr;
    }

    private static Prefs.WorkoutRecord findRecordByDate(List<Prefs.WorkoutRecord> history, String dateStr) {
        if (history == null || dateStr == null) return null;
        for (Prefs.WorkoutRecord r : history) {
            if (dateStr.equals(r.date)) return r;
        }
        return null;
    }

    private static long timestampForDate(String dateStr) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr);
            if (d != null) return d.getTime() + 12L * 3600_000L;
        } catch (Exception ignored) {
        }
        return System.currentTimeMillis();
    }

    private static String weekdayShortLabel(String dateStr) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr);
            if (d != null) {
                Calendar c = Calendar.getInstance();
                c.setTime(d);
                String[] names = {"", "周日", "周一", "周二", "周三", "周四", "周五", "周六"};
                return names[c.get(Calendar.DAY_OF_WEEK)];
            }
        } catch (Exception ignored) {
        }
        return dateStr;
    }

    private static String formatMonthDayWeekday(String dateStr) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr);
            if (d != null) {
                String md = new SimpleDateFormat("M月d日", Locale.CHINA).format(d);
                return md + " " + weekdayShortLabel(dateStr);
            }
        } catch (Exception ignored) {
        }
        return dateStr;
    }

    /**
     * 极简单区 3×3 乘法胶囊点选板（零联动、只留一块网格）：
     * - 自由杠铃动作（默认 10kg 空杆）：点一下白卡变黑点亮 = 选中 1 组，再点一下取消；长按可同重叠加 +1 组
     * - 器械动作（默认每项 4 组）：点一下变黑并选中 N 组（右上角标 ×4），点标题旁「每项 4 组 ▾」可切 4/3/5/1 组
     */
    private void showQuickEditItemDialog(final int idx) {
        if (idx < 0 || idx >= quickItems.size()) return;
        final QuickItem item = quickItems.get(idx);
        final boolean isBarbell = isBarbellExercise(item.name);
        final double[] barKg = new double[]{
                isBarbell ? Prefs.barWeightFor(ctx, item.name, 10.0) : 0.0
        };
        // 非杠铃动作默认每次点亮添加的组数（若当前已是固定重 N 组，自动继承 N，否则默认 4 组）
        int initBatch = 4;
        if (!isBarbell && !item.sets.isEmpty()) {
            double fw = item.sets.get(0).weightKg;
            int fr = item.sets.get(0).reps;
            boolean allSame = true;
            for (Prefs.SetItem s : item.sets) {
                if (Math.abs(s.weightKg - fw) > 0.05 || s.reps != fr) {
                    allSame = false;
                    break;
                }
            }
            if (allSame && item.sets.size() >= 1 && item.sets.size() <= 6) {
                initBatch = item.sets.size();
            }
        }
        final int[] machineBatch = new int[]{isBarbell ? 1 : initBatch};

        final List<Prefs.SetItem> workingSets = new ArrayList<>();
        for (Prefs.SetItem s : item.sets) {
            workingSets.add(new Prefs.SetItem(s.weightKg, s.reps, true));
        }

        final LinearLayout root = sheetRoot(false);
        final Dialog dlg = createSheet(root, false);
        final boolean[] deletedItem = new boolean[]{false};

        final Runnable syncToItem = new Runnable() {
            @Override
            public void run() {
                if (deletedItem[0] || workingSets.isEmpty()) return;
                // 杠铃递增组自动按重量由轻到重排序，保证首页展示整洁
                if (isBarbell) {
                    java.util.Collections.sort(workingSets, new java.util.Comparator<Prefs.SetItem>() {
                        @Override
                        public int compare(Prefs.SetItem a, Prefs.SetItem b) {
                            int cw = Double.compare(a.weightKg, b.weightKg);
                            if (cw != 0) return cw;
                            return Integer.compare(b.reps, a.reps);
                        }
                    });
                }
                item.sets.clear();
                for (Prefs.SetItem s : workingSets) {
                    item.sets.add(new Prefs.SetItem(s.weightKg, s.reps, true));
                }
                item.modified = true;
            }
        };
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialog) {
                if (!deletedItem[0]) {
                    if (!workingSets.isEmpty()) {
                        syncToItem.run();
                    }
                    renderHub();
                }
            }
        });

        // 标题行：动作名 + 轻量切换胶囊（杠铃: 空杆 10kg ▾ / 器械: 每项 4 组 ▾）+ 删除动作
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout leftHead = new LinearLayout(ctx);
        leftHead.setOrientation(LinearLayout.HORIZONTAL);
        leftHead.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText(item.name);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.PAPER_INK);
        leftHead.addView(title);

        final TextView modePill = new TextView(ctx);
        modePill.setText(isBarbell
                ? ("空杆 " + FitnessStore.formatKg(barKg[0]) + "kg ▾")
                : ("每项 " + machineBatch[0] + " 组 ▾"));
        modePill.setTextSize(12);
        modePill.setSingleLine(true);
        modePill.setIncludeFontPadding(true);
        modePill.setTypeface(Typeface.DEFAULT_BOLD);
        modePill.setTextColor(UiKit.CORAL_ACCENT);
        modePill.setBackground(UiKit.roundRect(ctx, 0xFFFDECE6, 999));
        modePill.setPadding(UiKit.dp(ctx, 10), UiKit.dp(ctx, 4),
                UiKit.dp(ctx, 10), UiKit.dp(ctx, 4));
        LinearLayout.LayoutParams mpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mpLp.leftMargin = UiKit.dp(ctx, 10);
        leftHead.addView(modePill, mpLp);

        head.addView(leftHead, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView delBtn = new TextView(ctx);
        delBtn.setText("删除动作");
        delBtn.setTextSize(13);
        delBtn.setTypeface(Typeface.DEFAULT_BOLD);
        delBtn.setTextColor(UiKit.DANGER_RED);
        delBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                deletedItem[0] = true;
                quickItems.remove(idx);
                dlg.dismiss();
                renderHub();
            }
        });
        head.addView(delBtn);
        root.addView(head);

        // 标题下方仅留一行无框汇总轻字（不单独成块）
        final TextView whisperSum = new TextView(ctx);
        whisperSum.setTextSize(12);
        whisperSum.setTypeface(Typeface.DEFAULT_BOLD);
        whisperSum.setTextColor(UiKit.PAPER_MUTED);
        whisperSum.setFontFeatureSettings("tnum");
        whisperSum.setLineSpacing(UiKit.dp(ctx, 3), 1f);
        whisperSum.setPadding(0, UiKit.dp(ctx, 6), 0, UiKit.dp(ctx, 16));
        root.addView(whisperSum);

        // 唯一操作区：3 列规整网格（高占比可滚动容器，防止超大字号裁切）
        int maxGridH = Math.min(UiKit.dp(ctx, 320),
                (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.45f));
        final ScrollView gridScroll = new ScrollView(ctx);
        gridScroll.setVerticalScrollBarEnabled(false);
        final LinearLayout gridHost = new LinearLayout(ctx);
        gridHost.setOrientation(LinearLayout.VERTICAL);
        gridScroll.addView(gridHost, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        gridScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        gridHost.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                int h = b - t;
                ViewGroup.LayoutParams lp = gridScroll.getLayoutParams();
                int targetH = h > maxGridH ? maxGridH : ViewGroup.LayoutParams.WRAP_CONTENT;
                if (lp.height != targetH) {
                    lp.height = targetH;
                    gridScroll.setLayoutParams(lp);
                }
            }
        });
        root.addView(gridScroll);

        final List<Prefs.SetItem> customAdded = new ArrayList<>();
        final Runnable[] renderGrid = new Runnable[1];
        renderGrid[0] = new Runnable() {
            @Override
            public void run() {
                // 更新顶部 1 行轻汇总
                if (workingSets.isEmpty()) {
                    whisperSum.setText("点下方卡片点亮今天做的组");
                } else {
                    QuickItem tmp = new QuickItem(item.name);
                    if (isBarbell) {
                        List<Prefs.SetItem> sorted = new ArrayList<>(workingSets);
                        java.util.Collections.sort(sorted, new java.util.Comparator<Prefs.SetItem>() {
                            @Override
                            public int compare(Prefs.SetItem a, Prefs.SetItem b) {
                                int cw = Double.compare(a.weightKg, b.weightKg);
                                return cw != 0 ? cw : Integer.compare(b.reps, a.reps);
                            }
                        });
                        tmp.sets.addAll(sorted);
                    } else {
                        tmp.sets.addAll(workingSets);
                    }
                    whisperSum.setText("已选 " + workingSets.size() + " 组 · " + formatQuickVisualSets(tmp));
                }

                // 组装网格候选（确保当前已选的每一种组合都在网格里亮着）
                List<Prefs.SetItem> pool = buildCapsulePool(item.name, isBarbell, barKg[0], workingSets);
                for (Prefs.SetItem c : customAdded) {
                    addUniqueSet(pool, c.weightKg, c.reps);
                }
                for (Prefs.SetItem s : workingSets) {
                    addUniqueSet(pool, s.weightKg, s.reps);
                }
                if (isBarbell) {
                    java.util.Collections.sort(pool, new java.util.Comparator<Prefs.SetItem>() {
                        @Override
                        public int compare(Prefs.SetItem a, Prefs.SetItem b) {
                            int cw = Double.compare(a.weightKg, b.weightKg);
                            return cw != 0 ? cw : Integer.compare(b.reps, a.reps);
                        }
                    });
                }

                // 渲染 3 列规整网格（最后一格为「＋ 自定义」）
                gridHost.removeAllViews();
                int totalTiles = pool.size() + 1;
                int rows = (totalTiles + 2) / 3;
                for (int r = 0; r < rows; r++) {
                    LinearLayout row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setClipChildren(false);
                    row.setClipToPadding(false);
                    for (int c = 0; c < 3; c++) {
                        int tileIdx = r * 3 + c;
                        View tileView;
                        if (tileIdx < pool.size()) {
                            tileView = buildGridTile(pool.get(tileIdx), isBarbell, barKg[0],
                                    machineBatch[0], workingSets, syncToItem, renderGrid[0]);
                        } else if (tileIdx == pool.size()) {
                            tileView = buildCustomGridTile(item.name, isBarbell, barKg[0],
                                    machineBatch[0], workingSets, customAdded, syncToItem, renderGrid[0]);
                        } else {
                            tileView = new View(ctx);
                        }
                        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                                0, UiKit.dp(ctx, 64), 1f);
                        if (c < 2) tLp.rightMargin = UiKit.dp(ctx, 9);
                        row.addView(tileView, tLp);
                    }
                    LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (r < rows - 1) rLp.bottomMargin = UiKit.dp(ctx, 9);
                    gridHost.addView(row, rLp);
                }
            }
        };

        modePill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                if (isBarbell) {
                    double oldBar = barKg[0];
                    double nextBar = (Math.abs(oldBar - 10.0) < 0.1) ? 15.0
                            : (Math.abs(oldBar - 15.0) < 0.1 ? 20.0 : 10.0);
                    double delta = nextBar - oldBar;
                    barKg[0] = nextBar;
                    Prefs.setBarWeightFor(ctx, item.name, nextBar);
                    for (Prefs.SetItem s : workingSets) {
                        s.weightKg = Math.max(nextBar, s.weightKg + delta);
                    }
                    for (Prefs.SetItem s : customAdded) {
                        s.weightKg = Math.max(nextBar, s.weightKg + delta);
                    }
                    modePill.setText("空杆 " + FitnessStore.formatKg(nextBar) + "kg ▾");
                    if (!workingSets.isEmpty()) syncToItem.run();
                    renderGrid[0].run();
                } else {
                    int[] cycle = {4, 3, 5, 1};
                    int next = 4;
                    for (int i = 0; i < cycle.length; i++) {
                        if (cycle[i] == machineBatch[0]) {
                            next = cycle[(i + 1) % cycle.length];
                            break;
                        }
                    }
                    machineBatch[0] = next;
                    modePill.setText("每项 " + next + " 组 ▾");
                    // 若当前正好只选中了 1 种重量，顺手把它的组数同步改成新组数
                    if (!workingSets.isEmpty()) {
                        double fw = workingSets.get(0).weightKg;
                        int fr = workingSets.get(0).reps;
                        boolean singleCombo = true;
                        for (Prefs.SetItem s : workingSets) {
                            if (Math.abs(s.weightKg - fw) > 0.05 || s.reps != fr) {
                                singleCombo = false;
                                break;
                            }
                        }
                        if (singleCombo) {
                            workingSets.clear();
                            for (int k = 0; k < next; k++) {
                                workingSets.add(new Prefs.SetItem(fw, fr, true));
                            }
                            syncToItem.run();
                        }
                    }
                    renderGrid[0].run();
                }
            }
        });

        renderGrid[0].run();

        TextView doneBtn = UiKit.makePrimaryPill(ctx, "完成");
        doneBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticConfirm(ctx, v);
                if (!workingSets.isEmpty()) {
                    syncToItem.run();
                }
                dlg.dismiss();
            }
        });
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 50));
        dLp.topMargin = UiKit.dp(ctx, 18);
        root.addView(doneBtn, dLp);
        dlg.show();
    }

    private View buildGridTile(final Prefs.SetItem opt, final boolean isBarbell,
                               final double barKg, final int batchCount,
                               final List<Prefs.SetItem> workingSets,
                               final Runnable syncToItem, final Runnable rerender) {
        int count = countMatchingSets(workingSets, opt.weightKg, opt.reps);
        boolean active = count > 0;

        FrameLayout wrap = new FrameLayout(ctx);
        wrap.setBackground(UiKit.roundRect(ctx, active ? 0xFF1B1B19 : UiKit.PAPER_SHEET, 16));
        UiKit.applySoftShadow(wrap, active ? 6 : 3, active ? 0x3814120E : 0x16181410);
        UiKit.attachSpringPress(wrap);

        LinearLayout center = new LinearLayout(ctx);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        center.setPadding(UiKit.dp(ctx, 4), UiKit.dp(ctx, 4), UiKit.dp(ctx, 4), UiKit.dp(ctx, 4));

        TextView mainTxt = new TextView(ctx);
        mainTxt.setSingleLine(true);
        mainTxt.setIncludeFontPadding(true);
        mainTxt.setTextSize(13);
        mainTxt.setTypeface(Typeface.DEFAULT_BOLD);
        mainTxt.setTextColor(active ? Color.WHITE : UiKit.PAPER_INK);
        mainTxt.setFontFeatureSettings("tnum");

        if (isBarbell) {
            double side = Math.max(0, (opt.weightKg - barKg) / 2.0);
            mainTxt.setText((side <= 0.01 ? "空杆" : ("单侧" + FitnessStore.formatKg(side)))
                    + " ×" + opt.reps);
            center.addView(mainTxt);

            TextView subTxt = new TextView(ctx);
            subTxt.setText("共 " + FitnessStore.formatKg(opt.weightKg) + "kg");
            subTxt.setSingleLine(true);
            subTxt.setTextSize(10.5f);
            subTxt.setTypeface(Typeface.DEFAULT_BOLD);
            subTxt.setTextColor(active ? 0xA6FFFFFF : UiKit.PAPER_MUTED);
            subTxt.setFontFeatureSettings("tnum");
            subTxt.setPadding(0, UiKit.dp(ctx, 2), 0, 0);
            center.addView(subTxt);
        } else {
            String wLbl = opt.weightKg <= 0.01 ? "自重" : FitnessStore.formatKg(opt.weightKg);
            mainTxt.setText(wLbl + " ×" + opt.reps);
            center.addView(mainTxt);
        }

        wrap.addView(center, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (count >= 2) {
            TextView badge = new TextView(ctx);
            badge.setText("×" + count);
            badge.setTextSize(10);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setTextColor(Color.WHITE);
            badge.setBackground(UiKit.roundRect(ctx, UiKit.CORAL_ACCENT, 999));
            badge.setPadding(UiKit.dp(ctx, 5), UiKit.dp(ctx, 1), UiKit.dp(ctx, 5), UiKit.dp(ctx, 1));
            FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bLp.gravity = Gravity.END | Gravity.TOP;
            bLp.topMargin = UiKit.dp(ctx, 5);
            bLp.rightMargin = UiKit.dp(ctx, 6);
            wrap.addView(badge, bLp);
        }

        wrap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                int cur = countMatchingSets(workingSets, opt.weightKg, opt.reps);
                if (cur > 0) {
                    removeMatchingSets(workingSets, opt.weightKg, opt.reps);
                } else {
                    int addN = isBarbell ? 1 : Math.max(1, batchCount);
                    for (int k = 0; k < addN; k++) {
                        workingSets.add(new Prefs.SetItem(opt.weightKg, opt.reps, true));
                    }
                }
                if (!workingSets.isEmpty()) syncToItem.run();
                rerender.run();
            }
        });

        // 长按支持同重量叠加 +1 组（应对偶尔同一重量多做一组）
        wrap.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                UiKit.hapticConfirm(ctx, v);
                workingSets.add(new Prefs.SetItem(opt.weightKg, opt.reps, true));
                syncToItem.run();
                rerender.run();
                return true;
            }
        });
        return wrap;
    }

    private View buildCustomGridTile(final String name, final boolean isBarbell, final double barKg,
                                     final int batchCount, final List<Prefs.SetItem> workingSets,
                                     final List<Prefs.SetItem> customAdded,
                                     final Runnable syncToItem, final Runnable rerender) {
        LinearLayout cust = new LinearLayout(ctx);
        cust.setOrientation(LinearLayout.VERTICAL);
        cust.setGravity(Gravity.CENTER);
        cust.setBackground(UiKit.roundRect(ctx, 0xFFF0EDE5, 16));
        UiKit.attachSpringPress(cust);

        TextView cm = new TextView(ctx);
        cm.setText("＋ 自定义");
        cm.setSingleLine(true);
        cm.setTextSize(13);
        cm.setTypeface(Typeface.DEFAULT_BOLD);
        cm.setTextColor(UiKit.CORAL_ACCENT);
        cust.addView(cm);

        if (isBarbell) {
            TextView cs = new TextView(ctx);
            cs.setText("自填单侧");
            cs.setSingleLine(true);
            cs.setTextSize(10.5f);
            cs.setTextColor(UiKit.PAPER_MUTED);
            cs.setPadding(0, UiKit.dp(ctx, 2), 0, 0);
            cust.addView(cs);
        }

        cust.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                showCustomCapsuleDialog(name, isBarbell, barKg, isBarbell ? 1 : batchCount,
                        workingSets, customAdded, syncToItem, rerender);
            }
        });
        return cust;
    }

    private static int countMatchingSets(List<Prefs.SetItem> list, double w, int r) {
        int n = 0;
        for (Prefs.SetItem s : list) {
            if (Math.abs(s.weightKg - w) < 0.05 && s.reps == r) n++;
        }
        return n;
    }

    private static void removeMatchingSets(List<Prefs.SetItem> list, double w, int r) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Prefs.SetItem s = list.get(i);
            if (Math.abs(s.weightKg - w) < 0.05 && s.reps == r) {
                list.remove(i);
            }
        }
    }

    private void showCustomCapsuleDialog(String name, final boolean isBarbell, final double barWeightKg,
                                         final int addCount, final List<Prefs.SetItem> workingSets,
                                         final List<Prefs.SetItem> customAdded,
                                         final Runnable syncToItem, final Runnable onAdded) {
        LinearLayout root = sheetRoot(false);
        final Dialog dlg = createSheet(root, false);

        TextView title = new TextView(ctx);
        title.setText(isBarbell ? ("自定义单侧片重 · " + name) : ("自定义一组 · " + name));
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.PAPER_INK);
        root.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText(isBarbell
                ? ("输入单侧挂片重量（kg），自动按空杆 " + FitnessStore.formatKg(barWeightKg) + "kg ×2 汇总总重")
                : "输入这一组的重量（kg）与次数");
        sub.setTextSize(12);
        sub.setTextColor(UiKit.PAPER_MUTED);
        sub.setPadding(0, UiKit.dp(ctx, 4), 0, UiKit.dp(ctx, 14));
        root.addView(sub);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final EditText wInput = new EditText(ctx);
        wInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        wInput.setHint(isBarbell ? "单侧 kg（如 12.5）" : "重量 kg");
        wInput.setTextSize(16);
        wInput.setTypeface(Typeface.DEFAULT_BOLD);
        wInput.setTextColor(UiKit.PAPER_INK);
        wInput.setHintTextColor(UiKit.PAPER_IDLE);
        wInput.setBackground(UiKit.roundRect(ctx, UiKit.PAPER_MUTED_BG, 14));
        wInput.setPadding(UiKit.dp(ctx, 14), UiKit.dp(ctx, 12), UiKit.dp(ctx, 14), UiKit.dp(ctx, 12));
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f);
        wLp.rightMargin = UiKit.dp(ctx, 10);
        row.addView(wInput, wLp);

        TextView mul = new TextView(ctx);
        mul.setText("×");
        mul.setTextSize(18);
        mul.setTextColor(UiKit.PAPER_MUTED);
        row.addView(mul);

        final EditText rInput = new EditText(ctx);
        rInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        rInput.setHint("次数");
        rInput.setText(workingSets.isEmpty() ? "8" : String.valueOf(workingSets.get(workingSets.size() - 1).reps));
        rInput.setTextSize(16);
        rInput.setTypeface(Typeface.DEFAULT_BOLD);
        rInput.setTextColor(UiKit.PAPER_INK);
        rInput.setHintTextColor(UiKit.PAPER_IDLE);
        rInput.setBackground(UiKit.roundRect(ctx, UiKit.PAPER_MUTED_BG, 14));
        rInput.setPadding(UiKit.dp(ctx, 14), UiKit.dp(ctx, 12), UiKit.dp(ctx, 14), UiKit.dp(ctx, 12));
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f);
        rLp.leftMargin = UiKit.dp(ctx, 10);
        row.addView(rInput, rLp);
        root.addView(row);

        final TextView preview = new TextView(ctx);
        preview.setTextSize(12);
        preview.setTypeface(Typeface.DEFAULT_BOLD);
        preview.setTextColor(UiKit.CORAL_ACCENT);
        preview.setPadding(UiKit.dp(ctx, 4), UiKit.dp(ctx, 10), 0, 0);
        preview.setVisibility(isBarbell ? View.VISIBLE : View.GONE);
        if (isBarbell) {
            preview.setText("空杆 = " + FitnessStore.formatKg(barWeightKg) + " kg");
            wInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(android.text.Editable s) {
                    try {
                        double side = s.toString().trim().isEmpty() ? 0 : Double.parseDouble(s.toString().trim());
                        double tot = Math.max(barWeightKg, barWeightKg + Math.max(0, side) * 2.0);
                        preview.setText("折合总重：" + FitnessStore.formatKg(barWeightKg)
                                + " + " + FitnessStore.formatKg(Math.max(0, side)) + "×2 = "
                                + FitnessStore.formatKg(tot) + " kg");
                    } catch (Exception e) {
                        preview.setText("请输入数字");
                    }
                }
            });
        }
        root.addView(preview);

        TextView addBtn = UiKit.makePrimaryPill(ctx, "加入并点亮");
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    String ws = wInput.getText().toString().trim();
                    String rs = rInput.getText().toString().trim();
                    double inputW = ws.isEmpty() ? 0 : Double.parseDouble(ws);
                    int reps = rs.isEmpty() ? 8 : Integer.parseInt(rs);
                    if (inputW < 0 || reps <= 0 || reps > 200) {
                        Toast.makeText(ctx, "请输入合理的重量与次数", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    double totalKg = isBarbell ? (barWeightKg + inputW * 2.0) : inputW;
                    UiKit.hapticConfirm(ctx, v);
                    addUniqueSet(customAdded, totalKg, reps);
                    int n = Math.max(1, addCount);
                    for (int k = 0; k < n; k++) {
                        workingSets.add(new Prefs.SetItem(totalKg, reps, true));
                    }
                    syncToItem.run();
                    dlg.dismiss();
                    onAdded.run();
                } catch (Exception e) {
                    Toast.makeText(ctx, "请输入数字", Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 50));
        aLp.topMargin = UiKit.dp(ctx, 16);
        root.addView(addBtn, aLp);
        dlg.show();
    }

    private static boolean isBarbellExercise(String name) {
        if (name == null) return false;
        return "深蹲".equals(name) || "硬拉".equals(name) || "罗马尼亚硬拉".equals(name)
                || "平板卧推".equals(name) || "上斜卧推".equals(name) || "杠铃划船".equals(name)
                || "臀推".equals(name) || name.contains("杠铃");
    }

    private static String formatSelectedCapsuleLabel(Prefs.SetItem s, boolean isBarbell, double barKg) {
        if (isBarbell && s.weightKg >= barKg - 0.05) {
            double side = Math.max(0, (s.weightKg - barKg) / 2.0);
            String sideStr = side <= 0.01 ? "空杆" : ("+" + FitnessStore.formatKg(side));
            return sideStr + "×" + s.reps;
        }
        String w = s.weightKg <= 0.01 ? "自重" : FitnessStore.formatKg(s.weightKg);
        return w + "×" + s.reps;
    }

    private List<Prefs.SetItem> buildCapsulePool(String name, boolean isBarbell, double barKg,
                                                 List<Prefs.SetItem> currentSets) {
        List<Prefs.SetItem> pool = new ArrayList<>();
        if (isBarbell && "深蹲".equals(name)) {
            double[] sides = {0, 5, 10, 12.5, 15, 17.5, 20};
            int[] reps =     {10, 10, 8, 8,    8,  8,    8};
            for (int i = 0; i < sides.length; i++) {
                addUniqueSet(pool, barKg + sides[i] * 2.0, reps[i]);
            }
            addUniqueSet(pool, barKg + 20 * 2.0, 6);
        } else if (isBarbell && ("硬拉".equals(name) || "罗马尼亚硬拉".equals(name))) {
            double[] sides = {0, 5, 10, 15, 17.5, 20, 25};
            int[] reps =     {10, 8, 8,  8,  8,    8,  6};
            for (int i = 0; i < sides.length; i++) {
                addUniqueSet(pool, barKg + sides[i] * 2.0, reps[i]);
            }
        } else if (isBarbell) {
            double[] sides = {0, 2.5, 5, 7.5, 10, 12.5, 15};
            int[] reps =     {10, 10, 10, 8,   8,  8,    8};
            for (int i = 0; i < sides.length; i++) {
                addUniqueSet(pool, barKg + sides[i] * 2.0, reps[i]);
            }
        } else if ("坐姿腿屈伸".equals(name)) {
            for (double w : new double[]{25, 30, 35, 40, 45, 50}) addUniqueSet(pool, w, 12);
            addUniqueSet(pool, 35, 10);
            addUniqueSet(pool, 40, 10);
        } else if ("内收肌夹腿".equals(name)) {
            for (double w : new double[]{40, 45, 50, 55, 60}) addUniqueSet(pool, w, 12);
            addUniqueSet(pool, 50, 10);
            addUniqueSet(pool, 55, 10);
        } else {
            double anchor = defaultWeightFor(name);
            int defR = defaultRepsFor(name);
            if (!currentSets.isEmpty()) {
                anchor = currentSets.get(currentSets.size() - 1).weightKg;
                defR = currentSets.get(currentSets.size() - 1).reps;
            }
            if (anchor <= 0.01) {
                for (int r : new int[]{6, 8, 10, 12, 15, 20}) addUniqueSet(pool, 0, r);
            } else {
                double step = anchor >= 30 ? 5.0 : 2.5;
                for (int k = -2; k <= 3; k++) {
                    double w = Math.max(0, anchor + k * step);
                    addUniqueSet(pool, w, defR);
                }
                addUniqueSet(pool, anchor, Math.max(5, defR - 2));
            }
        }

        // 融合该动作历史里出现过的自定义组合（方便越用越顺手）
        List<Prefs.SetItem> prev = Prefs.lastExerciseSets(ctx, name);
        if (prev != null) {
            for (Prefs.SetItem s : prev) {
                if (pool.size() < 11) addUniqueSet(pool, s.weightKg, s.reps);
            }
        }
        return pool;
    }

    private static void addUniqueSet(List<Prefs.SetItem> list, double w, int r) {
        for (Prefs.SetItem item : list) {
            if (Math.abs(item.weightKg - w) < 0.05 && item.reps == r) return;
        }
        list.add(new Prefs.SetItem(w, r, true));
    }

    private LinearLayout lightFocalRow(String label, LinearLayout strip, View.OnClickListener onMore) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(ctx, 10);
        row.setLayoutParams(lp);

        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextSize(12);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        l.setTextColor(UiKit.PAPER_MUTED);
        row.addView(l, new LinearLayout.LayoutParams(UiKit.dp(ctx, 34), ViewGroup.LayoutParams.WRAP_CONTENT));

        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(strip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (onMore != null) {
            TextView more = new TextView(ctx);
            more.setText("⋯");
            more.setTextSize(16);
            more.setTextColor(UiKit.PAPER_MUTED);
            more.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            more.setOnClickListener(onMore);
            row.addView(more, new LinearLayout.LayoutParams(UiKit.dp(ctx, 28), UiKit.dp(ctx, 38)));
        }
        return row;
    }

    private TextView makeLightOption(String text, boolean active) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setFontFeatureSettings("tnum");
        tv.setTextSize(active ? 15 : 14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(active ? UiKit.PAPER_INK : UiKit.PAPER_MUTED);
        if (active) tv.setBackground(UiKit.roundRect(ctx, 0xFFEDEAE2, 999));
        return tv;
    }

    private void showQuickAddExerciseDialog() {
        showExercisePickerSheet(false, new ExercisePickCallback() {
            @Override
            public void onPick(String name) {
                for (int i = 0; i < quickItems.size(); i++) {
                    if (name.equals(quickItems.get(i).name)) {
                        showQuickEditItemDialog(i);
                        return;
                    }
                }
                QuickItem added = new QuickItem(name);
                List<Prefs.SetItem> prev = Prefs.lastExerciseSets(ctx, name);
                if (prev != null && !prev.isEmpty()) {
                    for (Prefs.SetItem s : prev) {
                        added.sets.add(new Prefs.SetItem(s.weightKg, s.reps, true));
                    }
                } else {
                    added.sets.addAll(defaultSetsFor(name));
                }
                added.modified = true;
                quickItems.add(added);
                renderHub();
            }
        });
    }

    // =========================================================================
    // 2. SCENARIO B: DARKROOM LIVE STAGE (自己练：计时器 + 液体休息 + 右上角唯一「结束」)
    // =========================================================================
    private void startStageFromQuickItems(final String title) {
        if (countDoneSets() > 0) {
            confirmReplaceActiveStage(new Runnable() {
                @Override
                public void run() {
                    initAndEnterStage(title);
                }
            });
            return;
        }
        initAndEnterStage(title);
    }

    private void initAndEnterStage(String title) {
        sessionTitle = title;
        sessionWarmup = currentWarmupSummary();
        sessionStartMs = System.currentTimeMillis();
        restEndWallMs = 0L;
        activeExercises.clear();
        weightAnchors.clear();
        repsAnchors.clear();
        if (quickItems.isEmpty()) {
            activeExercises.add(createExerciseWithLastDefaults("深蹲", 20, 10));
        } else {
            for (QuickItem qi : quickItems) {
                double w = qi.sets.isEmpty() ? defaultWeightFor(qi.name) : qi.sets.get(0).weightKg;
                int r = qi.sets.isEmpty() ? defaultRepsFor(qi.name) : qi.sets.get(0).reps;
                activeExercises.add(createExerciseWithLastDefaults(qi.name, w, r));
            }
        }
        activeIndex = 0;
        saveDraft();
        enterStage();
    }

    private Prefs.ExerciseBlock createExerciseWithLastDefaults(String name, double fallbackW, int fallbackR) {
        Prefs.ExerciseBlock block = new Prefs.ExerciseBlock(name);
        block.sets.add(new Prefs.SetItem(fallbackW, fallbackR, false));
        weightAnchors.put(name, fallbackW);
        repsAnchors.put(name, fallbackR);
        return block;
    }

    private void enterStage() {
        inStage = true;
        stageRoot.setAlpha(0f);
        stageRoot.setVisibility(View.VISIBLE);
        stageRoot.animate().alpha(1f).setDuration(220).start();
        host.onActiveStageChanged(true);
        buildStageShell();
        renderStageBody();
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
        syncRestFromWallClock();
    }

    private void buildStageShell() {
        stageShell.removeAllViews();

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(UiKit.dp(ctx, 24), UiKit.dp(ctx, 4), UiKit.dp(ctx, 24), 0);

        TextView collapse = new TextView(ctx);
        collapse.setText("⌄ 收起");
        collapse.setTextSize(14);
        collapse.setTypeface(Typeface.DEFAULT_BOLD);
        collapse.setTextColor(UiKit.DARK_SECOND);
        collapse.setPadding(0, UiKit.dp(ctx, 8), UiKit.dp(ctx, 16), UiKit.dp(ctx, 8));
        collapse.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                exitStageToHub();
            }
        });
        top.addView(collapse);

        LinearLayout mid = new LinearLayout(ctx);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        clockView = new TextView(ctx);
        clockView.setText("00:00");
        clockView.setTextSize(15);
        clockView.setTypeface(Typeface.DEFAULT_BOLD);
        clockView.setTextColor(0xFFD4D2CC);
        clockView.setFontFeatureSettings("tnum");
        mid.addView(clockView);

        stageTitleView = new TextView(ctx);
        stageTitleView.setText(sessionTitle);
        stageTitleView.setTextSize(11);
        stageTitleView.setTextColor(UiKit.DARK_MUTED);
        mid.addView(stageTitleView);
        mid.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRenameSessionDialog();
            }
        });
        top.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView finishBtn = new TextView(ctx);
        finishBtn.setText("结束");
        finishBtn.setTextSize(14);
        finishBtn.setTypeface(Typeface.DEFAULT_BOLD);
        finishBtn.setTextColor(0xFFFF8552);
        finishBtn.setPadding(UiKit.dp(ctx, 16), UiKit.dp(ctx, 8), 0, UiKit.dp(ctx, 8));
        finishBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticConfirm(ctx, v);
                showFinishSummaryDialog();
            }
        });
        top.addView(finishBtn);
        stageShell.addView(top);

        LinearLayout exBarWrap = new LinearLayout(ctx);
        exBarWrap.setOrientation(LinearLayout.HORIZONTAL);
        exBarWrap.setGravity(Gravity.CENTER_VERTICAL);
        exBarWrap.setClipChildren(false);
        exBarWrap.setClipToPadding(false);
        exBarWrap.setPadding(UiKit.dp(ctx, 22), UiKit.dp(ctx, 14), UiKit.dp(ctx, 22), UiKit.dp(ctx, 4));

        HorizontalScrollView exScroll = new HorizontalScrollView(ctx);
        exScroll.setHorizontalScrollBarEnabled(false);
        exScroll.setClipChildren(false);
        exScroll.setClipToPadding(false);

        exerciseBar = new LinearLayout(ctx);
        exerciseBar.setOrientation(LinearLayout.HORIZONTAL);
        exerciseBar.setGravity(Gravity.CENTER_VERTICAL);
        exerciseBar.setClipChildren(false);
        exerciseBar.setClipToPadding(false);
        exScroll.addView(exerciseBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        exBarWrap.addView(exScroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView addExBtn = new TextView(ctx);
        addExBtn.setText("＋");
        addExBtn.setTextSize(22);
        addExBtn.setTextColor(UiKit.DARK_MUTED);
        addExBtn.setPadding(UiKit.dp(ctx, 14), UiKit.dp(ctx, 6), 0, UiKit.dp(ctx, 6));
        addExBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                showStageAddExerciseDialog();
            }
        });
        exBarWrap.addView(addExBtn);
        stageShell.addView(exBarWrap);

        HorizontalScrollView trailScroll = new HorizontalScrollView(ctx);
        trailScroll.setHorizontalScrollBarEnabled(false);
        trailRow = new LinearLayout(ctx);
        trailRow.setOrientation(LinearLayout.HORIZONTAL);
        trailRow.setPadding(UiKit.dp(ctx, 24), UiKit.dp(ctx, 10), UiKit.dp(ctx, 24), 0);
        trailScroll.addView(trailRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stageShell.addView(trailScroll);

        stageShell.addView(new Space(ctx), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout readout = new LinearLayout(ctx);
        readout.setOrientation(LinearLayout.VERTICAL);
        readout.setGravity(Gravity.CENTER_HORIZONTAL);
        readout.setPadding(UiKit.dp(ctx, 16), 0, UiKit.dp(ctx, 16), 0);

        LinearLayout numRow = new LinearLayout(ctx);
        numRow.setOrientation(LinearLayout.HORIZONTAL);
        numRow.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);

        bigWeightText = new TextView(ctx);
        bigWeightText.setTextSize(72);
        bigWeightText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        bigWeightText.setTextColor(Color.WHITE);
        bigWeightText.setFontFeatureSettings("tnum");
        numRow.addView(bigWeightText);

        TextView unitKg = new TextView(ctx);
        unitKg.setText(" kg");
        unitKg.setTextSize(19);
        unitKg.setTypeface(Typeface.DEFAULT_BOLD);
        unitKg.setTextColor(UiKit.DARK_MUTED);
        unitKg.setPadding(0, 0, UiKit.dp(ctx, 6), UiKit.dp(ctx, 10));
        numRow.addView(unitKg);

        TextView cross = new TextView(ctx);
        cross.setText("×");
        cross.setTextSize(30);
        cross.setTextColor(UiKit.DARK_FADED);
        cross.setPadding(UiKit.dp(ctx, 6), 0, UiKit.dp(ctx, 8), UiKit.dp(ctx, 8));
        numRow.addView(cross);

        bigRepsText = new TextView(ctx);
        bigRepsText.setTextSize(72);
        bigRepsText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        bigRepsText.setTextColor(Color.WHITE);
        bigRepsText.setFontFeatureSettings("tnum");
        numRow.addView(bigRepsText);
        readout.addView(numRow);

        stageSubHint = new TextView(ctx);
        stageSubHint.setTextSize(13);
        stageSubHint.setTypeface(Typeface.DEFAULT_BOLD);
        stageSubHint.setTextColor(UiKit.DARK_MUTED);
        stageSubHint.setGravity(Gravity.CENTER);
        stageSubHint.setPadding(0, UiKit.dp(ctx, 10), 0, 0);
        readout.addView(stageSubHint);
        stageShell.addView(readout);

        stageShell.addView(new Space(ctx), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.1f));

        LinearLayout focalGroup = new LinearLayout(ctx);
        focalGroup.setOrientation(LinearLayout.VERTICAL);
        focalGroup.setPadding(UiKit.dp(ctx, 22), 0, UiKit.dp(ctx, 22), UiKit.dp(ctx, 20));

        weightStrip = new LinearLayout(ctx);
        repsStrip = new LinearLayout(ctx);

        focalGroup.addView(buildDarkFocalRow("KG", weightStrip, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                Prefs.ExerciseBlock cur = activeExercises.get(activeIndex);
                Prefs.SetItem draft = ensurePendingSet(cur);
                showDarkQuickNumberSheet("选择或输入重量（kg）", draft.weightKg, true,
                        new double[]{0, 20, 30, 40, 50, 55, 60, 80},
                        new QuickValCallback() {
                            @Override
                            public void onPicked(double val) {
                                Prefs.ExerciseBlock c = activeExercises.get(activeIndex);
                                ensurePendingSet(c).weightKg = val;
                                weightAnchors.put(c.name, val);
                                saveDraft();
                                renderStageBody();
                            }
                        });
            }
        }));

        LinearLayout rWrap = buildDarkFocalRow("次", repsStrip, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                Prefs.ExerciseBlock cur = activeExercises.get(activeIndex);
                Prefs.SetItem draft = ensurePendingSet(cur);
                showDarkQuickNumberSheet("选择或输入次数", draft.reps, false,
                        new double[]{5, 6, 8, 10, 12, 15, 18, 20},
                        new QuickValCallback() {
                            @Override
                            public void onPicked(double val) {
                                Prefs.ExerciseBlock c = activeExercises.get(activeIndex);
                                int r = Math.max(1, (int) Math.round(val));
                                ensurePendingSet(c).reps = r;
                                repsAnchors.put(c.name, r);
                                saveDraft();
                                renderStageBody();
                            }
                        });
            }
        });
        LinearLayout.LayoutParams rwLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rwLp.topMargin = UiKit.dp(ctx, 16);
        focalGroup.addView(rWrap, rwLp);
        stageShell.addView(focalGroup);

        morphDock = new FrameLayout(ctx);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 60));
        mLp.leftMargin = UiKit.dp(ctx, 22);
        mLp.rightMargin = UiKit.dp(ctx, 22);

        readyPillBtn = UiKit.makePrimaryPill(ctx, "完成第 1 组");
        readyPillBtn.setMinHeight(UiKit.dp(ctx, 60));
        readyPillBtn.setTextSize(17);
        readyPillBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onCompleteOneSet(v);
            }
        });
        morphDock.addView(readyPillBtn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 60)));

        restBarView = new UiKit.LiquidRestBarView(ctx);
        restBarView.setVisibility(View.GONE);
        restBarView.setOnPlus30Listener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                long now = System.currentTimeMillis();
                restEndWallMs = Math.max(now, restEndWallMs) + 30_000L;
                int remain = (int) Math.ceil((restEndWallMs - now) / 1000.0);
                restTotalSec = Math.max(restTotalSec, remain);
                saveDraft();
                updateRestVisual(remain);
            }
        });
        restBarView.setOnSkipListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                stopRest();
            }
        });
        restBarView.setOnClockClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleDefaultRest(v);
            }
        });
        morphDock.addView(restBarView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 60)));

        stageShell.addView(morphDock, mLp);
    }

    private LinearLayout buildDarkFocalRow(String label, LinearLayout stripHost,
                                           View.OnClickListener onMore) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView lbl = new TextView(ctx);
        lbl.setText(label);
        lbl.setTextSize(11);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);
        lbl.setTextColor(0xFF575652);
        row.addView(lbl, new LinearLayout.LayoutParams(UiKit.dp(ctx, 28), ViewGroup.LayoutParams.WRAP_CONTENT));

        stripHost.setOrientation(LinearLayout.HORIZONTAL);
        stripHost.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(stripHost, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView more = new TextView(ctx);
        more.setText("⋯");
        more.setTextSize(17);
        more.setTextColor(0xFF63625E);
        more.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        more.setOnClickListener(onMore);
        row.addView(more, new LinearLayout.LayoutParams(UiKit.dp(ctx, 30), UiKit.dp(ctx, 38)));
        return row;
    }

    private void renderStageBody() {
        if (activeExercises.isEmpty()) {
            activeExercises.add(createExerciseWithLastDefaults("深蹲", 20, 10));
            activeIndex = 0;
        }
        if (activeIndex >= activeExercises.size()) {
            activeIndex = activeExercises.size() - 1;
        }

        exerciseBar.removeAllViews();
        for (int i = 0; i < activeExercises.size(); i++) {
            final int idx = i;
            final Prefs.ExerciseBlock ex = activeExercises.get(i);
            boolean sel = (i == activeIndex);

            LinearLayout tabCol = new LinearLayout(ctx);
            tabCol.setOrientation(LinearLayout.VERTICAL);
            tabCol.setPadding(0, UiKit.dp(ctx, 4), UiKit.dp(ctx, 22), UiKit.dp(ctx, 4));
            tabCol.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView nameTv = new TextView(ctx);
            nameTv.setText(ex.name);
            nameTv.setSingleLine(true);
            nameTv.setMaxLines(1);
            nameTv.setIncludeFontPadding(true);
            nameTv.setTextSize(sel ? 23 : 17);
            nameTv.setTypeface(Typeface.DEFAULT_BOLD);
            nameTv.setTextColor(sel ? UiKit.DARK_INK : UiKit.DARK_FADED);
            tabCol.addView(nameTv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            View underline = new View(ctx);
            if (sel) underline.setBackground(UiKit.roundRect(ctx, 0xFFFF7A45, 2));
            LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(
                    UiKit.dp(ctx, 18), UiKit.dp(ctx, 3));
            uLp.topMargin = UiKit.dp(ctx, 5);
            tabCol.addView(underline, uLp);

            tabCol.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.hapticTick(v);
                    activeIndex = idx;
                    renderStageBody();
                }
            });
            if (activeExercises.size() > 1) {
                tabCol.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        activeExercises.remove(idx);
                        activeIndex = Math.max(0, activeIndex - 1);
                        saveDraft();
                        Toast.makeText(ctx, "已移除「" + ex.name + "」", Toast.LENGTH_SHORT).show();
                        renderStageBody();
                        return true;
                    }
                });
            }
            exerciseBar.addView(tabCol);
        }

        final Prefs.ExerciseBlock cur = activeExercises.get(activeIndex);
        final Prefs.SetItem draftSet = ensurePendingSet(cur);
        final double prevBest = Prefs.bestWeightFor(ctx, cur.name);
        final List<Prefs.SetItem> lastSets = Prefs.lastExerciseSets(ctx, cur.name);

        if (!weightAnchors.containsKey(cur.name)) weightAnchors.put(cur.name, draftSet.weightKg);
        if (!repsAnchors.containsKey(cur.name)) repsAnchors.put(cur.name, draftSet.reps);
        double wAnchor = weightAnchors.get(cur.name);
        int rAnchor = repsAnchors.get(cur.name);

        trailRow.removeAllViews();
        List<Prefs.SetItem> completed = new ArrayList<>();
        for (Prefs.SetItem s : cur.sets) if (s.done) completed.add(s);

        for (int j = 0; j < completed.size(); j++) {
            final Prefs.SetItem doneItem = completed.get(j);
            TextView itemTv = new TextView(ctx);
            String wStr = doneItem.weightKg <= 0.01 ? "自重" : FitnessStore.formatKg(doneItem.weightKg);
            itemTv.setText("✓ " + wStr + "×" + doneItem.reps);
            itemTv.setSingleLine(true);
            itemTv.setTextSize(13);
            itemTv.setTypeface(Typeface.DEFAULT_BOLD);
            itemTv.setTextColor(0xFF9E9B94);
            itemTv.setFontFeatureSettings("tnum");
            itemTv.setPadding(0, UiKit.dp(ctx, 2), UiKit.dp(ctx, 14), UiKit.dp(ctx, 2));
            itemTv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.hapticTick(v);
                    cur.sets.remove(doneItem);
                    saveDraft();
                    Toast.makeText(ctx, "已撤销该组", Toast.LENGTH_SHORT).show();
                    renderStageBody();
                }
            });
            trailRow.addView(itemTv);
        }

        int nextSetNo = completed.size() + 1;
        if (!isRestingNow()) {
            TextView curSetTv = new TextView(ctx);
            curSetTv.setText("· 第 " + nextSetNo + " 组");
            curSetTv.setSingleLine(true);
            curSetTv.setTextSize(13);
            curSetTv.setTypeface(Typeface.DEFAULT_BOLD);
            curSetTv.setTextColor(0xFFFF935C);
            trailRow.addView(curSetTv);
        }

        String wTxt = draftSet.weightKg <= 0.01 ? "0" : FitnessStore.formatKg(draftSet.weightKg);
        bigWeightText.setText(wTxt);
        bigWeightText.setTextSize(wTxt.length() >= 5 ? 58 : 72);
        bigRepsText.setText(String.valueOf(draftSet.reps));
        bigRepsText.setTextSize(wTxt.length() >= 5 ? 58 : 72);

        if (isRestingNow()) {
            stageSubHint.setText("组间休息 · 可直接调整第 " + nextSetNo + " 组重量");
            stageSubHint.setTextColor(0xFFA39E96);
        } else {
            StringBuilder sub = new StringBuilder();
            if (lastSets != null && !lastSets.isEmpty()) {
                int refIdx = Math.min(completed.size(), lastSets.size() - 1);
                Prefs.SetItem refLast = lastSets.get(refIdx);
                sub.append("上次第").append(refIdx + 1).append("组 ")
                   .append(refLast.weightKg <= 0.01 ? "自重" : FitnessStore.formatKg(refLast.weightKg) + " kg")
                   .append(" × ").append(refLast.reps);
            } else {
                sub.append("点下方数字切换重量或次数");
            }
            if (prevBest > 0 && draftSet.weightKg > prevBest + 0.01) {
                sub.append("   ✦ 比上次 +").append(FitnessStore.formatKg(draftSet.weightKg - prevBest)).append(" kg");
                stageSubHint.setTextColor(UiKit.CORAL_DARK_TXT);
            } else {
                stageSubHint.setTextColor(UiKit.DARK_MUTED);
            }
            stageSubHint.setText(sub.toString());
        }

        weightStrip.removeAllViews();
        for (double val : buildWeightChoices(wAnchor)) {
            final double pick = val;
            boolean active = Math.abs(pick - draftSet.weightKg) < 0.05;
            boolean near = !active && Math.abs(pick - draftSet.weightKg) <= 2.6;
            String label = pick <= 0.01 ? "自重" : FitnessStore.formatKg(pick);
            TextView opt = buildDarkFocalOption(label, active, near);
            opt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.hapticTick(v);
                    draftSet.weightKg = pick;
                    saveDraft();
                    renderStageBody();
                }
            });
            weightStrip.addView(opt, new LinearLayout.LayoutParams(0, UiKit.dp(ctx, 38), 1f));
        }

        repsStrip.removeAllViews();
        for (int val : buildRepChoices(rAnchor)) {
            final int pick = val;
            boolean active = (pick == draftSet.reps);
            boolean near = !active && Math.abs(pick - draftSet.reps) == 1;
            TextView opt = buildDarkFocalOption(String.valueOf(pick), active, near);
            opt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.hapticTick(v);
                    draftSet.reps = pick;
                    saveDraft();
                    renderStageBody();
                }
            });
            repsStrip.addView(opt, new LinearLayout.LayoutParams(0, UiKit.dp(ctx, 38), 1f));
        }

        readyPillBtn.setText("完成第 " + nextSetNo + " 组");
        renderMorphDock();
    }

    private TextView buildDarkFocalOption(String text, boolean active, boolean near) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER);
        tv.setFontFeatureSettings("tnum");
        tv.setTextSize(active ? 16 : 15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        if (active) {
            tv.setTextColor(Color.WHITE);
            tv.setBackground(UiKit.roundRect(ctx, 0x24FFFFFF, 999));
        } else {
            tv.setTextColor(near ? 0xFF9E9C96 : 0xFF63625E);
        }
        return tv;
    }

    private void onCompleteOneSet(View v) {
        UiKit.hapticConfirm(ctx, v);
        Prefs.ExerciseBlock cur = activeExercises.get(activeIndex);
        Prefs.SetItem draftSet = ensurePendingSet(cur);
        draftSet.done = true;

        // If last session had progressive sets for this exercise, hint next set's weight/reps smoothly
        int doneCount = 0;
        for (Prefs.SetItem s : cur.sets) if (s.done) doneCount++;
        List<Prefs.SetItem> prev = Prefs.lastExerciseSets(ctx, cur.name);
        double nextW = draftSet.weightKg;
        int nextR = draftSet.reps;
        if (prev != null && doneCount < prev.size()) {
            nextW = prev.get(doneCount).weightKg;
            nextR = prev.get(doneCount).reps;
        }
        weightAnchors.put(cur.name, nextW);
        repsAnchors.put(cur.name, nextR);
        cur.sets.add(new Prefs.SetItem(nextW, nextR, false));

        restTotalSec = Prefs.restSeconds(ctx);
        restEndWallMs = System.currentTimeMillis() + restTotalSec * 1000L;
        saveDraft();
        renderStageBody();
        syncRestFromWallClock();
    }

    private boolean isRestingNow() {
        return restEndWallMs > System.currentTimeMillis();
    }

    private void syncRestFromWallClock() {
        handler.removeCallbacks(restTick);
        if (restEndWallMs <= 0L) {
            renderMorphDock();
            return;
        }
        int remain = (int) Math.ceil((restEndWallMs - System.currentTimeMillis()) / 1000.0);
        if (remain <= 0) {
            restEndWallMs = 0L;
            renderMorphDock();
            return;
        }
        renderMorphDock();
        updateRestVisual(remain);
        handler.postDelayed(restTick, 350);
    }

    private void renderMorphDock() {
        if (morphDock == null) return;
        boolean resting = isRestingNow();
        spotlightBg.setDimmed(resting);
        readyPillBtn.setVisibility(resting ? View.GONE : View.VISIBLE);
        restBarView.setVisibility(resting ? View.VISIBLE : View.GONE);
    }

    private void updateRestVisual(int remain) {
        if (restBarView == null) return;
        float pct = restTotalSec <= 0 ? 0f : (float) remain / restTotalSec;
        restBarView.setState(formatClock(remain), pct);
    }

    private void stopRest() {
        restEndWallMs = 0L;
        handler.removeCallbacks(restTick);
        saveDraft();
        renderStageBody();
    }

    private void cycleDefaultRest(View v) {
        UiKit.hapticTick(v);
        int[] opts = {60, 90, 120, 180};
        int cur = Prefs.restSeconds(ctx);
        int next = opts[0];
        for (int i = 0; i < opts.length; i++) {
            if (opts[i] == cur) {
                next = opts[(i + 1) % opts.length];
                break;
            }
        }
        Prefs.setRestSeconds(ctx, next);
        restTotalSec = next;
        restEndWallMs = System.currentTimeMillis() + next * 1000L;
        saveDraft();
        updateRestVisual(next);
        Toast.makeText(ctx, "组间休息已设为 " + next + " 秒", Toast.LENGTH_SHORT).show();
    }

    // =========================================================================
    // 3. SHARED HARMONIZED BOTTOM SHEETS
    // =========================================================================
    private interface QuickValCallback {
        void onPicked(double v);
    }

    private interface ExercisePickCallback {
        void onPick(String name);
    }

    private Dialog createSheet(View contentView, boolean dark) {
        Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(contentView);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(dark ? 0.65f : 0.35f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return dlg;
    }

    private LinearLayout sheetRoot(boolean dark) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(ctx, 24), UiKit.dp(ctx, 12),
                UiKit.dp(ctx, 24), UiKit.dp(ctx, 28));
        root.setBackground(UiKit.topRoundSheet(ctx,
                dark ? UiKit.DARK_SHEET : 0xFFFAF9F6, 32));
        View handle = new View(ctx);
        handle.setBackground(UiKit.roundRect(ctx, dark ? 0x33FFFFFF : 0xFFDFDCD5, 999));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 36), UiKit.dp(ctx, 4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = UiKit.dp(ctx, 16);
        root.addView(handle, hLp);
        return root;
    }

    public void openBodyWeightSheet() {
        final LinearLayout root = sheetRoot(false);
        final Dialog dlg = createSheet(root, false);

        double base = Prefs.latestBodyWeight(ctx);
        if (base <= 0) base = 54.0;
        final double[] selected = new double[]{base};

        TextView t = new TextView(ctx);
        t.setText("记录体重");
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiKit.PAPER_INK);
        root.addView(t);

        TextView sub = new TextView(ctx);
        sub.setText("选一个接近的数值，一键写入今天的 Obsidian 健身记录");
        sub.setTextSize(12);
        sub.setTextColor(UiKit.PAPER_MUTED);
        sub.setPadding(0, UiKit.dp(ctx, 4), 0, UiKit.dp(ctx, 18));
        root.addView(sub);

        final TextView bigKg = new TextView(ctx);
        bigKg.setTextSize(46);
        bigKg.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        bigKg.setTextColor(UiKit.PAPER_INK);
        bigKg.setFontFeatureSettings("tnum");
        bigKg.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(bigKg);

        final LinearLayout strip = new LinearLayout(ctx);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = UiKit.dp(ctx, 16);
        root.addView(strip, sLp);

        final TextView save = UiKit.makePrimaryPill(ctx, "保存体重");
        final double anchor = base;
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = new Runnable() {
            @Override
            public void run() {
                bigKg.setText(FitnessStore.formatKg(selected[0]) + " kg");
                strip.removeAllViews();
                double[] opts = {
                        Math.round((anchor - 0.5) * 10.0) / 10.0,
                        Math.round((anchor - 0.2) * 10.0) / 10.0,
                        Math.round(anchor * 10.0) / 10.0,
                        Math.round((anchor + 0.2) * 10.0) / 10.0,
                        Math.round((anchor + 0.5) * 10.0) / 10.0
                };
                for (double opt : opts) {
                    final double v = opt;
                    boolean on = Math.abs(v - selected[0]) < 0.05;
                    TextView tv = makeLightOption(FitnessStore.formatKg(v), on);
                    tv.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            UiKit.hapticTick(view);
                            selected[0] = v;
                            refresh[0].run();
                        }
                    });
                    strip.addView(tv, new LinearLayout.LayoutParams(0, UiKit.dp(ctx, 40), 1f));
                }
                save.setText("记录 " + FitnessStore.formatKg(selected[0]) + " kg");
            }
        };
        refresh[0].run();

        TextView customLink = new TextView(ctx);
        customLink.setText("手动输入其他数字 ›");
        customLink.setTextSize(12);
        customLink.setTextColor(UiKit.PAPER_SECOND);
        customLink.setGravity(Gravity.CENTER_HORIZONTAL);
        customLink.setPadding(0, UiKit.dp(ctx, 12), 0, 0);
        customLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLightQuickInput("输入体重（kg）", selected[0], true, new QuickValCallback() {
                    @Override
                    public void onPicked(double val) {
                        selected[0] = val;
                        refresh[0].run();
                    }
                });
            }
        });
        root.addView(customLink);

        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticConfirm(ctx, v);
                final double kg = selected[0];
                final String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                Prefs.setLatestBodyWeight(ctx, kg, date);
                host.onBodyWeightUpdated();
                save.setEnabled(false);
                save.setText("写入中…");
                IO_EXEC.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final FitnessStore.Saved saved = FitnessStore.saveBodyWeight(ctx, date, kg);
                            hubScroll.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(ctx, "已写入 " + saved.fileName,
                                            Toast.LENGTH_SHORT).show();
                                    if (dlg.isShowing()) dlg.dismiss();
                                }
                            });
                        } catch (NoteWriter.NotConfiguredException exc) {
                            hubScroll.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(ctx, "已记到本地；请先选择 00-Inbox 目录",
                                            Toast.LENGTH_LONG).show();
                                    save.setEnabled(true);
                                    save.setText("记录 " + FitnessStore.formatKg(kg) + " kg");
                                    host.onNeedPickFolder();
                                }
                            });
                        } catch (final Exception exc) {
                            hubScroll.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(ctx, "写入失败：" + exc.getMessage(),
                                            Toast.LENGTH_LONG).show();
                                    save.setEnabled(true);
                                    save.setText("重试");
                                }
                            });
                        }
                    }
                });
            }
        });
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 54));
        svLp.topMargin = UiKit.dp(ctx, 18);
        root.addView(save, svLp);

        dlg.show();
    }

    private void showLightQuickInput(String title, double current, final boolean decimal,
                                     final QuickValCallback cb) {
        final LinearLayout root = sheetRoot(false);
        final Dialog dlg = createSheet(root, false);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiKit.PAPER_INK);
        root.addView(t);

        final EditText et = new EditText(ctx);
        et.setText(FitnessStore.formatKg(current));
        et.setTextColor(UiKit.PAPER_INK);
        et.setTextSize(18);
        et.setSelectAllOnFocus(true);
        et.setInputType(decimal
                ? (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL)
                : InputType.TYPE_CLASS_NUMBER);
        et.setPadding(UiKit.dp(ctx, 16), UiKit.dp(ctx, 14), UiKit.dp(ctx, 16), UiKit.dp(ctx, 14));
        et.setBackground(UiKit.roundRect(ctx, UiKit.PAPER_MUTED_BG, 16));
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        eLp.topMargin = UiKit.dp(ctx, 14);
        root.addView(et, eLp);

        TextView ok = UiKit.makePrimaryPill(ctx, "确定");
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    double parsed = Double.parseDouble(et.getText().toString().trim());
                    if (parsed < 0 || parsed > 500) {
                        Toast.makeText(ctx, "数值超出合理范围", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    cb.onPicked(decimal ? Math.round(parsed * 10.0) / 10.0 : Math.round(parsed));
                    dlg.dismiss();
                } catch (Exception ignored) {
                    Toast.makeText(ctx, "请输入有效数字", Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 52));
        oLp.topMargin = UiKit.dp(ctx, 14);
        root.addView(ok, oLp);
        dlg.show();
    }

    private void showDarkQuickNumberSheet(String title, double currentVal, final boolean decimal,
                                          double[] presets, final QuickValCallback cb) {
        final LinearLayout root = sheetRoot(true);
        final Dialog dlg = createSheet(root, true);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiKit.DARK_INK);
        root.addView(t);

        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < presets.length; r += 4) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = r; c < Math.min(r + 4, presets.length); c++) {
                final double val = presets[c];
                TextView p = new TextView(ctx);
                p.setText(decimal && val == 0 ? "自重" : FitnessStore.formatKg(val));
                p.setTextSize(15);
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setTextColor(UiKit.DARK_INK);
                p.setGravity(Gravity.CENTER);
                p.setFontFeatureSettings("tnum");
                p.setBackground(UiKit.rippleWrap(UiKit.roundRect(ctx, UiKit.DARK_INPUT_BG, 999), 0x26FFFFFF));
                p.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        UiKit.hapticTick(v);
                        cb.onPicked(val);
                        dlg.dismiss();
                    }
                });
                LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, UiKit.dp(ctx, 46), 1f);
                if (c > r) pLp.leftMargin = UiKit.dp(ctx, 8);
                row.addView(p, pLp);
            }
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.topMargin = UiKit.dp(ctx, 10);
            grid.addView(row, rLp);
        }
        root.addView(grid);

        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText et = new EditText(ctx);
        et.setHint("其他数值");
        et.setHintTextColor(UiKit.DARK_MUTED);
        et.setTextColor(UiKit.DARK_INK);
        et.setText(FitnessStore.formatKg(currentVal));
        et.setSelectAllOnFocus(true);
        et.setInputType(decimal
                ? (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL)
                : InputType.TYPE_CLASS_NUMBER);
        et.setPadding(UiKit.dp(ctx, 16), UiKit.dp(ctx, 12), UiKit.dp(ctx, 16), UiKit.dp(ctx, 12));
        et.setBackground(UiKit.roundRect(ctx, UiKit.DARK_INPUT_BG, 16));
        inputRow.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView ok = UiKit.makePrimaryPill(ctx, "应用");
        ok.setMinHeight(UiKit.dp(ctx, 48));
        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(UiKit.dp(ctx, 92), UiKit.dp(ctx, 48));
        oLp.leftMargin = UiKit.dp(ctx, 10);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    double parsed = Double.parseDouble(et.getText().toString().trim());
                    if (parsed < 0 || parsed > 500) {
                        Toast.makeText(ctx, "数值超出合理范围", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    cb.onPicked(parsed);
                    dlg.dismiss();
                } catch (Exception ignored) {
                    Toast.makeText(ctx, "请输入有效数字", Toast.LENGTH_SHORT).show();
                }
            }
        });
        inputRow.addView(ok, oLp);
        LinearLayout.LayoutParams irLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        irLp.topMargin = UiKit.dp(ctx, 16);
        root.addView(inputRow, irLp);
        dlg.show();
    }

    private void showStageAddExerciseDialog() {
        showExercisePickerSheet(true, new ExercisePickCallback() {
            @Override
            public void onPick(String name) {
                for (int i = 0; i < activeExercises.size(); i++) {
                    if (name.equals(activeExercises.get(i).name)) {
                        activeIndex = i;
                        renderStageBody();
                        return;
                    }
                }
                List<Prefs.SetItem> prev = Prefs.lastExerciseSets(ctx, name);
                List<Prefs.SetItem> ref = (prev != null && !prev.isEmpty()) ? prev : defaultSetsFor(name);
                double w = ref.get(0).weightKg;
                int r = ref.get(0).reps;
                activeExercises.add(createExerciseWithLastDefaults(name, w, r));
                activeIndex = activeExercises.size() - 1;
                saveDraft();
                renderStageBody();
            }
        });
    }

    private void showExercisePickerSheet(final boolean dark, final ExercisePickCallback cb) {
        final LinearLayout root = sheetRoot(dark);
        final Dialog dlg = createSheet(root, dark);

        TextView t = new TextView(ctx);
        t.setText("添加动作");
        t.setTextSize(19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(dark ? UiKit.DARK_INK : UiKit.PAPER_INK);
        root.addView(t);

        LinearLayout customRow = new LinearLayout(ctx);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText et = new EditText(ctx);
        et.setHint("自定义动作（如：罗马尼亚硬拉）");
        et.setHintTextColor(dark ? UiKit.DARK_MUTED : UiKit.PAPER_MUTED);
        et.setTextColor(dark ? UiKit.DARK_INK : UiKit.PAPER_INK);
        et.setTextSize(14);
        et.setSingleLine(true);
        et.setPadding(UiKit.dp(ctx, 16), UiKit.dp(ctx, 11), UiKit.dp(ctx, 16), UiKit.dp(ctx, 11));
        et.setBackground(UiKit.roundRect(ctx, dark ? UiKit.DARK_INPUT_BG : UiKit.PAPER_MUTED_BG, 16));
        customRow.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView addBtn = UiKit.makePrimaryPill(ctx, "添加");
        addBtn.setMinHeight(UiKit.dp(ctx, 44));
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(UiKit.dp(ctx, 76), UiKit.dp(ctx, 44));
        aLp.leftMargin = UiKit.dp(ctx, 10);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = et.getText().toString().trim();
                if (name.isEmpty()) return;
                Prefs.addCustomExercise(ctx, name);
                dlg.dismiss();
                cb.onPick(name);
            }
        });
        customRow.addView(addBtn, aLp);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crLp.topMargin = UiKit.dp(ctx, 12);
        root.addView(customRow, crLp);

        ScrollView listScroll = new ScrollView(ctx);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);

        List<String> customs = Prefs.customExercises(ctx);
        if (!customs.isEmpty()) {
            addCategoryChips(list, "我的自定义", customs.toArray(new String[0]), dark, dlg, cb);
        }
        for (Map.Entry<String, String[]> cat : CATEGORIES.entrySet()) {
            addCategoryChips(list, cat.getKey(), cat.getValue(), dark, dlg, cb);
        }
        listScroll.addView(list);
        int maxH = Math.min(UiKit.dp(ctx, 290),
                (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.42f));
        LinearLayout.LayoutParams lsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxH);
        lsLp.topMargin = UiKit.dp(ctx, 10);
        root.addView(listScroll, lsLp);
        dlg.show();
    }

    private void addCategoryChips(LinearLayout parent, String catName, String[] names,
                                  boolean dark, final Dialog dlg, final ExercisePickCallback cb) {
        TextView lbl = new TextView(ctx);
        lbl.setText(catName);
        lbl.setTextSize(11);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);
        lbl.setTextColor(dark ? UiKit.DARK_MUTED : UiKit.PAPER_MUTED);
        lbl.setPadding(0, UiKit.dp(ctx, 12), 0, UiKit.dp(ctx, 4));
        parent.addView(lbl);

        for (int i = 0; i < names.length; i += 3) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < Math.min(i + 3, names.length); j++) {
                final String name = names[j];
                TextView chip = new TextView(ctx);
                chip.setText(name);
                chip.setSingleLine(true);
                chip.setTextSize(13);
                chip.setTypeface(Typeface.DEFAULT_BOLD);
                chip.setTextColor(dark ? UiKit.DARK_INK : UiKit.PAPER_INK);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(UiKit.dp(ctx, 8), UiKit.dp(ctx, 10), UiKit.dp(ctx, 8), UiKit.dp(ctx, 10));
                chip.setBackground(UiKit.rippleWrap(
                        UiKit.roundRect(ctx, dark ? UiKit.DARK_INPUT_BG : UiKit.PAPER_MUTED_BG, 999),
                        dark ? 0x26FFFFFF : 0x16000000));
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        UiKit.hapticTick(v);
                        dlg.dismiss();
                        cb.onPick(name);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (j > i) lp.leftMargin = UiKit.dp(ctx, 8);
                row.addView(chip, lp);
            }
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.topMargin = UiKit.dp(ctx, 8);
            parent.addView(row, rLp);
        }
    }

    private void showRenameSessionDialog() {
        final LinearLayout root = sheetRoot(true);
        final Dialog dlg = createSheet(root, true);

        TextView t = new TextView(ctx);
        t.setText("本次训练名称");
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiKit.DARK_INK);
        root.addView(t);

        final EditText et = new EditText(ctx);
        et.setText(sessionTitle);
        et.setTextColor(UiKit.DARK_INK);
        et.setSingleLine(true);
        et.setPadding(UiKit.dp(ctx, 16), UiKit.dp(ctx, 13), UiKit.dp(ctx, 16), UiKit.dp(ctx, 13));
        et.setBackground(UiKit.roundRect(ctx, UiKit.DARK_INPUT_BG, 16));
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        eLp.topMargin = UiKit.dp(ctx, 12);
        root.addView(et, eLp);

        TextView ok = UiKit.makePrimaryPill(ctx, "确定");
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String val = et.getText().toString().trim();
                if (!val.isEmpty()) {
                    sessionTitle = val;
                    if (stageTitleView != null) stageTitleView.setText(sessionTitle);
                    saveDraft();
                }
                dlg.dismiss();
            }
        });
        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 52));
        oLp.topMargin = UiKit.dp(ctx, 14);
        root.addView(ok, oLp);
        dlg.show();
    }

    private void showFinishSummaryDialog() {
        final Prefs.WorkoutRecord record = buildCompletedRecord();
        if (record.totalSets == 0) {
            LinearLayout root = sheetRoot(true);
            final Dialog dlg = createSheet(root, true);
            TextView t = new TextView(ctx);
            t.setText("还没有记录已完成的组");
            t.setTextSize(18);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(UiKit.DARK_INK);
            root.addView(t);

            TextView s = new TextView(ctx);
            s.setText("点底部按钮完成一组，或者放弃这次空白训练。");
            s.setTextSize(13);
            s.setTextColor(UiKit.DARK_SECOND);
            s.setPadding(0, UiKit.dp(ctx, 6), 0, UiKit.dp(ctx, 18));
            root.addView(s);

            TextView discard = new TextView(ctx);
            discard.setText("放弃这次数据");
            discard.setTextSize(15);
            discard.setTypeface(Typeface.DEFAULT_BOLD);
            discard.setTextColor(0xFFFF7A7A);
            discard.setGravity(Gravity.CENTER);
            discard.setPadding(0, UiKit.dp(ctx, 14), 0, UiKit.dp(ctx, 14));
            discard.setBackground(UiKit.roundRect(ctx, UiKit.DARK_INPUT_BG, 999));
            discard.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dlg.dismiss();
                    discardActiveWorkout();
                }
            });
            root.addView(discard);
            dlg.show();
            return;
        }

        restEndWallMs = 0L;
        handler.removeCallbacks(restTick);
        renderMorphDock();

        final LinearLayout root = sheetRoot(true);
        final Dialog dlg = createSheet(root, true);

        TextView head = new TextView(ctx);
        head.setText("练完了 · " + record.title);
        head.setTextSize(23);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(UiKit.DARK_INK);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(head);

        int mins = Math.max(1, Math.round(record.durationSec / 60f));
        String vol = record.totalVolumeKg >= 1000
                ? String.format(Locale.US, "%.1ft", record.totalVolumeKg / 1000.0)
                : Math.round(record.totalVolumeKg) + "kg";

        TextView statsLine = new TextView(ctx);
        statsLine.setText(mins + " 分钟   ·   " + record.totalSets + " 组   ·   " + vol);
        statsLine.setTextSize(15);
        statsLine.setTypeface(Typeface.DEFAULT_BOLD);
        statsLine.setTextColor(UiKit.DARK_SECOND);
        statsLine.setFontFeatureSettings("tnum");
        statsLine.setGravity(Gravity.CENTER_HORIZONTAL);
        statsLine.setPadding(0, UiKit.dp(ctx, 8), 0, 0);
        root.addView(statsLine);

        if (!record.prSummary.isEmpty()) {
            TextView pr = new TextView(ctx);
            pr.setText("✦ 新纪录：" + record.prSummary);
            pr.setTextSize(14);
            pr.setTypeface(Typeface.DEFAULT_BOLD);
            pr.setTextColor(UiKit.CORAL_DARK_TXT);
            pr.setGravity(Gravity.CENTER_HORIZONTAL);
            pr.setPadding(0, UiKit.dp(ctx, 10), 0, 0);
            root.addView(pr);
        }

        final EditText noteEt = new EditText(ctx);
        noteEt.setHint("写一句备注（可选，例如：状态很好）");
        noteEt.setHintTextColor(UiKit.DARK_MUTED);
        noteEt.setTextColor(UiKit.DARK_INK);
        noteEt.setTextSize(14);
        noteEt.setSingleLine(true);
        noteEt.setPadding(UiKit.dp(ctx, 16), UiKit.dp(ctx, 12), UiKit.dp(ctx, 16), UiKit.dp(ctx, 12));
        noteEt.setBackground(UiKit.roundRect(ctx, UiKit.DARK_INPUT_BG, 16));
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.topMargin = UiKit.dp(ctx, 16);
        root.addView(noteEt, nLp);

        final TextView saveBtn = UiKit.makePrimaryPill(ctx, "保存到 Obsidian");
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticConfirm(ctx, v);
                final String note = noteEt.getText().toString().trim();
                saveBtn.setEnabled(false);
                saveBtn.setText("写入中…");
                IO_EXEC.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final FitnessStore.Saved saved =
                                    FitnessStore.saveSession(ctx, record.date, record, note);
                            Prefs.saveWorkoutRecord(ctx, record);
                            stageRoot.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(ctx, "已保存到 " + saved.fileName,
                                            Toast.LENGTH_SHORT).show();
                                    discardActiveWorkout();
                                    if (dlg.isShowing()) dlg.dismiss();
                                }
                            });
                        } catch (NoteWriter.NotConfiguredException exc) {
                            stageRoot.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(ctx, "请先选择 00-Inbox 文件夹",
                                            Toast.LENGTH_LONG).show();
                                    saveBtn.setEnabled(true);
                                    saveBtn.setText("保存到 Obsidian");
                                    host.onNeedPickFolder();
                                }
                            });
                        } catch (final Exception exc) {
                            stageRoot.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(ctx, "保存失败：" + exc.getMessage(),
                                            Toast.LENGTH_LONG).show();
                                    saveBtn.setEnabled(true);
                                    saveBtn.setText("重试保存");
                                }
                            });
                        }
                    }
                });
            }
        });
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 54));
        sbLp.topMargin = UiKit.dp(ctx, 14);
        root.addView(saveBtn, sbLp);

        TextView discardLink = new TextView(ctx);
        discardLink.setText("放弃这次数据");
        discardLink.setTextSize(14);
        discardLink.setTypeface(Typeface.DEFAULT_BOLD);
        discardLink.setTextColor(0xFFFF7A7A);
        discardLink.setGravity(Gravity.CENTER_HORIZONTAL);
        discardLink.setPadding(0, UiKit.dp(ctx, 16), 0, UiKit.dp(ctx, 4));
        discardLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                dlg.dismiss();
                discardActiveWorkout();
                Toast.makeText(ctx, "已放弃本次训练数据", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(discardLink);

        dlg.show();
    }

    private void showHistoryDetailDialog(final Prefs.WorkoutRecord rec) {
        final LinearLayout root = sheetRoot(false);
        final Dialog dlg = createSheet(root, false);

        TextView title = new TextView(ctx);
        title.setText(rec.date + " · " + rec.title);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.PAPER_INK);
        root.addView(title);

        if (rec.warmup != null && !rec.warmup.isEmpty()) {
            TextView w = new TextView(ctx);
            w.setText("热身：" + rec.warmup);
            w.setTextSize(12);
            w.setTextColor(UiKit.PAPER_MUTED);
            w.setPadding(0, UiKit.dp(ctx, 6), 0, 0);
            root.addView(w);
        }

        ScrollView detailScroll = new ScrollView(ctx);
        LinearLayout detailList = new LinearLayout(ctx);
        detailList.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < rec.exercises.size(); i++) {
            if (i > 0) {
                View div = new View(ctx);
                div.setBackgroundColor(UiKit.PAPER_DIVIDER);
                detailList.addView(div, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 1)));
            }
            Prefs.ExerciseBlock b = rec.exercises.get(i);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, UiKit.dp(ctx, 10), 0, UiKit.dp(ctx, 10));

            TextView n = new TextView(ctx);
            n.setText(b.name);
            n.setTextSize(15);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            n.setTextColor(UiKit.PAPER_INK);
            row.addView(n);

            TextView s = new TextView(ctx);
            s.setText(FitnessStore.formatSets(b.sets));
            s.setTextSize(13);
            s.setTextColor(UiKit.PAPER_SECOND);
            s.setFontFeatureSettings("tnum");
            s.setPadding(0, UiKit.dp(ctx, 2), 0, 0);
            row.addView(s);
            detailList.addView(row);
        }
        detailScroll.addView(detailList);
        int maxH = Math.min(UiKit.dp(ctx, 260),
                (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.4f));
        LinearLayout.LayoutParams dsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (rec.exercises.size() > 5) dsLp.height = maxH;
        dsLp.topMargin = UiKit.dp(ctx, 8);
        root.addView(detailScroll, dsLp);

        TextView again = UiKit.makePrimaryPill(ctx, "载入到今日记录");
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticConfirm(ctx, v);
                dlg.dismiss();
                quickModeKey = rec.title;
                quickItems.clear();
                activeWarmups.clear();
                if (rec.warmup != null && !rec.warmup.isEmpty()) {
                    for (String w : rec.warmup.split("、")) {
                        if (!w.trim().isEmpty()) activeWarmups.add(w.trim());
                    }
                }
                for (Prefs.ExerciseBlock b : rec.exercises) {
                    QuickItem qi = new QuickItem(b.name);
                    for (Prefs.SetItem s : b.sets) {
                        qi.sets.add(new Prefs.SetItem(s.weightKg, s.reps, true));
                    }
                    if (qi.sets.isEmpty()) qi.sets.addAll(defaultSetsFor(b.name));
                    quickItems.add(qi);
                }
                renderHub();
            }
        });
        LinearLayout.LayoutParams agLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 54));
        agLp.topMargin = UiKit.dp(ctx, 16);
        root.addView(again, agLp);
        dlg.show();
    }

    private void confirmReplaceActiveStage(final Runnable startAction) {
        LinearLayout root = sheetRoot(false);
        final Dialog dlg = createSheet(root, false);

        TextView t = new TextView(ctx);
        t.setText("还有未结束的训练");
        t.setTextSize(19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiKit.PAPER_INK);
        root.addView(t);

        TextView s = new TextView(ctx);
        s.setText("「" + sessionTitle + "」已记 " + countDoneSets() + " 组，要继续它还是放弃并重开？");
        s.setTextSize(13);
        s.setTextColor(UiKit.PAPER_SECOND);
        s.setPadding(0, UiKit.dp(ctx, 6), 0, UiKit.dp(ctx, 18));
        root.addView(s);

        TextView resumeBtn = UiKit.makePrimaryPill(ctx, "继续刚才的训练");
        resumeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                enterStage();
            }
        });
        root.addView(resumeBtn);

        TextView replaceBtn = new TextView(ctx);
        replaceBtn.setText("放弃旧记录并新开");
        replaceBtn.setTextSize(14);
        replaceBtn.setTypeface(Typeface.DEFAULT_BOLD);
        replaceBtn.setTextColor(UiKit.DANGER_RED);
        replaceBtn.setGravity(Gravity.CENTER);
        replaceBtn.setPadding(0, UiKit.dp(ctx, 14), 0, UiKit.dp(ctx, 4));
        replaceBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                startAction.run();
            }
        });
        root.addView(replaceBtn);
        dlg.show();
    }

    // =========================================================================
    // HELPERS & DEFAULT SUNDAY LEG-DAY PYRAMID DATA
    // =========================================================================
    private Prefs.SetItem ensurePendingSet(Prefs.ExerciseBlock ex) {
        for (int i = ex.sets.size() - 1; i >= 0; i--) {
            if (!ex.sets.get(i).done) return ex.sets.get(i);
        }
        double w = ex.sets.isEmpty() ? defaultWeightFor(ex.name) : ex.sets.get(ex.sets.size() - 1).weightKg;
        int r = ex.sets.isEmpty() ? defaultRepsFor(ex.name) : ex.sets.get(ex.sets.size() - 1).reps;
        Prefs.SetItem s = new Prefs.SetItem(w, r, false);
        ex.sets.add(s);
        return s;
    }

    private Prefs.WorkoutRecord buildCompletedRecord() {
        Prefs.WorkoutRecord rec = new Prefs.WorkoutRecord();
        rec.date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        rec.timestampMs = System.currentTimeMillis();
        rec.title = sessionTitle;
        rec.warmup = sessionWarmup;
        rec.durationSec = (int) Math.max(60, (rec.timestampMs - sessionStartMs) / 1000L);

        StringBuilder prSb = new StringBuilder();
        for (Prefs.ExerciseBlock b : activeExercises) {
            Prefs.ExerciseBlock clean = new Prefs.ExerciseBlock(b.name);
            double prevBest = Prefs.bestWeightFor(ctx, b.name);
            double maxThis = 0;
            for (Prefs.SetItem s : b.sets) {
                if (!s.done) continue;
                clean.sets.add(new Prefs.SetItem(s.weightKg, s.reps, true));
                rec.totalSets++;
                rec.totalVolumeKg += s.weightKg * s.reps;
                if (s.weightKg > maxThis) maxThis = s.weightKg;
            }
            if (!clean.sets.isEmpty()) {
                rec.exercises.add(clean);
                if (prevBest > 0 && maxThis > prevBest + 0.01) {
                    if (prSb.length() > 0) prSb.append("、");
                    prSb.append(b.name).append(" +").append(FitnessStore.formatKg(maxThis - prevBest)).append("kg");
                }
            }
        }
        rec.prSummary = prSb.toString();
        return rec;
    }

    private void discardActiveWorkout() {
        restEndWallMs = 0L;
        handler.removeCallbacks(restTick);
        handler.removeCallbacks(clockTick);
        activeExercises.clear();
        weightAnchors.clear();
        repsAnchors.clear();
        sessionStartMs = 0L;
        sessionWarmup = "";
        inStage = false;
        Prefs.clearDraft(ctx);
        stageRoot.setVisibility(View.GONE);
        loadQuickPreset(defaultPresetKey());
        renderHub();
        host.onActiveStageChanged(false);
    }

    private void saveDraft() {
        if (activeExercises.isEmpty() || sessionStartMs <= 0) {
            Prefs.clearDraft(ctx);
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("title", sessionTitle);
            o.put("warm", sessionWarmup);
            o.put("start", sessionStartMs);
            o.put("idx", activeIndex);
            o.put("restEnd", restEndWallMs);
            o.put("restTotal", restTotalSec);
            JSONArray exArr = new JSONArray();
            for (Prefs.ExerciseBlock b : activeExercises) {
                JSONObject bo = new JSONObject();
                bo.put("n", b.name);
                JSONArray sArr = new JSONArray();
                for (Prefs.SetItem s : b.sets) {
                    JSONObject so = new JSONObject();
                    so.put("w", s.weightKg);
                    so.put("r", s.reps);
                    so.put("d", s.done);
                    sArr.put(so);
                }
                bo.put("s", sArr);
                exArr.put(bo);
            }
            o.put("ex", exArr);
            Prefs.saveDraft(ctx, o.toString());
        } catch (Exception ignored) {
        }
    }

    private void restoreDraftIfPresent() {
        String raw = Prefs.loadDraft(ctx);
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(raw);
            long start = o.optLong("start", 0L);
            if (start <= 0) {
                Prefs.clearDraft(ctx);
                return;
            }
            sessionTitle = o.optString("title", "今日训练");
            sessionWarmup = o.optString("warm", "");
            sessionStartMs = start;
            activeIndex = o.optInt("idx", 0);
            restEndWallMs = o.optLong("restEnd", 0L);
            restTotalSec = o.optInt("restTotal", Prefs.restSeconds(ctx));
            activeExercises.clear();
            weightAnchors.clear();
            repsAnchors.clear();
            JSONArray exArr = o.optJSONArray("ex");
            int doneTotal = 0;
            if (exArr != null) {
                for (int i = 0; i < exArr.length(); i++) {
                    JSONObject bo = exArr.optJSONObject(i);
                    if (bo == null) continue;
                    Prefs.ExerciseBlock b = new Prefs.ExerciseBlock(bo.optString("n", ""));
                    JSONArray sArr = bo.optJSONArray("s");
                    if (sArr != null) {
                        for (int j = 0; j < sArr.length(); j++) {
                            JSONObject so = sArr.optJSONObject(j);
                            if (so == null) continue;
                            boolean d = so.optBoolean("d", false);
                            if (d) doneTotal++;
                            b.sets.add(new Prefs.SetItem(
                                    so.optDouble("w", 20),
                                    so.optInt("r", 10),
                                    d
                            ));
                        }
                    }
                    if (!b.sets.isEmpty()) {
                        Prefs.SetItem ref = b.sets.get(b.sets.size() - 1);
                        weightAnchors.put(b.name, ref.weightKg);
                        repsAnchors.put(b.name, ref.reps);
                    }
                    activeExercises.add(b);
                }
            }
            if (doneTotal == 0 && System.currentTimeMillis() - start > 8 * 3_600_000L) {
                activeExercises.clear();
                sessionStartMs = 0L;
                Prefs.clearDraft(ctx);
            }
        } catch (Exception ignored) {
            Prefs.clearDraft(ctx);
        }
    }

    private int countDoneSets() {
        int n = 0;
        for (Prefs.ExerciseBlock b : activeExercises) {
            for (Prefs.SetItem s : b.sets) if (s.done) n++;
        }
        return n;
    }

    private String buildEyebrowCopy(Prefs.WorkoutRecord latest) {
        String dayName = new SimpleDateFormat("EEEE", Locale.CHINA).format(new Date());
        int days = Prefs.daysSinceLastWorkout(latest);
        if (days < 0) return dayName + " · 随时开始记录";
        if (days == 0) return dayName + " · 今天已经记过一轮";
        if (days == 1) return dayName + " · 距上次「" + latest.title + "」1 天";
        return dayName + " · 距上次「" + latest.title + "」已过 " + days + " 天";
    }

    private String buildEditorialHeadline(Prefs.WorkoutRecord latest) {
        int days = Prefs.daysSinceLastWorkout(latest);
        if (days < 0) return "练完了，\n随手记一笔。";
        if (days == 0) return "今天已记录，\n有加练可随时补充。";
        if (days == 1) return "昨天刚练完，\n今天状态怎么样？";
        return "肌肉恢复好了，\n练完直接点保存。";
    }

    private static List<Prefs.SetItem> defaultSetsFor(String name) {
        List<Prefs.SetItem> list = new ArrayList<>();
        if ("深蹲".equals(name)) {
            // 固定空杆 10kg + 左右 0 / 5 / 10 / 15 / 17.5kg 递增 → 10 / 20 / 30 / 40 / 45kg
            list.add(new Prefs.SetItem(10, 10, true));
            list.add(new Prefs.SetItem(20, 10, true));
            list.add(new Prefs.SetItem(30, 8, true));
            list.add(new Prefs.SetItem(40, 8, true));
            list.add(new Prefs.SetItem(45, 8, true));
            return list;
        }
        if ("硬拉".equals(name)) {
            // 固定空杆 10kg + 左右 5 / 10 / 15kg 递增 → 20 / 30 / 40kg
            list.add(new Prefs.SetItem(20, 8, true));
            list.add(new Prefs.SetItem(30, 8, true));
            list.add(new Prefs.SetItem(40, 8, true));
            return list;
        }
        if ("坐姿腿屈伸".equals(name)) {
            for (int i = 0; i < 4; i++) list.add(new Prefs.SetItem(35, 12, true));
            return list;
        }
        if ("内收肌夹腿".equals(name)) {
            for (int i = 0; i < 4; i++) list.add(new Prefs.SetItem(50, 12, true));
            return list;
        }
        double w = defaultWeightFor(name);
        int r = defaultRepsFor(name);
        for (int i = 0; i < 3; i++) list.add(new Prefs.SetItem(w, r, true));
        return list;
    }

    private static double[] buildWeightChoices(double anchor) {
        if (anchor <= 0.01) return new double[]{0, 5, 10, 15, 20};
        double start = Math.max(0, anchor - 5.0);
        double[] out = new double[5];
        for (int i = 0; i < 5; i++) {
            out[i] = Math.round((start + i * 2.5) * 10.0) / 10.0;
        }
        return out;
    }

    private static int[] buildRepChoices(int anchor) {
        int start = Math.max(1, anchor - 2);
        int[] out = new int[5];
        for (int i = 0; i < 5; i++) out[i] = start + i;
        return out;
    }

    private static double defaultWeightFor(String name) {
        if (name.contains("俯卧撑") || name.contains("引体")) return 0;
        if (name.contains("内收肌")) return 50;
        if (name.contains("腿屈伸")) return 35;
        if (name.contains("硬拉")) return 30;
        if (name.contains("腿举")) return 60;
        if (name.contains("深蹲")) return 20;
        if (name.contains("肩推") || name.contains("弯举") || name.contains("侧平举")) return 10;
        return 30;
    }

    private static int defaultRepsFor(String name) {
        if (name.contains("硬拉") || name.contains("深蹲")) return 8;
        if (name.contains("腿屈伸") || name.contains("内收肌")) return 12;
        return 10;
    }

    private static String shortDateLabel(String date) {
        if (date == null || date.length() < 10) return "";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date d = in.parse(date);
            return new SimpleDateFormat("M.d E", Locale.CHINA).format(d);
        } catch (Exception ignored) {
            return date.substring(5);
        }
    }

    private static String formatClock(int sec) {
        int m = Math.max(0, sec) / 60;
        int s = Math.max(0, sec) % 60;
        return String.format(Locale.US, "%02d:%02d", m, s);
    }
}
