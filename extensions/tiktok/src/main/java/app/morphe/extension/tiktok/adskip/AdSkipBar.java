/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.WeakHashMap;

/**
 * The found ads drawn inside TikTok's own progress line, the way SponsorBlock colours sponsor
 * parts: the stretch of the line where each ad plays turns green. Every ad in the video gets
 * its own stretch.
 *
 * <p>The marks sit in the seek bar's overlay, so TikTok's layout and touch handling are
 * untouched, and they are drawn exactly over TikTok's line: same left and right ends (the
 * seek bar's own padding), the same centre and the same thickness. The thickness comes from
 * TikTok itself: its style method hands the seek bar a line height for every style (2dp at
 * rest, 4dp paused, 3dp while dragging) and that value is read on its way in, so the marks
 * grow and shrink with the line, animated the same way.
 */
public final class AdSkipBar {
    static final int MARK = 0xFF2BD96B;
    static final int MARK_SKIPPED = 0x8C2BD96B;

    private static final WeakHashMap<LinearLayout, Marks> BARS = new WeakHashMap<>();
    /** The bar whose style method is running; its line height arrives inside it. */
    private static WeakReference<LinearLayout> styling = new WeakReference<>(null);

    private AdSkipBar() {}

    /** TikTok's bar style method begins, for this bar. Main thread. */
    public static void beginStyle(@Nullable LinearLayout bar, int style) {
        styling = new WeakReference<>(bar);
    }

    /** The line height TikTok is handing the seek bar, in pixels. Main thread. */
    public static void lineHeight(@Nullable Float height) {
        LinearLayout bar = styling.get();
        if (bar == null || height == null || height <= 0) return;
        Marks marks = BARS.get(bar);
        if (marks != null) marks.animateLine(height);
        else PENDING.put(bar, height);
    }

    /** TikTok's handle width in pixels, as its style method hands it over. */
    static float handle;

    /** The handle's side on its way to the seek bar. Main thread. */
    public static void handleSide(@Nullable Float side) {
        if (side != null && side > 0) handle = side;
        refresh();
    }

    /** Where the bar's handle is, 0 to 100, from the seek bar itself so a drag is followed. */
    static float percent(ProgressBar bar) {
        int max = bar.getMax();
        return max <= 0 ? 0f : bar.getProgress() * 100f / max;
    }

    /** Line heights seen before the bar had marks. */
    private static final WeakHashMap<LinearLayout, Float> PENDING = new WeakHashMap<>();

    /** The bar's progress method, on the main thread, with the percent it is about to draw. */
    public static void onBar(@Nullable LinearLayout bar, float percent) {
        if (bar == null) return;
        try {
            Marks marks = BARS.get(bar);
            if (!AdSkip.active()) {
                if (marks != null) marks.setAid(null);
                return;
            }
            if (marks == null) {
                ProgressBar seekBar = findSeekBar(bar);
                if (seekBar == null) return;
                marks = new Marks(seekBar);
                Float pending = PENDING.remove(bar);
                if (pending != null) marks.line = pending;
                seekBar.getOverlay().add(marks);
                BARS.put(bar, marks);
            }
            marks.follow(percent);
        } catch (Throwable failure) {
            Logger.printException(() -> "Ad skip: could not mark the progress bar", failure);
        }
    }

