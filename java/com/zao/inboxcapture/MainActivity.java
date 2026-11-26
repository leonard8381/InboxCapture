package com.zao.inboxcapture;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 主入口（昼夜双境）：
 * - 日常态：纸感暖白 #F6F4EF + 顶部大字「训练 / 灵感」切换（无底部框线导航栏）
 * - 训练中：全屏切入暗室聚光模式 #0B0B0D，状态栏同步变暗
 */
public class MainActivity extends Activity implements WorkoutView.Host, InboxView.Host {
    private static final int REQ_TREE = 42;
    private static final String SAMPLE =
            "小红书 用 AI 把老照片做成温暖微缩 3D 小房间，完整节点拆解 http://xhslink.com/a/AbCdEf";
    private static final String OLD_PACKAGE = "com.zao.inboxcapture";

    private FrameLayout root;
    private LinearLayout dayShell;
    private LinearLayout topRightHost;
    private TextView tabTrain;
    private TextView tabInbox;
    private WorkoutView workoutView;
    private InboxView inboxView;
    private DualPagePager pagePager;
    private int currentTab = 0; // 0=训练, 1=灵感

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        applyDayNightSystemBars(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        tryAutoRestoreAccessibility();
        if (inboxView != null) inboxView.refresh();
        if (workoutView != null) workoutView.refresh();
        refreshTopRight();
    }

    @Override
    protected void onDestroy() {
        if (workoutView != null) workoutView.release();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (currentTab == 0 && workoutView != null && workoutView.isInStage()) {
            workoutView.exitStageToHub();
            return;
        }
        super.onBackPressed();
    }

    private View buildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(UiKit.PAPER_BG);

