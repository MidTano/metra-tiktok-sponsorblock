/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
 * The skip notice: a small pill high on the left of the feed, above the caption and the
 * creator's name, so it covers neither. It shows an AD badge, the ad's name and "Watch it",
 * and goes by itself after a few seconds.
 */
final class AdSkipToast {
    private static final long VISIBLE_MS = 3500;
    /** How far up from the bottom: above the caption, the name and the music line. */
    private static final float BOTTOM_FRACTION = 0.34f;

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
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
                int height = root.getHeight() > 0 ? root.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
                params.leftMargin = dp(activity, 12);
                params.rightMargin = dp(activity, 88); // clear of the buttons on the right
                params.bottomMargin = Math.round(height * BOTTOM_FRACTION);
                root.addView(pill, params);
                pill.setAlpha(0f);
                pill.setTranslationY(dp(activity, 8));
                pill.animate().alpha(1f).translationY(0).setDuration(180).start();
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

    private static View build(Activity activity, String name, @Nullable Runnable watch) {
        LinearLayout pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(activity, 5), dp(activity, 4), dp(activity, watch == null ? 10 : 4), dp(activity, 4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xCC161618);
        background.setCornerRadius(dp(activity, 16));
        pill.setBackground(background);
        pill.setElevation(dp(activity, 4));

        TextView badge = new TextView(activity);
        badge.setText(L10n.t("AD"));
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.BLACK);
        badge.setLetterSpacing(0.06f);
        badge.setIncludeFontPadding(false);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(activity, 6), dp(activity, 3), dp(activity, 6), dp(activity, 3));
        GradientDrawable badgeFill = new GradientDrawable();
        badgeFill.setColor(AdSkipBar.MARK);
        badgeFill.setCornerRadius(dp(activity, 10));
        badge.setBackground(badgeFill);
        pill.addView(badge);

        TextView label = new TextView(activity);
        label.setText(name);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        label.setTextColor(0xF2FFFFFF);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(dp(activity, 170));
        label.setIncludeFontPadding(false);
        label.setPadding(dp(activity, 7), 0, dp(activity, 6), 0);
        pill.addView(label);

        if (watch != null) {
            TextView action = new TextView(activity);
            action.setText(L10n.t("Watch it"));
            action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            action.setTypeface(Typeface.DEFAULT_BOLD);
            action.setTextColor(AdSkipBar.MARK);
            action.setIncludeFontPadding(false);
            action.setGravity(Gravity.CENTER);
            action.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(dp(activity, 12));
            action.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, mask));
            action.setOnClickListener(v -> {
                dismiss();
                try {
                    watch.run();
                } catch (Throwable failure) {
                    Logger.printException(() -> "Ad skip: could not go back to the ad", failure);
                }
            });
            pill.addView(action);
        }
        return pill;
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