    @Nullable
    private static ProgressBar findSeekBar(View view) {
        if (view instanceof ProgressBar) return (ProgressBar) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ProgressBar found = findSeekBar(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Redraws every bar, after a scan found ads in a video or an ad was skipped. */
    static void refresh() {
        for (Marks marks : BARS.values()) {
            if (marks != null) marks.invalidateSelf();
        }
    }

    private static final class Marks extends Drawable {
        private final WeakReference<ProgressBar> seekBar;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        @Nullable private String aid;
        /** TikTok's current line height in pixels; 0 until its style method has said. */
        float line;
        @Nullable private ValueAnimator animator;

        Marks(ProgressBar seekBar) {
            this.seekBar = new WeakReference<>(seekBar);
            paint.setStyle(Paint.Style.FILL);
        }

        void setAid(@Nullable String value) {
            if (value == null ? aid == null : value.equals(aid)) return;
            aid = value;
            invalidateSelf();
        }

        /** TikTok animates its line between styles over about 200 ms; the marks follow. */
        void animateLine(float target) {
            if (animator != null) animator.cancel();
            if (line <= 0) {
                line = target;
                invalidateSelf();
                return;
            }
            ValueAnimator grow = ValueAnimator.ofFloat(line, target);
            grow.setDuration(200);
            grow.setInterpolator(new DecelerateInterpolator());
            grow.addUpdateListener(a -> {
                line = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            animator = grow;
            grow.start();
        }

        void follow(float percent) {
            ProgressBar bar = seekBar.get();
            if (bar != null) {
                Rect b = getBounds();
                if (b.width() != bar.getWidth() || b.height() != bar.getHeight()) {
                    setBounds(0, 0, bar.getWidth(), bar.getHeight());
                }
            }
            String playing = AdSkip.playingAid();
            long duration = AdSkip.playingDuration();
            if (playing != null && duration > 0) {
                float expected = AdSkip.playingPosition() * 100f / duration;
                if (Math.abs(expected - percent) < 2.5f) {
                    setAid(playing);
                    return;
                }
            }
            if (percent < 0.5f && aid != null && !aid.equals(playing)) setAid(null);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            String shown = aid;
            ProgressBar bar = seekBar.get();
            if (shown == null || bar == null || !bar.isShown() || bar.getAlpha() <= 0f) return;
            AdScanner.Result result = AdScanner.result(shown);
            if (result == null || result.state != AdScanner.State.DONE || result.matches.isEmpty()) return;
            long duration = shown.equals(AdSkip.playingAid()) ? AdSkip.playingDuration() : 0;
            if (duration <= 0) duration = result.scannedMs;
            if (duration <= 0) return;

            float density = bar.getResources().getDisplayMetrics().density;
            // TikTok's line runs between the seek bar's paddings, centred in its height.
            float left = bar.getPaddingLeft();
            float right = bar.getWidth() - bar.getPaddingRight();
            if (right <= left) return;
            float thickness = line > 0 ? line : 2 * density;
            float centre = bar.getPaddingTop() + (bar.getHeight() - bar.getPaddingTop() - bar.getPaddingBottom()) / 2f;
            float top = centre - thickness / 2f, bottom = centre + thickness / 2f;
            float radius = thickness / 2f;
            float width = right - left;
            float minWidth = Math.max(thickness, 2 * density);

            // TikTok's handle stays on top: the marks leave a gap where it sits.
            int saved = canvas.save();
            float handle = AdSkipBar.handle > 0 ? AdSkipBar.handle : 4 * density;
            float handleX = left + width * Math.max(0f, Math.min(100f, AdSkipBar.percent(bar))) / 100f;
            float gap = handle / 2f + 0.5f * density;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                canvas.clipOutRect(handleX - gap, 0, handleX + gap, bar.getHeight());
            } else {
                //noinspection deprecation
                canvas.clipRect(handleX - gap, 0, handleX + gap, bar.getHeight(), android.graphics.Region.Op.DIFFERENCE);
            }

            List<AdMatcher.Match> matches = result.matches;
            for (AdMatcher.Match match : matches) {
                float x0 = left + width * Math.max(0, match.startMs) / duration;
                float x1 = left + width * Math.min(duration, match.endMs) / duration;
                if (x1 - x0 < minWidth) x1 = x0 + minWidth;
                x0 = Math.max(left, x0);
                x1 = Math.min(right, x1);
                if (x1 <= x0) continue;
                paint.setColor(AdSkip.wasSkipped(shown, match.startMs) ? MARK_SKIPPED : MARK);
                rect.set(x0, top, x1, bottom);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
            canvas.restoreToCount(saved);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
