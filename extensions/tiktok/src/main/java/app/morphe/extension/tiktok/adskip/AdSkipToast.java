/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

import java.lang.ref.WeakReference;

/**
 * The skip notice: a small pill centred across the screen, 20 px above the creator's name, so
 * it sits clear of the name, the caption and the buttons. In it the green AD badge, the ad's
 * name and "Watch it". It goes by itself after a few seconds.
 */
final class AdSkipToast {
    private static final long VISIBLE_MS = 3500;
    /** The gap between the pill and the creator's name. */
    private static final int ABOVE_NAME_PX = 20;
    /** Where it sits when the name can't be found: about 200 px above the bottom bar's line. */
    private static final int FALLBACK_BOTTOM_PX = 200;
    private static final String NAME_ID = "title";
    private static final String BAR_ID = "video_seek_bar";

    private static WeakReference<View> shown = new WeakReference<>(null);
    private static int generation;

    private AdSkipToast() {}

    static void show(String name, @Nullable Runnable watch) {
        Utils.runOnMainThread(() -> {
            try {
                Activity activity = Utils.getActivity();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                ViewGroup root = activity.findViewById(android.R.id.content);
                if (root == null) return;
                dismiss();
                View pill = build(activity, name, watch);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                params.bottomMargin = bottomMargin(activity, root);
                root.addView(pill, params);
                pill.setAlpha(0f);
                pill.setScaleX(0.92f);
                pill.setScaleY(0.92f);
                pill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start();
                shown = new WeakReference<>(pill);
                int token = ++generation;
                Utils.runOnMainThreadDelayed(() -> {
                    if (token == generation) dismiss();
                }, VISIBLE_MS);
            } catch (Throwable failure) {
                Logger.printException(() -> "Ad skip: could not show the notice", failure);
            }
        });
    }

    /** From the root's bottom to 20 px above the playing video's creator name. */
    private static int bottomMargin(Activity activity, ViewGroup root) {
        int[] rootAt = new int[2];
        root.getLocationOnScreen(rootAt);
        int rootBottom = rootAt[1] + root.getHeight();
        View name = shownNamed(root, id(activity, NAME_ID));
        if (name != null) {
            int[] at = new int[2];
            name.getLocationOnScreen(at);
            int margin = rootBottom - at[1] + ABOVE_NAME_PX;
            if (margin > 0 && margin < root.getHeight() * 0.8f) return margin;
        }
        View bar = shownNamed(root, id(activity, BAR_ID));
        if (bar != null) {
            int[] at = new int[2];
            bar.getLocationOnScreen(at);
            int margin = rootBottom - at[1] + FALLBACK_BOTTOM_PX;
            if (margin > 0 && margin < root.getHeight() * 0.8f) return margin;
        }
        return Math.round(root.getHeight() * 0.25f);
    }

    private static int id(Activity activity, String name) {
        return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
    }

    /**
     * The view with this id that is on screen now. The feed keeps the cells either side bound, each
     * with its own name, so the one wanted is the one actually visible in the middle of the screen.
     */
    @Nullable
    private static View shownNamed(ViewGroup root, int id) {
        if (id == 0) return null;
        int width = root.getResources().getDisplayMetrics().widthPixels;
        int height = root.getResources().getDisplayMetrics().heightPixels;
        return find(root, id, width, height, new Rect());
    }

    @Nullable
    private static View find(View view, int id, int width, int height, Rect rect) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view.getId() == id && view.getGlobalVisibleRect(rect)
                && rect.left >= 0 && rect.right <= width && rect.top >= 0 && rect.bottom <= height) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), id, width, height, rect);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View build(Activity activity, String name, @Nullable Runnable watch) {
        LinearLayout pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(activity, 5), dp(activity, 4), dp(activity, watch == null ? 11 : 5), dp(activity, 4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xD9161618);
        background.setCornerRadius(dp(activity, 16));
        pill.setBackground(background);
        pill.setElevation(dp(activity, 4));

        TextView badge = new TextView(activity);
        badge.setText(L10n.t("AD"));
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(0xFF06210F);
        badge.setLetterSpacing(0.06f);
        badge.setIncludeFontPadding(false);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(activity, 6), dp(activity, 3), dp(activity, 6), dp(activity, 3));
        GradientDrawable badgeFill = new GradientDrawable();
        badgeFill.setColor(AdSkipBar.MARK);
        badgeFill.setCornerRadius(dp(activity, 10));
        badge.setBackground(badgeFill);
        pill.addView(badge, centred());

        TextView label = new TextView(activity);
        label.setText(name);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        label.setTextColor(0xF2FFFFFF);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(dp(activity, 180));
        label.setIncludeFontPadding(false);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(activity, 7), 0, dp(activity, 7), 0);
        pill.addView(label, centred());

        if (watch != null) {
            TextView action = new TextView(activity);
            action.setText(L10n.t("Watch it"));
            action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            action.setTypeface(Typeface.DEFAULT_BOLD);
            action.setTextColor(AdSkipBar.MARK);
            action.setIncludeFontPadding(false);
            action.setGravity(Gravity.CENTER);
            action.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
            GradientDrawable fill = new GradientDrawable();
            fill.setColor(0x262BD96B);
            fill.setCornerRadius(dp(activity, 12));
            action.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), fill, null));
            action.setOnClickListener(v -> {
                dismiss();
                try {
                    watch.run();
                } catch (Throwable failure) {
                    Logger.printException(() -> "Ad skip: could not go back to the ad", failure);
                }
            });
            pill.addView(action, centred());
        }
        return pill;
    }

    private static LinearLayout.LayoutParams centred() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.gravity = Gravity.CENTER_VERTICAL;
        return params;
    }

    static void dismiss() {
        View pill = shown.get();
        shown = new WeakReference<>(null);
        if (pill == null) return;
        pill.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) pill.getParent();
            if (parent != null) parent.removeView(pill);
        }).start();
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
