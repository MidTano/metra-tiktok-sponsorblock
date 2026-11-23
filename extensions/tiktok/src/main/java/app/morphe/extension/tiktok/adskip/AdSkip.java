/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The patch's hooks: TikTok's player names each video it plays and reports its position several
 * times a second, and the feed binds the posts either side of the one on screen. Bound posts are
 * scanned ahead, the playing one first, and a progress tick inside a found ad seeks past it.
 */
public final class AdSkip {
    private static final String PAGER = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager";
    /** A tick this close to a found ad's start counts as inside it. */
    private static final long LEAD_MS = 150;
    /** Nothing is skipped in the ad's last moments, where the seek would gain nothing. */
    private static final long TAIL_MS = 400;
    /** An ad that covers this much of a video makes the whole video an ad. */
    private static final float WHOLE_VIDEO = 0.85f;

    private static volatile WeakReference<Object> controller = new WeakReference<>(null);
    private static volatile WeakReference<Object> playingAweme = new WeakReference<>(null);
    private static volatile String playingAid;
    private static volatile long positionMs;
    private static volatile long durationMs;

    /** Per playing video: the matches already skipped this play, and the ones put back. */
    private static String sessionAid;
    private static final Set<Long> skipped = new HashSet<>();
    private static final Set<Long> allowed = new HashSet<>();
    private static long lastTick = -1;
    private static boolean advancedAway;

    private static volatile boolean paused;

    private static Class<?> seekOwner;
    private static Method seekMethod;

    private AdSkip() {}

    static boolean active() {
        return SettingsStatus.adSkipEnabled && Settings.AD_SKIP.get() && !paused;
    }

    /** The editor plays the video itself; nothing is skipped behind it. */
    static void setPaused(boolean value) {
        paused = value;
    }

    // region hooks

    /** {@code PlayerController.play(Aweme, ...)}, before the engine starts. */
    public static void onPlay(Object playerController, Object aweme) {
        try {
            if (aweme == null) return;
            String aid = Reflect.string(aweme, "getAid", "aid");
            if (aid == null) return;
            playingAweme = new WeakReference<>(aweme);
            if (!active()) return;
            if (playerController != null) controller = new WeakReference<>(playerController);
            if (!shouldScan()) return;
            AdScanner.request(aweme, true);
        } catch (Throwable failure) {
            Logger.printException(() -> "Ad skip: play hook failed", failure);
        }
    }

    /** {@code VideoAuthorInfoVM.paramSync2StateAccept(VideoItemParams)}: the feed bound a post. */
    public static void onBind(Object videoItemParams) {
        try {
            if (!active() || !Settings.AD_SKIP_PRESCAN.get() || !shouldScan()) return;
            Object aweme = Reflect.property(videoItemParams, "getAweme", "aweme");
            if (aweme != null) AdScanner.request(aweme, false);
        } catch (Throwable failure) {
            Logger.printException(() -> "Ad skip: bind hook failed", failure);
        }
    }

    /** {@code PlayerController.onPlayProgressChange(sourceId, position, duration)}. */
    public static void onProgress(Object playerController, String aid, long position, long duration) {
        if (playerController == null || aid == null || duration <= 0) return;
        controller = new WeakReference<>(playerController);
        playingAid = aid;
        positionMs = position;
        durationMs = duration;
        if (!active()) return;
        try {
            tick(aid, position, duration);
        } catch (Throwable failure) {
            Logger.printException(() -> "Ad skip: progress hook failed", failure);
        }
    }

    // endregion

    private static boolean shouldScan() {
        if (AdSegmentStore.index().isEmpty()) return false;
        if (!Settings.AD_SKIP_MOBILE_DATA.get() && Utils.getNetworkType() == Utils.NetworkType.MOBILE) return false;
        return true;
    }

    private static synchronized void tick(String aid, long position, long duration) {
        if (!aid.equals(sessionAid)) {
            sessionAid = aid;
            skipped.clear();
            allowed.clear();
            lastTick = -1;
            advancedAway = false;
            // A video that started before the play hook saw it, a resumed one for instance.
            Object aweme = playingAweme.get();
            if (aweme != null && aid.equals(Reflect.string(aweme, "getAid", "aid")) && shouldScan()) {
                AdScanner.request(aweme, true);
            }
        }
        // Played round to the start again, or the reader dragged back: the ads may go again.
        if (lastTick >= 0 && position + 1500 < lastTick) skipped.clear();
        lastTick = position;

        AdScanner.Result result = AdScanner.result(aid);
        if (result == null || result.state != AdScanner.State.DONE || result.matches.isEmpty()) return;
        List<AdMatcher.Match> matches = result.matches;
        for (AdMatcher.Match match : matches) {
            long key = match.startMs;
            if (skipped.contains(key) || allowed.contains(key)) continue;
            long end = Math.min(match.endMs, duration);
            if (position < match.startMs - LEAD_MS || position >= end - TAIL_MS) continue;
            skipped.add(key);
            boolean whole = (end - Math.max(0, match.startMs)) >= duration * WHOLE_VIDEO
                    || end >= duration - 700;
            if (whole && Settings.AD_SKIP_WHOLE_VIDEO.get()) {
                if (advancedAway) return;
                advancedAway = true;
                Utils.runOnMainThread(() -> {
                    if (advance()) {
                        AdSegmentStore.recordSkip(match.segment);
                        AdSkipLog.add(aid, "whole video is " + match.segment.name + ", moved on");
                        notice(L10n.f("Skipped an ad video: %1$s", match.segment.name), null);
                    } else {
                        seekPast(aid, match, Math.max(0, duration - 1), duration);
                    }
                });
                return;
            }
            seekPast(aid, match, end, duration);
            return;
        }
    }

