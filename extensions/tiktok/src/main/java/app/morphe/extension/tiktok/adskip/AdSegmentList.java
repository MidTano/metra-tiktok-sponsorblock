/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** The marked ads: switch each on or off, rename, delete, and the recent scan log. */
final class AdSegmentList {
    private final Activity activity;
    private final Dialog dialog;
    private LinearLayout list;

    private AdSegmentList(Activity activity) {
        this.activity = activity;
        this.dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    }

    static void open(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        Utils.runOnMainThread(() -> new AdSegmentList(activity).show());
    }

    private void show() {
        Context c = activity;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AdSkipUi.BG);

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(12), dp(8));
        TextView close = AdSkipUi.chip(c, "✕", false);
        close.setOnClickListener(v -> dialog.dismiss());
        bar.addView(close);
        TextView title = AdSkipUi.text(c, L10n.t("Marked ads"), 18, AdSkipUi.TEXT, true);
        title.setPadding(dp(12), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar);

        ScrollView scroll = new ScrollView(c);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), dp(24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setStatusBarColor(AdSkipUi.BG);
            window.setNavigationBarColor(AdSkipUi.BG);
        }
        refresh();
        dialog.show();
    }

    private void refresh() {
        Context c = activity;
        list.removeAllViews();
        List<AdSegment> segments = AdSegmentStore.all();

        TextView summary = AdSkipUi.text(c, segments.isEmpty()
                        ? L10n.t("No ads marked yet. Open a video's share sheet and tap Mark ad.")
                        : L10n.f("%1$s ads, skipped %2$s times", String.valueOf(segments.size()),
                                String.valueOf(AdSegmentStore.totalSkips())),
                13, AdSkipUi.TEXT_2, false);
        summary.setPadding(dp(4), dp(4), dp(4), dp(12));
        list.addView(summary);

        for (int i = segments.size() - 1; i >= 0; i--) list.addView(card(segments.get(i)));

        if (!segments.isEmpty()) {
            TextView clear = AdSkipUi.chip(c, L10n.t("Delete all"), false);
            clear.setTextColor(AdSkipUi.ACCENT);
            clear.setOnClickListener(v -> confirm(L10n.t("Delete every marked ad?"), () -> {
                AdSegmentStore.clear();
                AdScanner.clearAll();
                refresh();
            }));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.topMargin = dp(8);
            p.gravity = Gravity.END;
            list.addView(clear, p);
        }

        List<String> log = AdSkipLog.lines();
        if (!log.isEmpty()) {
            TextView heading = AdSkipUi.text(c, L10n.t("Recent checks"), 13, AdSkipUi.TEXT, true);
            heading.setPadding(dp(4), dp(24), 0, dp(6));
            list.addView(heading);
            StringBuilder text = new StringBuilder();
            for (String line : log) text.append(line).append('\n');
            TextView lines = AdSkipUi.text(c, text.toString().trim(), 11, AdSkipUi.TEXT_2, false);
            lines.setTypeface(android.graphics.Typeface.MONOSPACE);
            lines.setBackground(AdSkipUi.round(AdSkipUi.SURFACE, dp(12)));
            lines.setPadding(dp(12), dp(10), dp(12), dp(10));
            lines.setTextIsSelectable(true);
            list.addView(lines);
        }
    }

    private View card(AdSegment segment) {
        Context c = activity;
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(AdSkipUi.round(AdSkipUi.SURFACE, dp(14)));
        card.setPadding(dp(14), dp(12), dp(8), dp(12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(8);
        card.setLayoutParams(cardParams);

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = AdSkipUi.text(c, segment.name, 15, segment.enabled ? AdSkipUi.TEXT : AdSkipUi.TEXT_2, true);
        text.addView(name);
        String details = L10n.f("%1$s s · skipped %2$s times · %3$s",
                AdSkipUi.seconds(segment.durationMs), String.valueOf(segment.skips),
                DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(segment.createdAt)));
        text.addView(AdSkipUi.text(c, details, 12, AdSkipUi.TEXT_2, false));
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));

        Switch toggle = new Switch(c);
        toggle.setChecked(segment.enabled);
        toggle.setOnCheckedChangeListener((b, on) -> {
            AdSegmentStore.setEnabled(segment.id, on);
            name.setTextColor(on ? AdSkipUi.TEXT : AdSkipUi.TEXT_2);
        });
        card.addView(toggle);

        TextView more = AdSkipUi.chip(c, "⋯", false);
        more.setOnClickListener(v -> menu(segment));
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(-2, -2);
        moreParams.leftMargin = dp(6);
        card.addView(more, moreParams);
        card.setOnClickListener(v -> menu(segment));
        return card;
    }

    private void menu(AdSegment segment) {
        String[] items = {L10n.t("Rename"), L10n.t("Delete")};
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(segment.name)
                .setItems(items, (d, which) -> {
                    if (which == 0) rename(segment);
                    else confirm(L10n.f("Delete \"%1$s\"?", segment.name), () -> {
                        AdSegmentStore.remove(segment.id);
                        refresh();
                    });
                })
                .show();
    }

    private void rename(AdSegment segment) {
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setText(segment.name);
        field.setSelection(field.getText().length());
        LinearLayout box = new LinearLayout(activity);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(field, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L10n.t("Rename"))
                .setView(box)
                .setPositiveButton(L10n.t("Save"), (d, w) -> {
                    String value = field.getText().toString().trim();
                    if (!value.isEmpty()) AdSegmentStore.rename(segment.id, value);
                    refresh();
                })
                .setNegativeButton(L10n.t("Cancel"), null)
                .show();
    }

    private void confirm(String message, Runnable yes) {
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(message)
                .setPositiveButton(L10n.t("Delete"), (d, w) -> yes.run())
                .setNegativeButton(L10n.t("Cancel"), null)
                .show();
    }

    private int dp(float v) {
        return AdSkipUi.dp(activity, v);
    }
}