        // 1. Day Editorial Container (Top Nav + Active Day Page)
        dayShell = new LinearLayout(this);
        dayShell.setOrientation(LinearLayout.VERTICAL);
        dayShell.setBackgroundColor(UiKit.PAPER_BG);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 14),
                UiKit.dp(this, 22), UiKit.dp(this, 4));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.BOTTOM);

        tabTrain = new TextView(this);
        tabTrain.setText("训练");
        tabTrain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                switchTab(0);
            }
        });
        tabs.addView(tabTrain);

        tabInbox = new TextView(this);
        tabInbox.setText("灵感");
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inLp.leftMargin = UiKit.dp(this, 18);
        tabInbox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiKit.hapticTick(v);
                switchTab(1);
            }
        });
        tabs.addView(tabInbox, inLp);

        topBar.addView(tabs, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        topRightHost = new LinearLayout(this);
        topRightHost.setOrientation(LinearLayout.HORIZONTAL);
        topRightHost.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(topRightHost);

        dayShell.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        workoutView = new WorkoutView(this, this);
        inboxView = new InboxView(this, this);
        pagePager = new DualPagePager(this, workoutView.getHubView(), inboxView);

        dayShell.addView(pagePager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(dayShell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Full-Screen Darkroom Focus Stage (above dayShell when training)
        root.addView(workoutView.getStageView(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        updateTopTabs();
        refreshTopRight();
        return root;
    }

    private void switchTab(int tab) {
        if (pagePager != null) {
            pagePager.setCurrentPage(tab, true);
        }
    }

    private void onPageSettled(int tab) {
        if (currentTab == tab) return;
        currentTab = tab;
        if (tab == 1) {
            inboxView.refresh();
        } else {
            workoutView.refresh();
        }
        updateTopTabs();
        refreshTopRight();
    }

    /**
     * 无依赖双页跟手横向滑动容器：
     * - 顶部「左训练 · 右灵感」空间映射：训练页向左滑切到灵感，灵感页向右滑回到训练
     * - 屏幕左右各留 28dp 边缘热区不拦截，完全避让 vivo 全面屏边缘返回
     * - 在灵感页只拦截向右滑，绝不与单条灵感「左滑露删除」冲突
     * - 自动检测内部可横向滚动子视图（如热身标签行），优先让子视图滚动
     */
    private final class DualPagePager extends FrameLayout {
        private final View page0;
        private final View page1;
        private final int touchSlop;
        private final int edgeGuardPx;

        private int page = 0;
        private float downX;
        private float downY;
        private float dragOffset;
        private boolean edgeBlocked;
        private boolean dragging;
        private boolean verticalLocked;
        private VelocityTracker velocityTracker;
        private ValueAnimator settleAnim;

        DualPagePager(Context ctx, View page0, View page1) {
            super(ctx);
            this.page0 = page0;
            this.page1 = page1;
            this.touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
            this.edgeGuardPx = UiKit.dp(ctx, 28);

            addView(page0, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            addView(page1, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            page0.setVisibility(View.VISIBLE);
            page1.setVisibility(View.GONE);
        }

        void setCurrentPage(final int targetPage, boolean animate) {
            if (targetPage != 0 && targetPage != 1) return;
            if (settleAnim != null) {
                settleAnim.cancel();
                settleAnim = null;
            }
            if (page == targetPage && !dragging) {
                applyRestVisibility();
                return;
            }
            int w = getWidth();
            if (!animate || w <= 0) {
                page = targetPage;
                dragOffset = 0f;
                applyRestVisibility();
                onPageSettled(targetPage);
                return;
            }
            prepareBothVisible();
            float startOffset = dragOffset;
            float endOffset = (page == 0 && targetPage == 1) ? -w
                    : (page == 1 && targetPage == 0) ? w : 0f;
            animateSettle(startOffset, endOffset, targetPage, false);
        }

        private void prepareBothVisible() {
            if (page0.getVisibility() != View.VISIBLE) {
                page0.setVisibility(View.VISIBLE);
            }
            if (page1.getVisibility() != View.VISIBLE) {
                page1.setVisibility(View.VISIBLE);
            }
            page0.setAlpha(1f);
            page1.setAlpha(1f);
        }

        private void applyOffset(float offset) {
            dragOffset = offset;
            int w = getWidth();
            if (w <= 0) return;
            if (page == 0) {
                page0.setTranslationX(offset);
                page1.setTranslationX(w + offset);
            } else {
                page1.setTranslationX(offset);
                page0.setTranslationX(-w + offset);
            }
        }

        private void applyRestVisibility() {
            page0.setTranslationX(0f);
            page1.setTranslationX(0f);
            page0.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
            page1.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        }

        private void animateSettle(float fromOffset, float toOffset, final int targetPage, final boolean hapticOnChange) {
            if (settleAnim != null) {
                settleAnim.cancel();
                settleAnim = null;
            }
            final int prevPage = page;
            settleAnim = ValueAnimator.ofFloat(fromOffset, toOffset);
            settleAnim.setDuration(220);
            settleAnim.setInterpolator(new DecelerateInterpolator(2.0f));
            settleAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    applyOffset((Float) animation.getAnimatedValue());
                }
            });
            settleAnim.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    settleAnim = null;
                    if (cancelled) return;
                    page = targetPage;
                    dragOffset = 0f;
                    applyRestVisibility();
                    if (targetPage != prevPage) {
                        if (hapticOnChange) {
                            UiKit.hapticTick(DualPagePager.this);
                        }
                        onPageSettled(targetPage);
                    }
                }
            });
            settleAnim.start();
        }

        private boolean canChildScrollHorizontally(View v, boolean checkV, int dx, int x, int y) {
            if (v instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) v;
                int scrollX = v.getScrollX();
                int scrollY = v.getScrollY();
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    View child = group.getChildAt(i);
                    if (child.getVisibility() != View.VISIBLE) continue;
                    int tx = Math.round(child.getTranslationX());
                    int ty = Math.round(child.getTranslationY());
                    int cx = x + scrollX - tx;
                    int cy = y + scrollY - ty;
                    if (cx >= child.getLeft() && cx < child.getRight()
                            && cy >= child.getTop() && cy < child.getBottom()) {
                        if (canChildScrollHorizontally(child, true, dx,
                                cx - child.getLeft(), cy - child.getTop())) {
                            return true;
                        }
                    }
                }
            }
            return checkV && v.canScrollHorizontally(-dx);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (settleAnim != null) {
                settleAnim.cancel();
                settleAnim = null;
            }
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (workoutView != null && workoutView.isInStage()) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    dragging = false;
                    verticalLocked = false;
                    edgeBlocked = (downX < edgeGuardPx || downX > getWidth() - edgeGuardPx);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (edgeBlocked || verticalLocked) return false;
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
                        verticalLocked = true;
                        return false;
                    }
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.35f) {
                        boolean validDir = (page == 0 && dx < 0) || (page == 1 && dx > 0);
                        if (!validDir) return false;
                        if (canChildScrollHorizontally(this, false, (int) dx, (int) downX, (int) downY)) {
                            return false;
                        }
                        if (settleAnim != null) {
                            settleAnim.cancel();
                            settleAnim = null;
                        }
                        dragging = true;
                        downX = ev.getX();
                        if (inboxView != null && page == 1) {
                            inboxView.closeOpenedSwipeRow();
                        }
                        prepareBothVisible();
                        applyOffset(0f);
                        if (velocityTracker != null) {
                            velocityTracker.clear();
                        } else {
                            velocityTracker = VelocityTracker.obtain();
                        }
                        velocityTracker.addMovement(ev);
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (workoutView != null && workoutView.isInStage()) return false;
            if (velocityTracker == null) {
                velocityTracker = VelocityTracker.obtain();
            }
            velocityTracker.addMovement(ev);

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    edgeBlocked = (downX < edgeGuardPx || downX > getWidth() - edgeGuardPx);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) {
                        if (edgeBlocked) return false;
                        float dx = ev.getX() - downX;
                        float dy = ev.getY() - downY;
                        if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.35f) {
                            boolean validDir = (page == 0 && dx < 0) || (page == 1 && dx > 0);
                            if (validDir) {
                                dragging = true;
                                downX = ev.getX();
                                prepareBothVisible();
                            }
                        }
                    }
                    if (dragging) {
                        int w = getWidth();
                        float dx = ev.getX() - downX;
                        if (page == 0) {
                            float clamped = Math.max(-w, Math.min(0f, dx));
                            applyOffset(clamped);
                        } else {
                            float clamped = Math.max(0f, Math.min(w, dx));
                            applyOffset(clamped);
                        }
                        return true;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    velocityTracker.computeCurrentVelocity(1000);
                    float vx = velocityTracker.getXVelocity();
                    velocityTracker.recycle();
                    velocityTracker = null;

                    if (dragging) {
                        dragging = false;
                        int w = Math.max(1, getWidth());
                        int targetPage = page;
                        if (page == 0) {
                            if (vx < -500f || dragOffset < -w * 0.28f) {
                                targetPage = 1;
                            }
                            animateSettle(dragOffset, targetPage == 1 ? -w : 0f, targetPage, true);
                        } else {
                            if (vx > 500f || dragOffset > w * 0.28f) {
                                targetPage = 0;
                            }
                            animateSettle(dragOffset, targetPage == 0 ? w : 0f, targetPage, true);
                        }
                    }
                    return true;
            }
            return super.onTouchEvent(ev);
        }
    }

    private void updateTopTabs() {
        boolean trainOn = (currentTab == 0);
        tabTrain.setTextSize(trainOn ? 26 : 22);
        tabTrain.setTypeface(Typeface.DEFAULT_BOLD);
        tabTrain.setTextColor(trainOn ? UiKit.PAPER_INK : UiKit.PAPER_IDLE);

        tabInbox.setTextSize(!trainOn ? 26 : 22);
        tabInbox.setTypeface(Typeface.DEFAULT_BOLD);
        tabInbox.setTextColor(!trainOn ? UiKit.PAPER_INK : UiKit.PAPER_IDLE);
    }

    private void refreshTopRight() {
        if (topRightHost == null) return;
        topRightHost.removeAllViews();
        if (currentTab == 0) {
            double bw = Prefs.latestBodyWeight(this);
            LinearLayout bwRow = new LinearLayout(this);
            bwRow.setOrientation(LinearLayout.HORIZONTAL);
            bwRow.setGravity(Gravity.CENTER_VERTICAL);
            bwRow.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 6), 0, UiKit.dp(this, 6));

            View dot = new View(this);
            dot.setBackground(UiKit.roundRect(this, UiKit.CORAL_END, 999));
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    UiKit.dp(this, 6), UiKit.dp(this, 6));
            dLp.rightMargin = UiKit.dp(this, 6);
            bwRow.addView(dot, dLp);

            TextView tv = new TextView(this);
            tv.setText(bw > 0 ? (FitnessStore.formatKg(bw) + " kg") : "记体重");
            tv.setTextSize(13);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setTextColor(UiKit.PAPER_SECOND);
            tv.setFontFeatureSettings("tnum");
            bwRow.addView(tv);

            bwRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.hapticTick(v);
                    workoutView.openBodyWeightSheet();
                }
            });
            topRightHost.addView(bwRow);
        } else {
            FrameLayout gearWrap = new FrameLayout(this);
            gearWrap.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 6),
                    UiKit.dp(this, 2), UiKit.dp(this, 6));
            UiKit.MinimalGlyphView gear = new UiKit.MinimalGlyphView(
                    this, UiKit.MinimalGlyphView.GLYPH_GEAR, UiKit.PAPER_SECOND);
            gearWrap.addView(gear, new FrameLayout.LayoutParams(
                    UiKit.dp(this, 22), UiKit.dp(this, 22)));
            gearWrap.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    UiKit.hapticTick(v);
                    inboxView.openSettingsSheet();
                }
            });
            topRightHost.addView(gearWrap);
        }
    }

    private void applyDayNightSystemBars(boolean dark) {
        getWindow().setStatusBarColor(dark ? UiKit.DARK_BG : UiKit.PAPER_BG);
        getWindow().setNavigationBarColor(dark ? UiKit.DARK_BG : UiKit.PAPER_BG);
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
    }

    // ---- WorkoutView.Host ----
    @Override
    public void onNeedPickFolder() {
        pickFolder();
    }

    @Override
    public void onActiveStageChanged(boolean inStage) {
        applyDayNightSystemBars(inStage);
        if (!inStage) {
            dayShell.setVisibility(View.VISIBLE);
            refreshTopRight();
        } else {
            dayShell.setVisibility(View.GONE);
        }
    }

    @Override
    public void onBodyWeightUpdated() {
        refreshTopRight();
    }

    // ---- InboxView.Host ----
    @Override
    public boolean isAccessibilityEnabled() {
        try {
            AccessibilityManager manager =
                    (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager == null) return false;
            List<AccessibilityServiceInfo> services =
                    manager.getEnabledAccessibilityServiceList(
                            AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (services == null) return false;
            for (AccessibilityServiceInfo info : services) {
                ResolveInfo resolveInfo = info.getResolveInfo();
                if (resolveInfo != null && resolveInfo.serviceInfo != null
                        && getPackageName().equals(resolveInfo.serviceInfo.packageName)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @Override
    public boolean isOldInstalled() {
        try {
            getPackageManager().getPackageInfo(OLD_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException exc) {
            return false;
        } catch (Exception exc) {
            return false;
        }
    }

    @Override
    public void onOpenOverlaySettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException exc) {
            Toast.makeText(this, "系统没有悬浮窗设置页", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onOpenAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "找到「灵感」并开启", Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException exc) {
            Toast.makeText(this, "系统没有无障碍设置页", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onOpenBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            Toast.makeText(this, "将「灵感」设为允许后台高耗电 / 不优化", Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException exc) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
                Toast.makeText(this, "请到系统设置 → 应用 → 灵感 中开启后台高耗电", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 若用户通过 ADB 授予了 WRITE_SECURE_SETTINGS，则在掉线时自动静默重新开启无障碍。 */
    private void tryAutoRestoreAccessibility() {
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (isAccessibilityEnabled()) {
            return;
        }
        try {
            String component = new ComponentName(this, CaptureAccessibilityService.class)
                    .flattenToString();
            String current = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String next = (current == null || current.trim().isEmpty())
                    ? component
                    : (current.contains(component) ? current : current + ":" + component);
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Toast.makeText(this, "已自动恢复无障碍服务", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onPickFolder() {
        pickFolder();
    }

    @Override
    public void onTestPopup() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show();
            return;
        }
        PopupController.show(this, SAMPLE, true);
    }

    @Override
    public void onCleanupOldVersion() {
        if (isOldInstalled()) {
            Intent delete = new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + OLD_PACKAGE));
            try {
                startActivity(delete);
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + OLD_PACKAGE)));
        } catch (ActivityNotFoundException exc) {
            Toast.makeText(this, "旧版已不在；若无障碍列表还有残留，重启一次手机",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_TREE);
        } catch (ActivityNotFoundException exc) {
            Toast.makeText(this, "系统文件选择器不可用", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) return;
        int flags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
            Toast.makeText(this, "所选目录没有写入权限，请重新选择 00-Inbox", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException exc) {
            Toast.makeText(this, "无法持久化目录权限，请重新授权", Toast.LENGTH_LONG).show();
            return;
        }
        Prefs.setTreeUri(this, uri.toString());
        if (inboxView != null) inboxView.refresh();
        Toast.makeText(this, "00-Inbox 已连接", Toast.LENGTH_SHORT).show();
    }
}
