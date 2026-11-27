package com.zao.inboxcapture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Small folder outline next to the selected archive name. */
final class ArchiveFolderIcon extends Drawable {
    private final int size;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();

    ArchiveFolderIcon(Context context) {
        size = UiKit.dp(context, 17);
        stroke.setColor(UiKit.PAPER_SECOND);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        outline.moveTo(2, 5);
        outline.lineTo(8, 5);
        outline.lineTo(10, 7);
        outline.lineTo(18, 7);
        outline.lineTo(18, 16);
        outline.lineTo(2, 16);
        outline.close();
    }

    @Override public void draw(Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 20f, getBounds().height() / 20f);
        canvas.drawPath(outline, stroke);
        canvas.restore();
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) { stroke.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { stroke.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