    private static void seekPast(String aid, AdMatcher.Match match, long target, long duration) {
        long landing = Math.min(target, duration - 1);
        Utils.runOnMainThread(() -> {
            if (!aid.equals(playingAid)) return;
            if (!seekTo(landing, duration)) {
                AdSkipLog.add(aid, "could not seek");
                return;
            }
            AdSegmentStore.recordSkip(match.segment);
            long seconds = Math.max(1, Math.round((landing - Math.max(positionMs, match.startMs)) / 1000.0));
            AdSkipLog.add(aid, "skipped " + match.segment.name + " to " + landing + "ms");
            notice(L10n.f("Skipped %1$s s of ad: %2$s", String.valueOf(seconds), match.segment.name), () -> {
                synchronized (AdSkip.class) {
                    allowed.add(match.startMs);
                }
                seekTo(Math.max(0, match.startMs), duration);
            });
        });
    }

    private static void notice(String message, Runnable undo) {
        if (!Settings.AD_SKIP_NOTICE.get()) return;
        try {
            if (undo != null) BlockAuthorOverlay.showActionBanner(message, L10n.t("Watch it"), undo);
            else BlockAuthorOverlay.showActionBanner(message, null, null);
        } catch (Throwable failure) {
            Utils.showToastShort(message);
        }
    }

    /** Seeks the player that last reported progress. Main thread. */
    static boolean seekTo(long targetMs, long duration) {
        Object current = controller.get();
        if (current == null || duration <= 0) return false;
        Object manager = Reflect.invoke(current, "getPlayerManager");
        if (manager == null) return false;
        try {
            Method method = seekMethod(manager.getClass());
            if (method == null) return false;
            long target = Math.max(0, Math.min(duration - 1, targetMs));
            method.invoke(manager, (float) target / duration * 100f);
            positionMs = target;
            synchronized (AdSkip.class) {
                lastTick = target;
            }
            return true;
        } catch (Exception failure) {
            Logger.printException(() -> "Ad skip: could not seek", failure);
            return false;
        }
    }

    private static Method seekMethod(Class<?> type) {
        if (type == seekOwner) return seekMethod;
        seekOwner = type;
        seekMethod = null;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod("seek", float.class);
                method.setAccessible(true);
                seekMethod = method;
                break;
            } catch (NoSuchMethodException ignored) {
                // keep climbing
            } catch (Throwable ignored) {
                break;
            }
        }
        return seekMethod;
    }

    /** Moves the feed's pager on by one, as a swipe would. Main thread. */
    private static boolean advance() {
        Activity activity = Utils.getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        View pager = findPager(activity.findViewById(android.R.id.content));
        if (pager == null || !pager.canScrollVertically(1)) return false;
        try {
            Object position = Reflect.invoke(pager, "getCurrentItem");
            if (!(position instanceof Integer)) return false;
            int next = (Integer) position + 1;
            pager.getClass().getMethod("setCurrentItem", int.class).invoke(pager, next);
            return Integer.valueOf(next).equals(Reflect.invoke(pager, "getCurrentItem"));
        } catch (Exception failure) {
            Logger.printException(() -> "Ad skip: could not move to the next video", failure);
            return false;
        }
    }

    private static View findPager(View view) {
        if (view == null || !view.isShown() || !view.getGlobalVisibleRect(new Rect())) return null;
        if (PAGER.equals(view.getClass().getName())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findPager(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    // region state for the editor

    static long playingPositionFor(String aid) {
        return aid != null && aid.equals(playingAid) ? positionMs : -1;
    }

    static Object playingAweme() {
        return playingAweme.get();
    }

    /** Whether TikTok's own player is playing; the editor pauses it while it plays its copy. */
    static void setFeedPlaying(boolean play) {
        try {
            app.morphe.extension.tiktok.wellbeing.SessionPlaybackHold.setCurrentPlaying(play);
        } catch (Throwable ignored) {
            // Best effort: the bridge is part of the patch, but a missing member must not crash.
        }
    }

    // endregion
}
