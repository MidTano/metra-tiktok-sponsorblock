/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.AdAudioSource;
import app.morphe.extension.tiktok.settings.L10n;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cuts an ad out of a video: the video plays at the top with its sound, a strip of frames and
 * the waveform below it, and two handles choose where the ad starts and ends. "Play ad" plays
 * just the chosen part, so the reader hears exactly what will be matched. Saving fingerprints
 * the cut, checks it can be found again in this very video, and adds it to the list.
 */
final class AdSegmentEditor {
    private static final long MAX_LOAD_MS = 10L * 60 * 1000;
    private static final long DEFAULT_LENGTH_MS = 15_000;
    private static final int THUMBS = 10;
    /** Envelope resolution: one value per 20 ms. */
    private static final int ENVELOPE_MS = 20;
    /** Fewer hashes than this is too little sound to recognise. */
    private static final int MIN_HASHES = 40;
    private static final long MIN_LENGTH_MS = 2000;

    private final Activity activity;
    private final Object aweme;
    private final String aid;
    private final Dialog dialog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean();

    private FrameLayout videoBox;
    private TextureView texture;
    private TextView status;
    private ProgressBar spinner;
    private LinearLayout controls;
    private SegmentRangeView range;
    private TextView startLabel, endLabel, lengthLabel, positionLabel, hint;
    private TextView playButton, playAdButton, saveButton, zoomButton;
    private EditText nameField;

    private File pictureFile, soundFile;
    private MediaPlayer picture, sound;
    private Surface surface;
    private int videoWidth, videoHeight;
    private boolean prepared;
    private long durationMs;
    private Landmarks.Print print;
    /** When the play should stop: the end of the ad for Play ad, or -1. */
    private long stopAt = -1;
    private long pendingSeek = -1;
    private boolean seeking;
    private final long initialPositionMs;

    private AdSegmentEditor(Activity activity, Object aweme) {
        this.activity = activity;
        this.aweme = aweme;
        this.aid = Reflect.string(aweme, "getAid", "aid");
        this.initialPositionMs = Math.max(0, AdSkip.playingPositionFor(aid));
        this.dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    }

    static void open(Activity activity, Object aweme) {
        if (activity == null || aweme == null || activity.isFinishing()) return;
        Utils.runOnMainThread(() -> {
            try {
                new AdSegmentEditor(activity, aweme).show();
            } catch (Throwable failure) {
                Logger.printException(() -> "Ad skip: could not open the editor", failure);
                Utils.showToastShort(L10n.t("Couldn't open the ad editor"));
            }
        });
    }

    // region layout

    private void show() {
        Context c = activity;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AdSkipUi.BG);

