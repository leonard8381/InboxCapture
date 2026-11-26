package com.zao.inboxcapture;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity implements InboxView.Host {
    private static final int REQ_TREE = 42;
    private static final String SAMPLE =
            "小红书 用 AI 把老照片做成温暖微缩 3D 小房间，完整节点拆解 http://xhslink.com/a/AbCdEf";
    private static final String OLD_PACKAGE = "com.zao.inboxcapture";

    private InboxView inboxView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        getWindow().setStatusBarColor(UiKit.PAPER_BG);
        getWindow().setNavigationBarColor(UiKit.PAPER_BG);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    @Override
    protected void onResume() {
        super.onResume();
        tryAutoRestoreAccessibility();
        inboxView.refresh();
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.PAPER_BG);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 14),
                UiKit.dp(this, 22), UiKit.dp(this, 4));

        TextView title = new TextView(this);
        title.setText("灵感");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(26);
        title.setTextColor(UiKit.PAPER_INK);
        topBar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout settingsButton = new FrameLayout(this);
        settingsButton.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 6),
                UiKit.dp(this, 2), UiKit.dp(this, 6));
        settingsButton.setContentDescription("收纳设置");
        settingsButton.addView(new UiKit.MinimalGlyphView(
                this, UiKit.MinimalGlyphView.GLYPH_GEAR, UiKit.PAPER_SECOND),
                new FrameLayout.LayoutParams(UiKit.dp(this, 22), UiKit.dp(this, 22)));
        settingsButton.setOnClickListener(v -> {
            UiKit.hapticTick(v);
            inboxView.openSettingsSheet();
        });
        topBar.addView(settingsButton);
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        inboxView = new InboxView(this, this);
        root.addView(inboxView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

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
