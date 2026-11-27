package com.zao.inboxcapture;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

final class SyncPowerSettings {
    private SyncPowerSettings() {}

    static TextView link(Activity activity) {
        return new PowerLink(activity);
    }

    static boolean isExempt(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    static void open(Activity activity) {
        Uri app = Uri.fromParts("package", activity.getPackageName(), null);
        boolean canRequest = activity.checkSelfPermission(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                == PackageManager.PERMISSION_GRANTED;
        if (!isExempt(activity) && canRequest
                && launch(activity, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app))) {
            return;
        }
        if (launch(activity, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            Toast.makeText(activity, "在列表中选择「灵感」", Toast.LENGTH_SHORT).show();
            return;
        }
        if (launch(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))) {
            Toast.makeText(activity, "在「电池」中调整后台耗电设置", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity, "请在系统设置中打开「灵感」的电池设置", Toast.LENGTH_LONG).show();
    }

    private static boolean launch(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }

    private static final class PowerLink extends TextView {
        PowerLink(Activity activity) {
            super(activity);
            setTextSize(14);
            setMinHeight(UiKit.dp(activity, 48));
            setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true);
            setPaintFlags(getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            setOnClickListener(v -> {
                UiKit.hapticTick(v);
                open(activity);
            });
            refresh();
        }

        @Override public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            if (hasFocus) refresh();
        }

        private void refresh() {
            boolean exempt = isExempt(getContext());
            String label = exempt ? "已关闭系统电池优化" : "关闭省电限制";
            setText(label);
            setTextColor(exempt ? UiKit.OK_GREEN : UiKit.CORAL_ACCENT);
            setContentDescription(label + "，打开系统电池设置");
        }
    }
}