        // Top bar.
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(12), dp(8));
        TextView close = AdSkipUi.chip(c, "✕", false);
        close.setContentDescription(L10n.t("Close"));
        close.setOnClickListener(v -> dialog.dismiss());
        bar.addView(close);
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, dp(8), 0);
        titles.addView(AdSkipUi.text(c, L10n.t("Mark an ad"), 18, AdSkipUi.TEXT, true));
        titles.addView(AdSkipUi.text(c, L10n.t("Choose where the ad starts and ends"), 12, AdSkipUi.TEXT_2, false));
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        saveButton = AdSkipUi.chip(c, L10n.t("Save"), true);
        saveButton.setOnClickListener(v -> save());
        AdSkipUi.enable(saveButton, false);
        bar.addView(saveButton);
        root.addView(bar);

        // Video.
        videoBox = new FrameLayout(c);
        videoBox.setBackgroundColor(Color.BLACK);
        texture = new TextureView(c);
        videoBox.addView(texture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        spinner = new ProgressBar(c);
        videoBox.addView(spinner, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        status = AdSkipUi.text(c, L10n.t("Loading the video and its sound…"), 13, AdSkipUi.TEXT_2, false);
        status.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        statusParams.topMargin = dp(70);
        videoBox.addView(status, statusParams);
        positionLabel = AdSkipUi.text(c, "", 12, Color.WHITE, true);
        positionLabel.setBackground(AdSkipUi.round(0x99000000, dp(10)));
        positionLabel.setPadding(dp(8), dp(3), dp(8), dp(3));
        FrameLayout.LayoutParams posParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        posParams.setMargins(0, 0, dp(10), dp(10));
        videoBox.addView(positionLabel, posParams);
        videoBox.setOnClickListener(v -> togglePlay());
        root.addView(videoBox, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Controls.
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(false);
        controls = new LinearLayout(c);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(12), dp(10), dp(12), dp(14));
        controls.setBackgroundColor(AdSkipUi.BG);
        controls.setAlpha(0.35f);
        scroll.addView(controls);

        range = new SegmentRangeView(c, AdSkipUi.ACCENT);
        range.setListener(new SegmentRangeView.Listener() {
            @Override
            public void rangeChanged(long startMs, long endMs, boolean fromUser, boolean dragging) {
                updateLabels();
                if (!dragging) {
                    stopAt = -1;
                }
            }

            @Override
            public void seekRequested(long ms, boolean dragging) {
                pause();
                seek(ms);
                if (!dragging) previewAround(ms);
            }
        });
        controls.addView(range, new LinearLayout.LayoutParams(-1, -2));

        // Start, length, end.
        LinearLayout times = new LinearLayout(c);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.setGravity(Gravity.CENTER_VERTICAL);
        times.setPadding(0, dp(6), 0, dp(4));
        startLabel = AdSkipUi.text(c, "", 13, AdSkipUi.TEXT, true);
        lengthLabel = AdSkipUi.text(c, "", 13, AdSkipUi.ACCENT, true);
        lengthLabel.setGravity(Gravity.CENTER);
        endLabel = AdSkipUi.text(c, "", 13, AdSkipUi.TEXT, true);
        endLabel.setGravity(Gravity.END);
        times.addView(startLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(lengthLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(endLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        controls.addView(times);

        // Fine tuning.
        LinearLayout nudges = new LinearLayout(c);
        nudges.setOrientation(LinearLayout.HORIZONTAL);
        nudges.setGravity(Gravity.CENTER_VERTICAL);
        addNudge(nudges, "−0.1", true, -100);
        addNudge(nudges, "+0.1", true, 100);
        TextView here = AdSkipUi.chip(c, L10n.t("Start here"), false);
        here.setOnClickListener(v -> setEdge(true, currentMs()));
        nudges.addView(here, gap());
        View spacer = new View(c);
        nudges.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView endHere = AdSkipUi.chip(c, L10n.t("End here"), false);
        endHere.setOnClickListener(v -> setEdge(false, currentMs()));
        nudges.addView(endHere, gap());
        addNudge(nudges, "−0.1", false, -100);
        addNudge(nudges, "+0.1", false, 100);
        controls.addView(nudges);

        // Playback.
        LinearLayout play = new LinearLayout(c);
        play.setOrientation(LinearLayout.HORIZONTAL);
        play.setGravity(Gravity.CENTER_VERTICAL);
        play.setPadding(0, dp(10), 0, 0);
        playButton = AdSkipUi.chip(c, "▶  " + L10n.t("Play"), false);
        playButton.setOnClickListener(v -> togglePlay());
        play.addView(playButton, new LinearLayout.LayoutParams(0, -2, 1f));
        playAdButton = AdSkipUi.chip(c, "▶  " + L10n.t("Play ad"), true);
        playAdButton.setOnClickListener(v -> playSelection());
        LinearLayout.LayoutParams playAdParams = new LinearLayout.LayoutParams(0, -2, 1.3f);
        playAdParams.leftMargin = dp(8);
        play.addView(playAdButton, playAdParams);
        zoomButton = AdSkipUi.chip(c, "⤢", false);
        zoomButton.setContentDescription(L10n.t("Zoom"));
        zoomButton.setOnClickListener(v -> {
            range.zoom(!range.zoomed());
            zoomButton.setText(range.zoomed() ? "⤡" : "⤢");
        });
        play.addView(zoomButton, gap());
        controls.addView(play);

        // Name.
        TextView nameTitle = AdSkipUi.text(c, L10n.t("Name"), 12, AdSkipUi.TEXT_2, false);
        nameTitle.setPadding(dp(4), dp(14), 0, dp(4));
        controls.addView(nameTitle);
        nameField = new EditText(c);
        nameField.setSingleLine(true);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameField.setTextColor(AdSkipUi.TEXT);
        nameField.setHintTextColor(AdSkipUi.TEXT_2);
        nameField.setHint(L10n.t("For example, the brand"));
        nameField.setText(defaultName());
        nameField.setBackground(AdSkipUi.round(AdSkipUi.SURFACE, dp(12)));
        nameField.setPadding(dp(14), dp(10), dp(14), dp(10));
        controls.addView(nameField, new LinearLayout.LayoutParams(-1, -2));

        hint = AdSkipUi.text(c, L10n.t("Tip: mark only the ad itself. Its sound is what gets matched, even under music or noise."),
                12, AdSkipUi.TEXT_2, false);
        hint.setPadding(dp(4), dp(10), dp(4), 0);
        controls.addView(hint);

        root.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setStatusBarColor(AdSkipUi.BG);
            window.setNavigationBarColor(AdSkipUi.BG);
        }
        dialog.setOnDismissListener(d -> release());
        AdSkip.setPaused(true);
        AdSkip.setFeedPlaying(false);
        enableControls(false);
        dialog.show();

        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = new Surface(st);
                if (picture != null) picture.setSurface(surface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                fitVideo();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (picture != null) picture.setSurface(null);
                if (surface != null) surface.release();
                surface = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        videoBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitVideo());
        load();
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = dp(6);
        return params;
    }

    private void addNudge(LinearLayout row, String label, boolean start, long delta) {
        TextView chip = AdSkipUi.chip(activity, label, false);
        chip.setOnClickListener(v -> setEdge(start, (start ? range.start() : range.end()) + delta));
        row.addView(chip, gap());
    }

    private String defaultName() {
        Object author = Reflect.property(aweme, "getAuthor", "author");
        String nick = Reflect.string(author, "getNickname", "nickname");
        String base = L10n.t("Ad");
        int n = AdSegmentStore.size() + 1;
        return nick == null ? base + " " + n : base + " " + n + " · " + nick;
    }

    private void enableControls(boolean enabled) {
        controls.setAlpha(enabled ? 1f : 0.35f);
        setEnabledDeep(controls, enabled);
        nameField.setEnabled(true);
    }

    private static void setEnabledDeep(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabledDeep(group.getChildAt(i), enabled);
        }
    }

    // endregion

    // region loading

    private void load() {
        Utils.runOnOwnThread("metra-adskip-editor", () -> {
            try {
                Context context = activity.getApplicationContext();
                AdAudioSource.sweep(context);
                setStatus(L10n.t("Downloading the video…"));
                File pic = AdAudioSource.fetchUrls(context, AdAudioSource.videoUrls(aweme), "edit-video");
                if (cancel.get()) { pic.delete(); return; }
                File snd = null;
                if (AdAudioSource.separateSound(aweme) || !AdAudioSource.hasSound(pic)) {
                    setStatus(L10n.t("Downloading the sound…"));
                    snd = AdAudioSource.fetch(context, aweme, "edit-sound");
                }
                final File soundSource = snd != null ? snd : pic;
                pictureFile = pic;
                soundFile = snd;

                setStatus(L10n.t("Reading the sound…"));
                AdAudioSource.Pcm pcm = AdAudioSource.decode(soundSource, Landmarks.SAMPLE_RATE, MAX_LOAD_MS, cancel);
                if (cancel.get()) return;
                float[] envelope = envelope(pcm);
                Landmarks.Print full = Landmarks.compute(pcm.samples, pcm.length);
                long audioMs = pcm.durationMs();
                main.post(() -> {
                    if (cancel.get()) return;
                    print = full;
                    preparePlayers(audioMs);
                    range.setEnvelope(envelope);
                });

                Bitmap[] thumbs = thumbs(pic);
                main.post(() -> {
                    if (!cancel.get()) range.setThumbs(thumbs);
                });
            } catch (Throwable failure) {
                Logger.printException(() -> "Ad skip: the editor could not load the video", failure);
                main.post(() -> {
                    if (cancel.get()) return;
                    spinner.setVisibility(View.GONE);
                    status.setText(L10n.t("Couldn't load this video. Check the connection and try again."));
                    status.setTextColor(AdSkipUi.WARN);
                });
            }
        });
    }

    private void setStatus(String text) {
        main.post(() -> {
            if (!cancel.get()) status.setText(text);
        });
    }

    /** The loudest sample in each 20 ms, scaled so the loudest window fills the strip. */
    private static float[] envelope(AdAudioSource.Pcm pcm) {
        int window = Math.max(1, pcm.sampleRate * ENVELOPE_MS / 1000);
        int n = Math.max(1, pcm.length / window);
        float[] values = new float[n];
        float max = 1;
        for (int i = 0; i < n; i++) {
            int peak = 0;
            int from = i * window, to = Math.min(pcm.length, from + window);
            for (int k = from; k < to; k++) {
                int v = Math.abs(pcm.samples[k]);
                if (v > peak) peak = v;
            }
            values[i] = peak;
            if (peak > max) max = peak;
        }
        for (int i = 0; i < n; i++) values[i] = (float) Math.sqrt(values[i] / max);
        return values;
    }

    private Bitmap[] thumbs(File file) {
        Bitmap[] out = new Bitmap[THUMBS];
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long duration = d == null ? 0 : Long.parseLong(d);
            if (duration <= 0) return out;
            for (int i = 0; i < THUMBS && !cancel.get(); i++) {
                long us = (duration * 1000L) * (2L * i + 1) / (2L * THUMBS);
                Bitmap frame;
                if (Build.VERSION.SDK_INT >= 27) {
                    frame = retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 284);
                } else {
                    Bitmap big = retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    frame = big == null ? null : Bitmap.createScaledBitmap(big, 160,
                            Math.max(1, Math.round(160f * big.getHeight() / big.getWidth())), true);
                    if (big != null && big != frame) big.recycle();
                }
                out[i] = frame;
                final Bitmap[] partial = out.clone();
                main.post(() -> {
                    if (!cancel.get()) range.setThumbs(partial);
                });
            }
        } catch (Throwable failure) {
            Logger.printDebug(() -> "Ad skip: no frames for the strip");
        } finally {
            try { retriever.release(); } catch (Throwable ignored) { }
        }
        return out;
    }

    private void preparePlayers(long audioMs) {
        try {
            picture = new MediaPlayer();
            picture.setDataSource(pictureFile.getAbsolutePath());
            if (surface != null) picture.setSurface(surface);
            if (soundFile != null) picture.setVolume(0f, 0f);
            picture.setOnVideoSizeChangedListener((mp, w, h) -> {
                videoWidth = w;
                videoHeight = h;
                fitVideo();
            });
            picture.setOnSeekCompleteListener(mp -> {
                seeking = false;
                if (pendingSeek >= 0) {
                    long next = pendingSeek;
                    pendingSeek = -1;
                    seek(next);
                }
            });
            picture.setOnCompletionListener(mp -> {
                pauseSound();
                updatePlayButtons();
            });
            picture.setOnPreparedListener(mp -> {
                if (cancel.get()) return;
                if (soundFile != null) {
                    try {
                        sound = new MediaPlayer();
                        sound.setDataSource(soundFile.getAbsolutePath());
                        sound.prepare();
                    } catch (Exception failure) {
                        Logger.printException(() -> "Ad skip: no separate sound player", failure);
                        sound = null;
                        picture.setVolume(1f, 1f);
                    }
                }
                prepared = true;
                long videoMs = picture.getDuration();
                durationMs = videoMs > 0 ? Math.min(videoMs, Math.max(audioMs, 1)) : audioMs;
                if (videoMs > 0 && audioMs > 0) durationMs = Math.min(videoMs, audioMs);
                range.setDuration(durationMs);
                long start = Math.min(initialPositionMs, Math.max(0, durationMs - MIN_LENGTH_MS));
                long end = Math.min(durationMs, start + DEFAULT_LENGTH_MS);
                if (end - start < MIN_LENGTH_MS) start = Math.max(0, end - DEFAULT_LENGTH_MS);
                range.setRange(start, end);
                updateLabels();
                spinner.setVisibility(View.GONE);
                status.setVisibility(View.GONE);
                enableControls(true);
                AdSkipUi.enable(saveButton, true);
                seek(start);
                main.post(ticker);
            });
            picture.prepareAsync();
        } catch (Exception failure) {
            Logger.printException(() -> "Ad skip: could not play the video in the editor", failure);
            spinner.setVisibility(View.GONE);
            status.setText(L10n.t("Couldn't play this video."));
        }
    }

    private void fitVideo() {
        if (videoWidth <= 0 || videoHeight <= 0 || texture == null) return;
        float boxW = videoBox.getWidth(), boxH = videoBox.getHeight();
        if (boxW <= 0 || boxH <= 0) return;
        float scale = Math.min(boxW / videoWidth, boxH / videoHeight);
        float w = videoWidth * scale, h = videoHeight * scale;
        Matrix matrix = new Matrix();
        matrix.setScale(w / boxW, h / boxH, boxW / 2f, boxH / 2f);
        texture.setTransform(matrix);
        texture.invalidate();
    }

    // endregion

    // region playback

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (cancel.get() || picture == null) return;
            try {
                long now = currentMs();
                range.setPlayhead(now);
                positionLabel.setText(AdSkipUi.time(now, true) + " / " + AdSkipUi.time(durationMs, false));
                if (picture.isPlaying()) {
                    if (stopAt >= 0 && now >= stopAt) {
                        pause();
                        seek(range.start());
                        stopAt = -1;
                    } else if (sound != null && sound.isPlaying()
                            && Math.abs(sound.getCurrentPosition() - now) > 120) {
                        sound.seekTo((int) now);
                    }
                }
            } catch (IllegalStateException ignored) {
                // A player being released.
            }
            main.postDelayed(this, 33);
        }
    };

    private long currentMs() {
        if (picture == null || !prepared) return 0;
        if (seeking && pendingSeek >= 0) return pendingSeek;
        try {
            return picture.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    private void seek(long ms) {
        if (picture == null || !prepared) return;
        ms = Math.max(0, Math.min(durationMs, ms));
        if (seeking) {
            pendingSeek = ms;
            return;
        }
        seeking = true;
        try {
            if (Build.VERSION.SDK_INT >= 26) picture.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
            else picture.seekTo((int) ms);
            if (sound != null) {
                if (Build.VERSION.SDK_INT >= 26) sound.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
                else sound.seekTo((int) ms);
            }
        } catch (IllegalStateException e) {
            seeking = false;
        }
        range.setPlayhead(ms);
    }

    private void play() {
        if (picture == null || !prepared) return;
        try {
            if (sound != null) {
                sound.seekTo(picture.getCurrentPosition());
                sound.start();
            }
            picture.start();
        } catch (IllegalStateException ignored) {
            // Released.
        }
        updatePlayButtons();
    }

    private void pause() {
        if (picture == null || !prepared) return;
        try {
            if (picture.isPlaying()) picture.pause();
        } catch (IllegalStateException ignored) {
            // Released.
        }
        pauseSound();
        updatePlayButtons();
    }

    private void pauseSound() {
        try {
            if (sound != null && sound.isPlaying()) sound.pause();
        } catch (IllegalStateException ignored) {
            // Released.
        }
    }

    private boolean isPlaying() {
        try {
            return picture != null && prepared && picture.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void togglePlay() {
        if (!prepared) return;
        if (isPlaying()) {
            pause();
            stopAt = -1;
        } else {
            stopAt = -1;
            play();
        }
    }

    private void playSelection() {
        if (!prepared) return;
        if (isPlaying() && stopAt >= 0) {
            pause();
            stopAt = -1;
            return;
        }
        stopAt = range.end();
        seek(range.start());
        main.postDelayed(this::play, 80);
    }

    /** After a handle lets go, a second and a half of sound from that edge. */
    private void previewAround(long ms) {
        if (!prepared) return;
        boolean isEnd = Math.abs(ms - range.end()) < 5;
        long from = isEnd ? Math.max(range.start(), ms - 1500) : ms;
        stopAt = isEnd ? ms : Math.min(range.end(), ms + 1500);
        seek(from);
        main.postDelayed(this::play, 80);
        main.postDelayed(() -> {
            if (stopAt >= 0 && currentMs() >= stopAt - 40) {
                pause();
                stopAt = -1;
            }
        }, 1700);
    }

    private void updatePlayButtons() {
        boolean playing = isPlaying();
        boolean selection = playing && stopAt >= 0;
        playButton.setText((playing && !selection ? "❚❚  " : "▶  ") + L10n.t(playing && !selection ? "Pause" : "Play"));
        playAdButton.setText((selection ? "❚❚  " : "▶  ") + L10n.t(selection ? "Stop" : "Play ad"));
    }

    private void setEdge(boolean start, long ms) {
        if (!prepared) return;
        long s = range.start(), e = range.end();
        if (start) s = Math.max(0, Math.min(ms, e - 500));
        else e = Math.min(durationMs, Math.max(ms, s + 500));
        range.setRange(s, e);
        updateLabels();
        pause();
        seek(start ? s : e);
        previewAround(start ? s : e);
    }

    private void updateLabels() {
        long s = range.start(), e = range.end();
        startLabel.setText("⟦ " + AdSkipUi.time(s, true));
        endLabel.setText(AdSkipUi.time(e, true) + " ⟧");
        lengthLabel.setText(L10n.f("%1$s s", AdSkipUi.seconds(e - s)));
    }

    // endregion

    // region saving

    private void save() {
        if (!prepared || print == null) return;
        long s = range.start(), e = range.end();
        if (e - s < MIN_LENGTH_MS) {
            warn(L10n.t("The ad must be at least 2 seconds long."));
            return;
        }
        Landmarks.Print cut = print.slice(Landmarks.msToFrame(s), Landmarks.msToFrame(e));
        if (cut.count < MIN_HASHES) {
            warn(L10n.t("There's too little sound here to recognise. Choose a louder part."));
            return;
        }
        String name = nameField.getText().toString().trim();
        if (name.isEmpty()) name = defaultName();
        pause();

        AdSegment segment = AdSegmentStore.add(name, e - s, aid, cut);
        // The cut must be found again in its own video, at its own place.
        List<AdMatcher.Match> check = AdMatcher.match(AdMatcher.Index.build(java.util.Collections.singletonList(segment)),
                print, AdMatcher.Strictness.NORMAL);
        boolean found = false;
        for (AdMatcher.Match m : check) if (Math.abs(m.startMs - s) < 1500) found = true;
        AdSkipLog.add(aid, "saved \"" + name + "\" " + cut.count + " hashes, self-check " + (found ? "ok" : "weak"));
        AdScanner.forget(aid);
        hideKeyboard();
        dialog.dismiss();
        Utils.showToastLong(found
                ? L10n.f("Saved \"%1$s\". This ad will be skipped in other videos.", name)
                : L10n.f("Saved \"%1$s\", but its sound is weak. A longer or louder cut is found more reliably.", name));
    }

    private void warn(String message) {
        hint.setText(message);
        hint.setTextColor(AdSkipUi.WARN);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(nameField.getWindowToken(), 0);
        } catch (Throwable ignored) {
            // Nothing to hide.
        }
    }

    // endregion

    private void release() {
        cancel.set(true);
        main.removeCallbacksAndMessages(null);
        for (MediaPlayer player : new MediaPlayer[]{picture, sound}) {
            if (player == null) continue;
            try { player.release(); } catch (Throwable ignored) { }
        }
        picture = null;
        sound = null;
        if (pictureFile != null) //noinspection ResultOfMethodCallIgnored
            pictureFile.delete();
        if (soundFile != null) //noinspection ResultOfMethodCallIgnored
            soundFile.delete();
        AdSkip.setPaused(false);
        AdSkip.setFeedPlaying(true);
    }

    private int dp(float v) {
        return AdSkipUi.dp(activity, v);
    }

    @SuppressWarnings("unused")
    private static String date(long ms) {
        return DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(ms));
    }
}
