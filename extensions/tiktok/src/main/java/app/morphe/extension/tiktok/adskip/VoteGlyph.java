/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Thumbs-up / thumbs-down icon, drawn with a path at any size, no PNG needed.
 * The thumb outline is a simplified flat hand: a wide base with a rounded thumb pointing up,
 * mirrored vertically for the down variant.
 */
public final class VoteGlyph extends Drawable {
    public enum Direction { UP, DOWN }

    private final Direction direction;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean computed;

    public VoteGlyph(Direction direction, int color, float strokePx) {
        this.direction = direction;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        paint.setStrokeWidth(strokePx);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        if (!computed) build(b);
        canvas.save();
        if (direction == Direction.DOWN) {
            canvas.scale(1f, -1f, b.exactCenterX(), b.exactCenterY());
        }
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        computed = false;
        path.reset();
    }

    /** Builds the thumb-up path in the given bounds. */
    private void build(Rect b) {
        computed = true;
        path.reset();
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float w = b.width() * 0.42f, h = b.height() * 0.44f;
        // Thumb base (palm): a rounded rectangle in the lower-left.
        float px0 = cx - w, py0 = cy - h * 0.08f;
        float px1 = cx + w * 0.30f, py1 = cy + h;
        float r = h * 0.28f;
        path.moveTo(px0 + r, py0);
        path.lineTo(px1 - r, py0);
        path.arcTo(px1 - r * 2, py0, px1, py0 + r * 2, -90, 90, false);
        path.lineTo(px1, py1 - r);
        path.arcTo(px1 - r * 2, py1 - r * 2, px1, py1, 0, 90, false);
        path.lineTo(px0 + r, py1);
        path.arcTo(px0, py1 - r * 2, px0 + r * 2, py1, 90, 90, false);
        path.lineTo(px0, py0 + r);
        path.arcTo(px0, py0, px0 + r * 2, py0 + r * 2, 180, 90, false);
        path.close();
        // Thumb digit: a rounded bar going up and curving left at the top.
        float tx0 = cx - w * 0.05f, ty0 = cy - h * 1.0f;
        float tx1 = cx + w * 0.60f, ty1 = cy - h * 0.08f;
        float tr = (tx1 - tx0) * 0.5f;
        path.moveTo(tx0, ty1);
        path.lineTo(tx0, ty0 + tr);
        path.arcTo(tx0, ty0, tx1, ty0 + tr * 2, 180, 180, false);
        path.lineTo(tx1, ty1);
    }

    @Override public void setAlpha(int alpha)                    { paint.setAlpha(alpha); }
    @Override public void setColorFilter(@Nullable ColorFilter f){ paint.setColorFilter(f); }
    @Override public int getOpacity()                            { return PixelFormat.TRANSLUCENT; }
}
