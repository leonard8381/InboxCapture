package com.zao.inboxcapture;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 灵感 v0.8 昼夜双境设计系统（无描边去框化）：
 * - 日常态：纸感暖白 #F6F4EF + 纯白柔影卡 #FFFFFF（零描边）
 * - 训练态：深邃暗室 #0B0B0D + 中心聚光 + 原位变形液体休息胶囊
 */
public final class UiKit {
    // ---- Editorial Day Palette ----
    public static final int PAPER_BG       = 0xFFF6F4EF;
    public static final int PAPER_SHEET    = 0xFFFFFFFF;
    public static final int PAPER_MUTED_BG = 0xFFF2EFE8;
    public static final int PAPER_DIVIDER  = 0xFFF2EFEA;
    public static final int PAPER_INK      = 0xFF151514;
    public static final int PAPER_SECOND   = 0xFF6E6B64;
    public static final int PAPER_MUTED    = 0xFF9C988F;
    public static final int PAPER_IDLE     = 0xFFC5C1B8;

    // ---- Darkroom Focus Palette ----
    public static final int DARK_BG        = 0xFF0B0B0D;
    public static final int DARK_SHEET     = 0xFF16161A;
    public static final int DARK_PILL_BG   = 0xFF19191D;
    public static final int DARK_INPUT_BG  = 0xFF202026;
    public static final int DARK_INK       = 0xFFF7F5F0;
    public static final int DARK_SECOND    = 0xFFA6A39C;
    public static final int DARK_MUTED     = 0xFF6E6D68;
    public static final int DARK_FADED     = 0xFF4E4D4A;

    // ---- Warm Coral Accent ----
    public static final int CORAL_START    = 0xFFFF9442;
    public static final int CORAL_END      = 0xFFF05A39;
    public static final int CORAL_ACCENT   = 0xFFEE5D36;
    public static final int CORAL_DARK_TXT = 0xFFFF8E53;

    // ---- Semantic Dots ----
    public static final int OK_GREEN       = 0xFF1FA971;
    public static final int OK_GREEN_DARK  = 0xFF36D399;
    public static final int WARN_AMBER     = 0xFFD97706;
    public static final int DANGER_RED     = 0xFFD94A3D;

    public static final int DOT_XHS        = 0xFFF03E58;
    public static final int DOT_DY         = 0xFF23252A;
    public static final int DOT_WX         = 0xFF1AAD68;

    private UiKit() {}

    public static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    public static float dpf(Context ctx, float value) {
        return value * ctx.getResources().getDisplayMetrics().density;
    }

