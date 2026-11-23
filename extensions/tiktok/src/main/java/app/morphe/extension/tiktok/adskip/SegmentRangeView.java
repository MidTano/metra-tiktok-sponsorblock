/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * The editor's timeline: a strip of frames over the sound's waveform, the chosen part lit and
 * the rest dimmed, a handle at each end and the play head. Drag a handle to move that end, drag
 * inside to move the play head, pinch-free: a long video is zoomed by the editor's own buttons.
 */
@SuppressLint("ViewConstructor")
final class SegmentRangeView extends View {
    interface Listener {
        void rangeChanged(long startMs, long endMs, boolean fromUser, boolean dragging);
        void seekRequested(long ms, boolean dragging);
    }

    private final float density;
    private final int accent;
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbBg = new Paint();
    private final RectF rect = new RectF();
    private final Rect src = new Rect();
    private final Path path = new Path();

    private float[] envelope = new float[0];
    private Bitmap[] thumbs = new Bitmap[0];
    private long durationMs = 1;
    /** The visible window, for zooming into a long video. */
    private long viewStart = 0, viewEnd = 1;
    private long startMs, endMs;
    private long playMs = -1;
    private Listener listener;

    private static final int NONE = 0, START = 1, END = 2, HEAD = 3, MOVE = 4;
    private int dragging = NONE;
    private float downX;
    private long downStart, downEnd;
    private boolean moved;

    SegmentRangeView(Context context, int accent) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;
        this.accent = accent;
        wavePaint.setColor(Color.WHITE);
        wavePaint.setStyle(Paint.Style.FILL);
        waveDim.setColor(0x66FFFFFF);
        waveDim.setStyle(Paint.Style.FILL);
        shade.setColor(0xAA000000);
        frame.setColor(accent);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(3 * density);
        handle.setColor(accent);
        grip.setColor(Color.WHITE);
        grip.setStrokeWidth(1.5f * density);
        grip.setStrokeCap(Paint.Cap.ROUND);
        head.setColor(Color.WHITE);
        head.setStrokeWidth(2 * density);
        head.setShadowLayer(3 * density, 0, 0, 0x99000000);
        tick.setColor(0x99FFFFFF);
        tick.setTextSize(10 * density);
        thumbBg.setColor(0xFF1C1C1E);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setContentDescription("Ad range");
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setDuration(long ms) {
        durationMs = Math.max(1, ms);
        viewStart = 0;
        viewEnd = durationMs;
        invalidate();
    }

    long duration() {
        return durationMs;
    }

    /** Peak loudness per column, 0..1. */
    void setEnvelope(float[] values) {
        envelope = values == null ? new float[0] : values;
        invalidate();
    }

    void setThumbs(Bitmap[] bitmaps) {
        thumbs = bitmaps == null ? new Bitmap[0] : bitmaps;
        invalidate();
    }

    void setRange(long start, long end) {
        startMs = clamp(start, 0, durationMs);
        endMs = clamp(end, startMs, durationMs);
        invalidate();
    }

    long start() {
        return startMs;
    }

    long end() {
        return endMs;
    }

    void setPlayhead(long ms) {
        playMs = ms;
        invalidate();
    }

    /** Shows the chosen part with a margin either side, or the whole video again. */
    void zoom(boolean toSelection) {
        if (toSelection) {
            long span = Math.max(3000, endMs - startMs);
            long margin = span / 4 + 500;
            viewStart = Math.max(0, startMs - margin);
            viewEnd = Math.min(durationMs, endMs + margin);
        } else {
            viewStart = 0;
            viewEnd = durationMs;
        }
        invalidate();
    }

