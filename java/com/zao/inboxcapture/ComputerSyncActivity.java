package com.zao.inboxcapture;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local UI for the private Syncthing process. All REST and directory operations run off the UI. */
public final class ComputerSyncActivity extends Activity {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final String PAIRING_HINT = "配对时需和电脑在同一个局域网";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::refresh;

    private LinearLayout body;
    private SyncPairing.State state;
    private boolean visible, reading, working, expanded;
    private String lastDisplay = "", draftId = "";
    private long enteredAt;
    private boolean waitingForAccess;
    private String pendingPeer = "", pendingFolder = "";
    private String lastFailure = "";

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            draftId = saved.getString("id", "");
            expanded = saved.getBoolean("expanded");
            waitingForAccess = saved.getBoolean("waiting");
            pendingPeer = saved.getString("offerPeer", "");
            pendingFolder = saved.getString("offerFolder", "");
        }
        getWindow().setStatusBarColor(UiKit.PAPER_BG);
        getWindow().setNavigationBarColor(UiKit.PAPER_BG);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout page = vertical();
        page.setBackgroundColor(UiKit.PAPER_BG);
        LinearLayout bar = horizontal();
        bar.setPadding(dp(6), dp(4), dp(20), dp(6));
        TextView back = text("‹", 32, UiKit.PAPER_INK);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        clickable(back, this::finish);
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        TextView heading = text("连接电脑", 25, UiKit.PAPER_INK);
        heading.setTypeface(Typeface.create("serif", Typeface.BOLD));
        heading.setAccessibilityHeading(true);
        bar.addView(heading, weight());
        TextView more = text("⋮", 24, UiKit.PAPER_SECOND);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("更多");
        clickable(more, () -> {
            PopupMenu menu = new PopupMenu(this, more);
            if (state != null && !state.folder.isEmpty()) {
                menu.getMenu().add("解除连接").setOnMenuItemClickListener(item -> {
                    String peer = state.peer, folder = state.folder;
                    new AlertDialog.Builder(this).setTitle("解除与电脑的连接？")
                            .setMessage("停止同步，手机和电脑上已有的文件会保留。")
                            .setNegativeButton("取消", null).setPositiveButton("解除连接", (d, w) ->
                                    perform(() -> SyncPairing.disconnect(app(), peer, folder))).show();
                    return true;
                });
            }
            menu.getMenu().add("开源许可").setOnMenuItemClickListener(item -> {
                showLicense();
                return true;
            });
            menu.show();
        });
        bar.addView(more, new LinearLayout.LayoutParams(dp(42), dp(48)));
        page.addView(bar);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        body = vertical();
        body.setFocusableInTouchMode(true);
        body.setPadding(dp(20), dp(5), dp(20), dp(24));
        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        setContentView(page);
        body.addView(text("正在启动同步服务…", 15, UiKit.PAPER_SECOND));
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("id", draftId);
        out.putBoolean("expanded", expanded);
        out.putBoolean("waiting", waitingForAccess);
        out.putString("offerPeer", pendingPeer);
        out.putString("offerFolder", pendingFolder);
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        enteredAt = SystemClock.elapsedRealtime();
        SyncService.enter(this);
        lastDisplay = "";
        refresh();
    }

    @Override protected void onPause() {
        visible = false;
        handler.removeCallbacks(poll);
        SyncService.leave(this);
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] result) {
        super.onRequestPermissionsResult(code, permissions, result);
        if (visible) refresh();
    }

    private void refresh() {
        handler.removeCallbacks(poll);
        if (!visible || reading || working) return;
        reading = true;
        WORK.execute(() -> {
            try {
                SyncPairing.State fresh = SyncPairing.read(getApplicationContext());
                handler.post(() -> {
                    reading = false;
                    if (!visible || working) return;
                    state = fresh;
                    lastFailure = "";
                    renderIfChanged();
                    continueAccessIfGranted();
                    schedule();
                });
            } catch (Exception err) {
                handler.post(() -> {
                    reading = false;
                    if (!visible || working) return;
                    String reason = SyncEngine.failure();
                    boolean starting = reason.isEmpty() && SystemClock.elapsedRealtime() - enteredAt < 15000;
                    String message = starting ? "正在启动同步服务…" :
                            (reason.isEmpty() ? "暂时无法读取同步服务" : reason);
                    if (!message.equals(lastFailure) || !lastDisplay.equals("error")) {
                        lastFailure = message;
                        lastDisplay = "error";
                        body.removeAllViews();
                        line(message, UiKit.PAPER_SECOND);
                        if (!starting) action("重试", () -> {
                            enteredAt = SystemClock.elapsedRealtime();
                            SyncService.enter(this);
                            refresh();
                        });
                    }
                    schedule();
                });
            }
        });
    }

    private void schedule() {
        handler.removeCallbacks(poll);
        if (visible && !working) handler.postDelayed(poll, state == null ? 1000 : 3500);
    }

    private void renderIfChanged() {
        String key = state.displayKey() + "|" + expanded + "|" + SyncService.background(this);
        if (key.equals(lastDisplay)) return;
        lastDisplay = key;
        body.removeAllViews();
        boolean paired = !state.peer.isEmpty();
        if (paired) {
            LinearLayout computer = horizontal();
            computer.setMinimumHeight(dp(52));
            computer.addView(text(state.label, 18, UiKit.PAPER_INK), weight());
            String status = (state.connected ? "● " : "") + state.status();
            computer.addView(text(status, 14, state.connected ? UiKit.OK_GREEN : UiKit.PAPER_SECOND));
            body.addView(computer);
            divider(body);
        }
        deviceId("本设备 ID", state.local);
        if (paired) {
            deviceId("电脑 ID", state.peer);
            if (!state.previouslyConnected && state.folder.isEmpty()) line(PAIRING_HINT, UiKit.PAPER_SECOND);
            if (state.folder.isEmpty()) {
                line(state.connected ? "请在电脑上将现有的 test1 文件夹共享给本设备。"
                        : "在电脑的 Syncthing 中接受本设备，再共享现有的 test1 文件夹。", UiKit.PAPER_SECOND);
                for (SyncPairing.Offer offer : state.offers) {
                    String name = offer.label.isEmpty() ? offer.id : offer.label;
                    String peer = state.peer;
                    action("接受共享 · " + name, () -> acceptOffer(peer, offer.id));
                }
                if (!state.connected) action("取消此次配对", this::cancelPairing);
            }
            if (!state.problem.isEmpty()) line(state.problem, UiKit.WARN_AMBER);
        } else {
            connectionForm();
        }
        spacer(15);
        modeChooser();
        spacer(12);
        backgroundSwitch();
    }

    private void deviceId(String label, String id) {
        LinearLayout row = horizontal();
        row.setPadding(0, dp(8), 0, dp(8));
        LinearLayout value = vertical();
        value.addView(text(label, 15, UiKit.PAPER_INK));
        TextView abbreviated = text(shortId(id), 16, UiKit.PAPER_INK);
        abbreviated.setTypeface(Typeface.MONOSPACE);
        value.addView(abbreviated);
        row.addView(value, weight());
        TextView copy = text("复制", 14, UiKit.CORAL_ACCENT);
        copy.setGravity(Gravity.CENTER);
        copy.setContentDescription("复制完整" + label);
        clickable(copy, () -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText(label, id));
                Toast.makeText(this, "已复制完整 ID", Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(copy, new LinearLayout.LayoutParams(dp(48), dp(48)));
        body.addView(row);
        divider(body);
    }

    private static String shortId(String id) {
        return id.length() < 22 ? id : id.substring(0, 7) + " … " + id.substring(id.length() - 7);
    }

    private void connectionForm() {
        spacer(12);
        line("电脑 ID", UiKit.PAPER_INK);
        EditText id = input(draftId, "粘贴完整ID");
        body.addView(id);
        line(PAIRING_HINT, UiKit.PAPER_SECOND);
        Button submit = new Button(this);
        submit.setText("连接电脑");
        submit.setTextSize(16);
        submit.setTextColor(android.graphics.Color.WHITE);
        submit.setAllCaps(false);
        submit.setMinHeight(dp(46));
        submit.setBackgroundTintList(null);
        Runnable update = () -> {
            draftId = id.getText().toString();
            boolean ready = !draftId.trim().isEmpty();
            submit.setEnabled(ready);
            submit.setBackground(UiKit.roundRect(this, ready ? UiKit.CORAL_ACCENT : 0xFFAEA9A0, 9));
        };
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            @Override public void afterTextChanged(Editable value) {}
        };
        id.addTextChangedListener(watcher);
        update.run();
        submit.setOnClickListener(v -> {
            InputMethodManager ime = getSystemService(InputMethodManager.class);
            if (ime != null) ime.hideSoftInputFromWindow(submit.getWindowToken(), 0);
            String peer = draftId;
            perform(() -> SyncPairing.configureDevice(app(), peer));
        });
        spacer(12);
        body.addView(submit);
    }

    private void cancelPairing() {
        String peer = state.peer;
        new AlertDialog.Builder(this).setTitle("取消此次配对？")
                .setNegativeButton("返回", null).setPositiveButton("取消配对", (d, w) ->
                        perform(() -> SyncPairing.cancelPending(app(), peer))).show();
    }

    private void acceptOffer(String peer, String folderId) {
        if (Prefs.treeUri(this).isEmpty()) {
            Toast.makeText(this, "请返回收纳设置，选择手机的 test 文件夹", Toast.LENGTH_LONG).show();
            return;
        }
        if (SyncStorage.needsAllFilesAccess(this)) {
            new AlertDialog.Builder(this).setTitle("允许同步收纳目录")
                    .setMessage("同步引擎需要系统文件访问权限，才能读写你选定的 test 文件夹。")
                    .setNegativeButton("取消", null).setPositiveButton("去授权", (d, w) -> {
                        pendingPeer = peer;
                        pendingFolder = folderId;
                        waitingForAccess = true;
                        try {
                            SyncStorage.requestAccess(this);
                        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
                            waitingForAccess = false;
                            Toast.makeText(this, "请在系统设置中允许「灵感」访问所有文件", Toast.LENGTH_LONG).show();
                        }
                    }).show();
            return;
        }
        confirmAccept(peer, folderId);
    }

    private void continueAccessIfGranted() {
        if (!waitingForAccess || SyncStorage.needsAllFilesAccess(this)) return;
        waitingForAccess = false;
        if (!state.peer.equals(pendingPeer) || !state.folder.isEmpty()) return;
        for (SyncPairing.Offer offer : state.offers) {
            if (offer.id.equals(pendingFolder)) {
                confirmAccept(pendingPeer, pendingFolder);
                break;
            }
        }
    }

    private void confirmAccept(String peer, String folderId) {
        String label = folderId;
        for (SyncPairing.Offer offer : state.offers) {
            if (offer.id.equals(folderId) && !offer.label.isEmpty()) label = offer.label + "（" + folderId + "）";
        }
        new AlertDialog.Builder(this).setTitle("接管这个共享文件夹？")
                .setMessage("电脑共享：" + label + "\n手机目录：test\n\n如果另一个 Syncthing 正在同步 test，请先暂停其中的这个文件夹。")
                .setNegativeButton("取消", null).setPositiveButton("确认接管", (d, w) ->
                        perform(() -> SyncPairing.accept(app(), peer, folderId))).show();
    }

    private void modeChooser() {
        LinearLayout group = vertical();
        group.setBackground(UiKit.roundRect(this, UiKit.PAPER_MUTED_BG, 8));
        body.addView(group);
        LinearLayout top = horizontal();
        top.setPadding(dp(12), 0, dp(12), 0);
        top.setMinimumHeight(dp(48));
        top.addView(text("⇄  同步方式", 16, UiKit.PAPER_INK), weight());
        top.addView(text(modeLabel(state.mode) + (expanded ? "  ⌄" : "  ›"), 15, UiKit.PAPER_SECOND));
        clickable(top, () -> { expanded = !expanded; renderIfChanged(); });
        group.addView(top);
        if (!expanded) return;
        option(group, SyncPairing.READ, "电脑 → 手机");
        option(group, SyncPairing.WRITE, "手机 → 电脑");
        option(group, SyncPairing.BOTH, "手机 ↔ 电脑");
    }

    private void option(LinearLayout group, String mode, String direction) {
        divider(group);
        LinearLayout row = horizontal();
        row.setPadding(dp(14), dp(5), dp(8), dp(5));
        row.setMinimumHeight(dp(50));
        LinearLayout copy = vertical();
        copy.addView(text(modeLabel(mode), 16, UiKit.PAPER_INK));
        copy.addView(text(direction, 13, UiKit.PAPER_SECOND));
        row.addView(copy, weight());
        RadioButton radio = new RadioButton(this);
        radio.setButtonTintList(checkColors());
        boolean selected = mode.equals(state.mode);
        radio.setChecked(selected);
        radio.setClickable(false);
        radio.setFocusable(false);
        row.addView(radio);
        row.setContentDescription(modeLabel(mode) + "，" + direction + (selected ? "，已选择" : ""));
        clickable(row, () -> {
            if (selected) { expanded = false; renderIfChanged(); return; }
            perform(() -> SyncPairing.setMode(app(), mode));
        });
        group.addView(row);
    }

    private static String modeLabel(String mode) {
        if (SyncPairing.READ.equals(mode)) return "只读";
        if (SyncPairing.WRITE.equals(mode)) return "只写";
        return "读+写";
    }

    private void backgroundSwitch() {
        LinearLayout row = horizontal();
        row.setMinimumHeight(dp(60));
        LinearLayout copy = vertical();
        copy.addView(text("后台同步", 16, UiKit.PAPER_INK));
        if (SyncService.background(this)) copy.addView(SyncPowerSettings.link(this));
        row.addView(copy, weight());
        Switch toggle = new Switch(this);
        toggle.setContentDescription("后台同步");
        toggle.setThumbTintList(checkColors());
        toggle.setChecked(SyncService.background(this));
        toggle.setOnCheckedChangeListener((btn, enabled) -> {
            SyncService.setBackground(this, enabled);
            renderIfChanged();
        });
        row.addView(toggle);
        row.setOnClickListener(v -> toggle.toggle());
        body.addView(row);
    }

    private android.content.res.ColorStateList checkColors() {
        return new android.content.res.ColorStateList(
                new int[][] {new int[] { android.R.attr.state_checked }, new int[0]},
                new int[] {UiKit.OK_GREEN, UiKit.PAPER_MUTED});
    }

    private interface Operation { void run() throws Exception; }

    private void perform(Operation op) {
        if (working) return;
        working = true;
        handler.removeCallbacks(poll);
        WORK.execute(() -> {
            String error = "";
            try { op.run(); }
            catch (Exception e) {
                String reason = e.getMessage();
                error = reason != null && reason.matches("(?s).*[一-鿿].*") ? reason : "操作未完成，请检查同步服务后重试";
            }
            final String result = error;
            handler.post(() -> {
                working = false;
                if (!visible) return;
                if (!result.isEmpty()) new AlertDialog.Builder(this)
                        .setMessage(result).setPositiveButton("好", null).show();
                lastDisplay = "";
                refresh();
            });
        });
    }

    private Context app() { return getApplicationContext(); }
    private int dp(int n) { return UiKit.dp(this, n); }
    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, WRAP, 1f); }
    private TextView text(String value, int sp, int color) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(sp);
        label.setTextColor(color);
        return label;
    }
    private void line(String value, int color) {
        TextView label = text(value, 14, color);
        label.setPadding(0, dp(6), 0, dp(6));
        body.addView(label);
    }
    private void action(String value, Runnable run) {
        TextView button = text(value, 15, UiKit.CORAL_ACCENT);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinimumHeight(dp(44));
        clickable(button, run);
        body.addView(button);
    }
    private void clickable(View view, Runnable run) {
        view.setFocusable(true);
        view.setOnClickListener(v -> { UiKit.hapticTick(view); run.run(); });
    }
    private void spacer(int height) { body.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(UiKit.PAPER_DIVIDER);
        parent.addView(line, new LinearLayout.LayoutParams(MATCH, 1));
    }
    private EditText input(String value, String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setTextSize(15);
        field.setTextColor(UiKit.PAPER_INK);
        field.setHintTextColor(UiKit.PAPER_SECOND);
        field.setText(value);
        field.setHint(hint);
        field.setPadding(dp(12), dp(5), dp(12), dp(5));
        field.setMinimumHeight(dp(46));
        field.setBackground(UiKit.roundRectBordered(this, UiKit.PAPER_SHEET, 8, UiKit.PAPER_DIVIDER, 1));
        return field;
    }
    private void showLicense() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                getAssets().open("syncthing/NOTICE.txt"), StandardCharsets.UTF_8))) {
            StringBuilder notice = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) notice.append(line).append('\n');
            TextView copy = text(notice.toString(), 13, UiKit.PAPER_INK);
            copy.setPadding(dp(20), dp(12), dp(20), dp(12));
            copy.setTextIsSelectable(true);
            android.text.util.Linkify.addLinks(copy, android.text.util.Linkify.WEB_URLS);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(copy);
            new AlertDialog.Builder(this).setTitle("开源许可").setView(scroll).setPositiveButton("完成", null).show();
        } catch (IOException e) {
            Toast.makeText(this, "无法读取许可文件", Toast.LENGTH_SHORT).show();
        }
    }
}