    public static GradientDrawable roundRect(Context ctx, int fillColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dpf(ctx, radiusDp));
        return d;
    }

    public static GradientDrawable roundRectBordered(Context ctx, int fillColor, float radiusDp,
                                                     int strokeColor, float strokeDp) {
        GradientDrawable d = roundRect(ctx, fillColor, radiusDp);
        if (strokeDp > 0 && strokeColor != 0) {
            d.setStroke(Math.max(1, Math.round(dpf(ctx, strokeDp))), strokeColor);
        }
        return d;
    }

    public static GradientDrawable topRoundSheet(Context ctx, int fillColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        float r = dpf(ctx, radiusDp);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return d;
    }

    public static GradientDrawable coralPillGradient(Context ctx, float radiusDp) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{CORAL_START, CORAL_END});
        d.setCornerRadius(dpf(ctx, radiusDp));
        return d;
    }

    public static Drawable rippleWrap(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, content);
    }

    public static void applySoftShadow(View v, int elevationDp, int spotAlphaColor) {
        v.setElevation(dpf(v.getContext(), elevationDp));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            v.setOutlineAmbientShadowColor(0x0A000000);
            v.setOutlineSpotShadowColor(spotAlphaColor);
        }
    }

    public static void attachSpringPress(final View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.965f).scaleY(0.965f)
                            .setDuration(90)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(200)
                            .setInterpolator(new OvershootInterpolator(1.5f))
                            .start();
                }
                return false;
            }
        });
    }

    public static void hapticTick(View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    public static void hapticConfirm(Context context, View view) {
        if (view != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            return;
        }
        try {
            Vibrator vib = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createOneShot(22, 140));
            }
        } catch (Exception ignored) {
        }
    }

    public static void vibrateRestDone(Context context) {
        try {
            Vibrator vib = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createWaveform(
                        new long[]{0, 60, 70, 95}, new int[]{0, 180, 0, 230}, -1));
            }
        } catch (Exception ignored) {
        }
    }

    public static TextView makePrimaryPill(Context ctx, String text) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(16);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(Color.WHITE);
        btn.setBackground(rippleWrap(coralPillGradient(ctx, 999), 0x26FFFFFF));
        applySoftShadow(btn, 10, 0x66F05A39);
        btn.setMinHeight(dp(ctx, 56));
        attachSpringPress(btn);
        return btn;
    }

    // =========================================================================
    // 1. DarkroomSpotlightView: 暗室中心柔光（纯深空底 + 中心偏上微弱暖光）
    // =========================================================================
    public static final class DarkroomSpotlightView extends View {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float glowAlpha = 1f;

        public DarkroomSpotlightView(Context context) {
            super(context);
        }

        public void setDimmed(boolean dimmed) {
            this.glowAlpha = dimmed ? 0.45f : 1f;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w <= 0 || h <= 0) return;
            glow.setShader(new RadialGradient(
                    w * 0.5f, h * 0.36f, w * 0.62f,
                    new int[]{0x26F2643D, 0x0AF2643D, 0x000B0B0D},
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(DARK_BG);
            glow.setAlpha((int) (255 * glowAlpha));
            canvas.drawRect(0, 0, getWidth(), getHeight(), glow);
        }
    }

    // =========================================================================
    // 2. LiquidRestBarView: 完成按钮原位变形的液体休息倒计时胶囊
    // =========================================================================
    public static final class LiquidRestBarView extends FrameLayout {
        private final LiquidFillCanvas liquidCanvas;
        private final TextView clockText;
        private final TextView plus30Btn;
        private final TextView skipBtn;

        public LiquidRestBarView(Context ctx) {
            super(ctx);
            setClipToOutline(true);
            setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dpf(ctx, 999));
                }
            });
            setBackground(roundRect(ctx, DARK_PILL_BG, 999));

            liquidCanvas = new LiquidFillCanvas(ctx);
            addView(liquidCanvas, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(ctx, 22), 0, dp(ctx, 10), 0);

            TextView tag = new TextView(ctx);
            tag.setText("休息");
            tag.setTextSize(13);
            tag.setTypeface(Typeface.DEFAULT_BOLD);
            tag.setTextColor(DARK_SECOND);
            row.addView(tag);

            clockText = new TextView(ctx);
            clockText.setText("01:30");
            clockText.setTextSize(22);
            clockText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            clockText.setTextColor(Color.WHITE);
            clockText.setFontFeatureSettings("tnum");
            android.widget.LinearLayout.LayoutParams cLp =
                    new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cLp.leftMargin = dp(ctx, 8);
            row.addView(clockText, cLp);

            plus30Btn = new TextView(ctx);
            plus30Btn.setText("+30s");
            plus30Btn.setTextSize(13);
            plus30Btn.setTypeface(Typeface.DEFAULT_BOLD);
            plus30Btn.setTextColor(0xFFC8C5BE);
            plus30Btn.setPadding(dp(ctx, 13), dp(ctx, 9), dp(ctx, 13), dp(ctx, 9));
            plus30Btn.setBackground(rippleWrap(roundRect(ctx, 0x12FFFFFF, 999), 0x26FFFFFF));
            attachSpringPress(plus30Btn);
            row.addView(plus30Btn);

            skipBtn = new TextView(ctx);
            skipBtn.setText("下一组 ›");
            skipBtn.setTextSize(13);
            skipBtn.setTypeface(Typeface.DEFAULT_BOLD);
            skipBtn.setTextColor(CORAL_DARK_TXT);
            skipBtn.setPadding(dp(ctx, 15), dp(ctx, 9), dp(ctx, 15), dp(ctx, 9));
            skipBtn.setBackground(rippleWrap(roundRect(ctx, 0x24FF8E53, 999), 0x33FF8E53));
            attachSpringPress(skipBtn);
            android.widget.LinearLayout.LayoutParams sLp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.leftMargin = dp(ctx, 8);
            row.addView(skipBtn, sLp);

            addView(row, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        public void setState(String formattedClock, float progress) {
            clockText.setText(formattedClock);
            liquidCanvas.setProgress(progress);
        }

        public void setOnPlus30Listener(OnClickListener l) {
            plus30Btn.setOnClickListener(l);
        }

        public void setOnSkipListener(OnClickListener l) {
            skipBtn.setOnClickListener(l);
        }

        public void setOnClockClickListener(OnClickListener l) {
            clockText.setOnClickListener(l);
        }

        private static final class LiquidFillCanvas extends View {
            private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private float progress = 1f;

            LiquidFillCanvas(Context ctx) {
                super(ctx);
            }

            void setProgress(float p) {
                this.progress = Math.max(0f, Math.min(1f, p));
                invalidate();
            }

            @Override
            protected void onDraw(Canvas canvas) {
                int w = getWidth();
                int h = getHeight();
                if (w <= 0 || h <= 0 || progress <= 0.005f) return;
                float fillW = Math.max(h * 0.5f, w * progress);
                fillPaint.setShader(new LinearGradient(
                        0, 0, fillW, 0,
                        new int[]{0x5CF25F3A, 0x3DFF9448, 0x00FF9448},
                        new float[]{0f, 0.80f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, fillW, h, fillPaint);
            }
        }
    }

    // =========================================================================
    // 3. MinimalGlyphView: 极简自定义矢量图标（播放三角 / 齿轮）
    // =========================================================================
    public static final class MinimalGlyphView extends View {
        public static final int GLYPH_PLAY = 1;
        public static final int GLYPH_GEAR = 2;

        private final int glyph;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int color;

        public MinimalGlyphView(Context ctx, int glyph, int color) {
            super(ctx);
            this.glyph = glyph;
            this.color = color;
            paint.setColor(color);
        }

        public void setGlyphColor(int color) {
            this.color = color;
            paint.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            path.reset();
            if (glyph == GLYPH_PLAY) {
                paint.setStyle(Paint.Style.FILL);
                path.moveTo(w * 0.30f, h * 0.20f);
                path.lineTo(w * 0.82f, h * 0.50f);
                path.lineTo(w * 0.30f, h * 0.80f);
                path.close();
                canvas.drawPath(path, paint);
            } else if (glyph == GLYPH_GEAR) {
                float cx = w * 0.5f;
                float cy = h * 0.5f;
                float rOuter = Math.min(w, h) * 0.34f;
                float rInner = Math.min(w, h) * 0.14f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpf(getContext(), 1.7f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawCircle(cx, cy, rInner, paint);
                canvas.drawCircle(cx, cy, rOuter * 0.78f, paint);
                for (int i = 0; i < 6; i++) {
                    double rad = Math.toRadians(i * 60.0);
                    float x1 = cx + (float) Math.cos(rad) * (rOuter * 0.76f);
                    float y1 = cy + (float) Math.sin(rad) * (rOuter * 0.76f);
                    float x2 = cx + (float) Math.cos(rad) * (rOuter * 1.05f);
                    float y2 = cy + (float) Math.sin(rad) * (rOuter * 1.05f);
                    canvas.drawLine(x1, y1, x2, y2, paint);
                }
            }
        }
    }

    // =========================================================================
    // 4. PlaceholderArtView: 无截图时的艺术封面缩略图（避免生硬色块）
    // =========================================================================
    public static final class PlaceholderArtView extends View {
        private final String platform;
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint decoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path curve = new Path();

        public PlaceholderArtView(Context ctx, String platform) {
            super(ctx);
            this.platform = platform == null ? "" : platform;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            if ("douyin".equals(platform)) {
                bgPaint.setShader(new LinearGradient(0, 0, w, h,
                        0xFF1E242B, 0xFF3B4A59, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, bgPaint);

                decoPaint.setStyle(Paint.Style.FILL);
                decoPaint.setColor(0x47FF8A4C);
                canvas.drawCircle(w * 0.72f, h * 0.27f, w * 0.30f, decoPaint);

                decoPaint.setStyle(Paint.Style.STROKE);
                decoPaint.setStrokeWidth(dpf(getContext(), 2.6f));
                decoPaint.setStrokeCap(Paint.Cap.ROUND);
                decoPaint.setColor(0xFFF6F4EF);
                curve.reset();
                curve.moveTo(w * 0.24f, h * 0.73f);
                curve.cubicTo(w * 0.38f, h * 0.46f, w * 0.58f, h * 0.44f, w * 0.77f, h * 0.29f);
                canvas.drawPath(curve, decoPaint);

                decoPaint.setStyle(Paint.Style.FILL);
                decoPaint.setColor(0xFFFF8A4C);
                canvas.drawCircle(w * 0.77f, h * 0.29f, dpf(getContext(), 3.8f), decoPaint);
            } else if ("wechat".equals(platform)) {
                bgPaint.setShader(new LinearGradient(0, 0, w, h,
                        0xFFDCE8DF, 0xFF8DB59B, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, bgPaint);

                decoPaint.setStyle(Paint.Style.FILL);
                decoPaint.setColor(0xE0FAF9F6);
                RectF card = new RectF(w * 0.22f, h * 0.22f, w * 0.78f, h * 0.78f);
                canvas.drawRoundRect(card, dpf(getContext(), 8), dpf(getContext(), 8), decoPaint);

                decoPaint.setStyle(Paint.Style.STROKE);
                decoPaint.setStrokeCap(Paint.Cap.ROUND);
                decoPaint.setStrokeWidth(dpf(getContext(), 2.2f));
                decoPaint.setColor(0xFF4D6B58);
                canvas.drawLine(w * 0.33f, h * 0.38f, w * 0.67f, h * 0.38f, decoPaint);
                decoPaint.setColor(0xFF8CA896);
                canvas.drawLine(w * 0.33f, h * 0.49f, w * 0.58f, h * 0.49f, decoPaint);
            } else {
                bgPaint.setShader(new LinearGradient(0, 0, w, h,
                        0xFFF6C6B8, 0xFFE88D72, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, bgPaint);

                decoPaint.setStyle(Paint.Style.FILL);
                decoPaint.setColor(0x38FFFFFF);
                RectF inner = new RectF(w * 0.16f, h * 0.14f, w * 0.84f, h * 0.86f);
                canvas.drawRoundRect(inner, dpf(getContext(), 10), dpf(getContext(), 10), decoPaint);
            }
        }
    }

    /** 轻量自动换行流式布局（用于乘法胶囊、标签池，零第三方依赖）。 */
    public static final class FlowLayout extends ViewGroup {
        private final int hGap;
        private final int vGap;

        public FlowLayout(Context ctx, int hGapDp, int vGapDp) {
            super(ctx);
            this.hGap = dp(ctx, hGapDp);
            this.vGap = dp(ctx, vGapDp);
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int maxW = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || maxW <= 0) {
                maxW = Integer.MAX_VALUE;
            }
            int lineW = 0;
            int lineH = 0;
            int totalH = 0;
            int maxLineW = 0;
            int count = getChildCount();
            int childWSpec = MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST);
            int childHSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                child.measure(childWSpec, childHSpec);
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (lineW > 0 && lineW + hGap + cw > maxW) {
                    totalH += lineH + vGap;
                    maxLineW = Math.max(maxLineW, lineW);
                    lineW = cw;
                    lineH = ch;
                } else {
                    lineW += (lineW == 0 ? 0 : hGap) + cw;
                    lineH = Math.max(lineH, ch);
                }
            }
            totalH += lineH;
            maxLineW = Math.max(maxLineW, lineW);
            int desiredW = maxLineW + getPaddingLeft() + getPaddingRight();
            int desiredH = totalH + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(
                    resolveSize(desiredW, widthMeasureSpec),
                    resolveSize(desiredH, heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = (r - l) - getPaddingLeft() - getPaddingRight();
            int x = getPaddingLeft();
            int y = getPaddingTop();
            int lineH = 0;
            int count = getChildCount();
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (x > getPaddingLeft() && (x - getPaddingLeft()) + cw > maxW) {
                    x = getPaddingLeft();
                    y += lineH + vGap;
                    lineH = 0;
                }
                child.layout(x, y, x + cw, y + ch);
                x += cw + hGap;
                lineH = Math.max(lineH, ch);
            }
        }
    }
}
