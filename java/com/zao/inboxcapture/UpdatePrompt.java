package com.zao.inboxcapture;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class UpdatePrompt {
    private UpdatePrompt() {}

    static void show(Activity owner, String current, String available, Runnable cancel, Runnable install) {
        Dialog dialog = new Dialog(owner);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setTitle("发现新版本");
        LinearLayout panel = new LinearLayout(owner);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = UiKit.dp(owner, 22);
        panel.setPadding(padding, padding, padding, padding);
        panel.setBackground(UiKit.roundRect(owner, UiKit.PAPER_SHEET, 20));
        TextView heading = new TextView(owner);
        heading.setText("发现新版本");
        heading.setContentDescription("发现新版本");
        heading.setAccessibilityHeading(true);
        heading.setTypeface(Typeface.create("serif", Typeface.BOLD));
        heading.setTextColor(UiKit.PAPER_INK);
        heading.setTextSize(22);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setCompoundDrawablesWithIntrinsicBounds(owner.getResources().getIdentifier(
                "setting_update", "drawable", owner.getPackageName()), 0, 0, 0);
        heading.setCompoundDrawablePadding(UiKit.dp(owner, 10));
        panel.addView(heading);
        TextView versions = new TextView(owner);
        versions.setText(current + "  →  " + available);
        versions.setTextSize(16);
        versions.setTextColor(UiKit.PAPER_SECOND);
        versions.setPadding(0, UiKit.dp(owner, 10), 0, UiKit.dp(owner, 22));
        panel.addView(versions);
        LinearLayout actions = new LinearLayout(owner);
        Button later = button(owner, "取消", false);
        later.setOnClickListener(v -> dialog.cancel());
        Button confirm = button(owner, "确认更新", true);
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            install.run();
        });
        actions.addView(later, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams actionSize = new LinearLayout.LayoutParams(0, -2, 1);
        actionSize.leftMargin = UiKit.dp(owner, 10);
        actions.addView(confirm, actionSize);
        panel.addView(actions);
        dialog.setContentView(panel);
        dialog.setOnCancelListener(d -> cancel.run());
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screen = owner.getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min(UiKit.dp(owner, 400), screen - UiKit.dp(owner, 40)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private static Button button(Activity owner, String label, boolean primary) {
        Button button = new Button(owner);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(primary ? Color.WHITE : UiKit.PAPER_INK);
        button.setStateListAnimator(null);
        button.setMinHeight(UiKit.dp(owner, 48));
        button.setMinimumWidth(0);
        button.setBackgroundTintList(null);
        button.setBackground(UiKit.rippleWrap(UiKit.roundRect(owner,
                primary ? UiKit.CORAL_ACCENT : UiKit.PAPER_MUTED_BG, 8), 0x22000000));
        return button;
    }
}
