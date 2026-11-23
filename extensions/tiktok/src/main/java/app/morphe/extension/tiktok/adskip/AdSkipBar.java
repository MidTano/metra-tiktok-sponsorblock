/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.WeakHashMap;

/**
 * The found ads drawn on TikTok's own progress bar, the way SponsorBlock marks sponsor parts:
 * a yellow stretch of the line where each ad plays. Every ad in the video gets its own mark.
 *
 * <p>The marks are a drawable in the seek bar's overlay, so TikTok's layout and touch handling
 * are untouched. They are drawn over the bar's own track, read from its progress drawable on
 * every frame, so they follow the bar when it grows while paused or dragged.
 */
public final class AdSkipBar {
    static final int MARK = 0xFFFFC400;
    static final int MARK_SKIPPED = 0x99FFC400;

    /** Each bar's mark drawable, and which video that bar was last seen showing. */
    private static final WeakHashMap<LinearLayout, Marks> BARS = new WeakHashMap<>();

    private AdSkipBar() {}

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

    /** Redraws every bar, after a scan found ads in a video. */
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

        Marks(ProgressBar seekBar) {
            this.seekBar = new WeakReference<>(seekBar);
            paint.setStyle(Paint.Style.FILL);
        }

        void setAid(@Nullable String value) {
            if (value == null ? aid == null : value.equals(aid)) return;
            aid = value;
            invalidateSelf();
        }

        /**
         * Which video this bar shows. The bar whose percent is the playing video's own is that
         * video's; a bar that has gone back to its start without playing is a recycled cell
         * whose old marks must go.
         */
        void follow(float percent) {
            ProgressBar bar = seekBar.get();
            if (bar != null) {
                Rect b = getBounds();
                if (b.width() != bar.getWidth() || b.height() != bar.getHeight()) fit(bar, this);
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
            if (shown == null || bar == null || !bar.isShown()) return;
            AdScanner.Result result = AdScanner.result(shown);
            if (result == null || result.state != AdScanner.State.DONE || result.matches.isEmpty()) return;
            long duration = shown.equals(AdSkip.playingAid()) ? AdSkip.playingDuration() : 0;
            if (duration <= 0) duration = result.scannedMs;
            if (duration <= 0) return;

            // The track: the progress drawable's bounds, inside the bar's padding.
            Drawable track = bar.getProgressDrawable();
            Rect bounds = track != null ? track.getBounds() : null;
            float left = bar.getPaddingLeft(), right = bar.getWidth() - bar.getPaddingRight();
            float top, bottom;
            if (bounds != null && !bounds.isEmpty()) {
                left = bar.getPaddingLeft() + bounds.left;
                right = bar.getPaddingLeft() + bounds.right;
                float centre = bar.getPaddingTop() + bounds.exactCenterY();
                float half = Math.max(bounds.height() / 2f, 1.5f * density(bar));
                top = centre - half;
                bottom = centre + half;
            } else {
                float centre = bar.getHeight() / 2f;
                top = centre - 1.5f * density(bar);
                bottom = centre + 1.5f * density(bar);
            }
            if (right <= left) return;
            float width = right - left;
            float radius = (bottom - top) / 2f;
            float minWidth = 3 * density(bar);
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
        }

        private static float density(View view) {
            return view.getResources().getDisplayMetrics().density;
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

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            invalidateSelf();
        }
    }

    /** The overlay drawable has to span the seek bar; it is resized on every progress tick. */
    static void fit(ProgressBar bar, Drawable marks) {
        marks.setBounds(0, 0, bar.getWidth(), bar.getHeight());
    }
}