    boolean zoomed() {
        return viewStart > 0 || viewEnd < durationMs;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, Math.round(112 * density));
    }

    private float pad() {
        return 14 * density;
    }

    private float xOf(long ms) {
        float left = pad(), right = getWidth() - pad();
        return left + (right - left) * (ms - viewStart) / (float) Math.max(1, viewEnd - viewStart);
    }

    private long msOf(float x) {
        float left = pad(), right = getWidth() - pad();
        float f = (x - left) / Math.max(1f, right - left);
        return clamp(viewStart + Math.round(f * (viewEnd - viewStart)), 0, durationMs);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float left = pad(), right = w - pad();
        float thumbTop = 4 * density, thumbBottom = h * 0.52f;
        float waveTop = thumbBottom + 6 * density, waveBottom = h - 16 * density;

        // Frames.
        rect.set(left, thumbTop, right, thumbBottom);
        canvas.drawRoundRect(rect, 6 * density, 6 * density, thumbBg);
        if (thumbs.length > 0) {
            float cell = (right - left) / thumbs.length;
            canvas.save();
            canvas.clipRect(left, thumbTop, right, thumbBottom);
            for (int i = 0; i < thumbs.length; i++) {
                Bitmap b = thumbs[i];
                if (b == null || b.isRecycled()) continue;
                // The frame for this cell's time in the current view.
                rect.set(left + i * cell, thumbTop, left + (i + 1) * cell + 1, thumbBottom);
                float ar = b.getWidth() / (float) b.getHeight();
                float cellAr = rect.width() / rect.height();
                if (ar > cellAr) {
                    int cw = Math.round(b.getHeight() * cellAr);
                    src.set((b.getWidth() - cw) / 2, 0, (b.getWidth() + cw) / 2, b.getHeight());
                } else {
                    int ch = Math.round(b.getWidth() / cellAr);
                    src.set(0, (b.getHeight() - ch) / 2, b.getWidth(), (b.getHeight() + ch) / 2);
                }
                canvas.drawBitmap(b, src, rect, null);
            }
            canvas.restore();
        }

        // Waveform: a bar per pixel column, mirrored about the middle.
        float mid = (waveTop + waveBottom) / 2f, amp = (waveBottom - waveTop) / 2f;
        float xs = xOf(startMs), xe = xOf(endMs);
        if (envelope.length > 0) {
            int columns = Math.max(1, Math.round((right - left) / (2 * density)));
            float colW = (right - left) / columns;
            for (int c = 0; c < columns; c++) {
                long t0 = viewStart + (viewEnd - viewStart) * c / columns;
                long t1 = viewStart + (viewEnd - viewStart) * (c + 1) / columns;
                int e0 = (int) (t0 * envelope.length / durationMs);
                int e1 = Math.max(e0 + 1, (int) (t1 * envelope.length / durationMs));
                float v = 0;
                for (int e = Math.max(0, e0); e < Math.min(envelope.length, e1); e++) v = Math.max(v, envelope[e]);
                float bh = Math.max(1 * density, v * amp);
                float x = left + c * colW;
                float cx = x + colW / 2;
                Paint p = cx >= xs && cx <= xe ? wavePaint : waveDim;
                canvas.drawRect(x + colW * 0.15f, mid - bh, x + colW * 0.85f, mid + bh, p);
            }
        }

        // Dim outside the range.
        canvas.drawRect(left, thumbTop, Math.max(left, xs), waveBottom, shade);
        canvas.drawRect(Math.min(right, xe), thumbTop, right, waveBottom, shade);

        // Frame and handles.
        rect.set(xs, thumbTop, xe, waveBottom);
        canvas.drawRoundRect(rect, 4 * density, 4 * density, frame);
        drawHandle(canvas, xs, thumbTop, waveBottom, true);
        drawHandle(canvas, xe, thumbTop, waveBottom, false);

        // Play head.
        if (playMs >= viewStart && playMs <= viewEnd) {
            float x = xOf(playMs);
            canvas.drawLine(x, thumbTop - 2 * density, x, waveBottom + 2 * density, head);
            canvas.drawCircle(x, thumbTop - 2 * density, 3.5f * density, head);
        }

        // Time ticks.
        long span = viewEnd - viewStart;
        long step = span <= 6000 ? 1000 : span <= 20000 ? 2000 : span <= 60000 ? 5000 : span <= 180000 ? 15000 : 30000;
        for (long t = ((viewStart + step - 1) / step) * step; t <= viewEnd; t += step) {
            float x = xOf(t);
            String label = AdSkipUi.time(t, false);
            float tw = tick.measureText(label);
            canvas.drawText(label, Math.max(0, Math.min(w - tw, x - tw / 2)), h - 3 * density, tick);
        }
    }

    private void drawHandle(Canvas canvas, float x, float top, float bottom, boolean start) {
        float hw = 12 * density;
        if (start) rect.set(x - hw, top, x + 1, bottom);
        else rect.set(x - 1, top, x + hw, bottom);
        canvas.drawRoundRect(rect, 5 * density, 5 * density, handle);
        float cx = rect.centerX(), cy = rect.centerY();
        canvas.drawLine(cx - 2 * density, cy - 7 * density, cx - 2 * density, cy + 7 * density, grip);
        canvas.drawLine(cx + 2 * density, cy - 7 * density, cx + 2 * density, cy + 7 * density, grip);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                float xs = xOf(startMs), xe = xOf(endMs);
                float grab = 26 * density;
                float ds = Math.abs(x - (xs - 6 * density)), de = Math.abs(x - (xe + 6 * density));
                if (ds <= grab && ds <= de) dragging = START;
                else if (de <= grab) dragging = END;
                else if (x > xs && x < xe && event.getY() < getHeight() * 0.52f) dragging = MOVE;
                else dragging = HEAD;
                downX = x;
                downStart = startMs;
                downEnd = endMs;
                moved = false;
                if (dragging == HEAD) seek(msOf(x), true);
                if (dragging == START || dragging == END) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (Math.abs(x - downX) > 3 * density) moved = true;
                long ms = msOf(x);
                long minimum = 500;
                if (dragging == START) {
                    startMs = clamp(ms, 0, endMs - minimum);
                    notifyRange(true);
                    seek(startMs, true);
                } else if (dragging == END) {
                    endMs = clamp(ms, startMs + minimum, durationMs);
                    notifyRange(true);
                    seek(endMs, true);
                } else if (dragging == MOVE) {
                    long delta = msOf(x) - msOf(downX);
                    long len = downEnd - downStart;
                    long s = clamp(downStart + delta, 0, durationMs - len);
                    startMs = s;
                    endMs = s + len;
                    notifyRange(true);
                } else if (dragging == HEAD) {
                    seek(ms, true);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int was = dragging;
                dragging = NONE;
                if (was == START || was == END || was == MOVE) {
                    if (listener != null) listener.rangeChanged(startMs, endMs, true, false);
                    if (was == MOVE && !moved) seek(msOf(x), false);
                    else seek(was == END ? endMs : startMs, false);
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                } else if (was == HEAD) {
                    seek(msOf(x), false);
                }
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    private void notifyRange(boolean dragging) {
        if (listener != null) listener.rangeChanged(startMs, endMs, true, dragging);
    }

    private void seek(long ms, boolean dragging) {
        playMs = ms;
        if (listener != null) listener.seekRequested(ms, dragging);
        invalidate();
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
