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
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Player and feed hooks that trigger background audio scanning and automatic ad seeking. */
public final class AdSkip {
    private static final String PAGER = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager";
    private static final float WHOLE_VIDEO = 0.85f;

    private static volatile WeakReference<Object> controller = new WeakReference<>(null);
    private static volatile WeakReference<Object> playingAweme = new WeakReference<>(null);
    private static volatile String playingAid;
    private static volatile String startedAid;
    private static volatile long positionMs;
    private static volatile long durationMs;

    private static String sessionAid;
    private static final Set<Long> skipped = new HashSet<>();
    private static final List<AdMatcher.Match> allowed = new ArrayList<>();
    private static Object videoSession;
    private static PendingSkip pending;

    private static final class PendingSkip {
        final Object playback;
        final AdMatcher.Match match;
        final int generation;
        long target, from, duration;
        long issuedAt = -1;
        boolean seekAccepted, advanced;

        PendingSkip(Object playback, AdMatcher.Match match, int generation) {
            this.playback = playback;
            this.match = match;
            this.generation = generation;
        }
    }

    private static volatile boolean paused;
    private static Integer requestedGeneration;
    private static long nextScanRequestAt;
    private static volatile Object playback = new Object();

    private static final long END_GUARD_MS = 600;
    private static final long ON_SCREEN_MS = 1500;

    private static Class<?> seekOwner;
    private static Method seekMethod;

    private AdSkip() {}

    static boolean active() {
        return SettingsStatus.adSkipEnabled && Settings.AD_SKIP.get() && !paused;
    }

    /** The editor plays the video itself; nothing is skipped behind it. */
    static void setPaused(boolean value) {
        if (paused != value) playback = new Object();
        paused = value;
    }

    // region hooks

    /** {@code PlayerController.play(Aweme, ...)}, before the engine starts. */
    public static void onPlay(Object playerController, Object aweme) {
        try {
            // The selected patch's player hook also runs before settings have been opened.
            SettingsStatus.enableAdSkip();
            SharedDb.syncIfDue();
            KawaiiLottie.warmUpAsync();
            if (aweme == null) return;
            String aid = Reflect.string(aweme, "getAid", "aid");
            if (aid == null) return;
            startedAid = aid;
            playback = new Object();
            playingAweme = new WeakReference<>(aweme);
            if (!active()) return;
            if (playerController != null) controller = new WeakReference<>(playerController);
            if (Settings.AD_SKIP_SAVE_TRAFFIC.get() || !mayDownload()) {
                AdScanner.restoreInBackground();
                return;
            }
            AdScanner.request(aweme, true);
        } catch (Throwable failure) {
            Logger.printException(() -> "Ad skip: play hook failed", failure);
        }
    }

    /** {@code VideoAuthorInfoVM.paramSync2StateAccept(VideoItemParams)}: the feed bound a post. */
    public static void onBind(Object videoItemParams) {
        try {
            if (!active() || !Settings.AD_SKIP_PRESCAN.get() || !mayDownload()) return;
            if (Settings.AD_SKIP_SAVE_TRAFFIC.get() && Utils.getNetworkType() != Utils.NetworkType.OTHER) return;
            Object aweme = Reflect.property(videoItemParams, "getAweme", "aweme");
            if (aweme != null) AdScanner.request(aweme, false);
        } catch (Throwable failure) {
            Logger.printException(() -> "Ad skip: bind hook failed", failure);
        }
    }

    /** {@code PlayerController.onPlayProgressChange(sourceId, position, duration)}. */
    public static synchronized void onProgress(Object playerController, String aid, long position, long duration) {
        if (playerController == null || aid == null || duration <= 0) return;
        if (startedAid != null && !startedAid.equals(aid)) return;
        controller = new WeakReference<>(playerController);
        playingAid = aid;
        positionMs = position;
        durationMs = duration;
        if (paused) {
            holdFeed();
            return;
        }
        if (!active()) return;
        try {
            tick(aid, position, duration);
        } catch (Throwable failure) {
            Logger.printException(() -> "Ad skip: progress hook failed", failure);
        }
    }

    // endregion

    static synchronized void scanDeferredByNetwork(String aid, int generation) {
        if (aid.equals(sessionAid) && requestedGeneration != null && requestedGeneration == generation) {
            requestedGeneration = null;
        }
    }

    /** Whether the network in use allows a scan's download now; asked again when a queued scan starts. */
    static boolean mayDownload() {
        return Settings.AD_SKIP_MOBILE_DATA.get() || Utils.getNetworkType() != Utils.NetworkType.MOBILE;
    }

    private static synchronized void tick(String aid, long position, long duration) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!aid.equals(sessionAid)) {
            // The notice belongs to the video before: its Watch it must not seek this one.
            if (sessionAid != null) Utils.runOnMainThread(AdSkipToast::dismiss);
            sessionAid = aid;
            skipped.clear();
            allowed.clear();
            pending = null;
            videoSession = new Object();
            requestedGeneration = null;
            nextScanRequestAt = 0;
            playback = new Object();
        }
        int generation = AdScanner.stamp();
        if ((requestedGeneration == null || requestedGeneration != generation) && now >= nextScanRequestAt
                && position >= (Settings.AD_SKIP_SAVE_TRAFFIC.get() ? ON_SCREEN_MS : 0)) {
            nextScanRequestAt = now + 500;
            Object aweme = playingAweme.get();
            if (aweme != null && aid.equals(Reflect.string(aweme, "getAid", "aid")) && mayDownload()) {
                requestedGeneration = generation;
                AdScanner.request(aweme, true);
            } else {
                AdScanner.restoreInBackground();
            }
        }
        if (pending != null) {
            PendingSkip attempt = pending;
            if (attempt.playback != playback || attempt.generation != generation || !eligible(attempt.match)) {
                pending = null;
            } else if (attempt.issuedAt < 0) {
                return;
            } else if (!attempt.advanced && position >= attempt.target) {
                pending = null;
                Object session = videoSession;
                if (attempt.seekAccepted) Utils.runOnMainThread(() -> skippedOnce(session, aid, attempt));
            } else if (AdSkipPlayback.waitingForSeek(now, attempt.issuedAt, position,
                    attempt.match.startMs, attempt.advanced)) {
                return;
            } else {
                pending = null; // Unconfirmed seek or re-entry: the marker stays eligible.
            }
        }

        AdScanner.Result result = AdScanner.result(aid);
        if (result == null) return;
        for (AdMatcher.Match match : result.matches) {
            if (AdSkipPlayback.target(position, duration, match.startMs, match.endMs) < 0 || !eligible(match)) continue;
            long end = Math.min(match.endMs, duration);
            // Only a complete scan may classify the entire video as an ad. A tail ad just seeks.
            boolean whole = result.state == AdScanner.State.COMPLETE
                    && result.scannedMs >= duration - END_GUARD_MS
                    && match.startMs <= END_GUARD_MS && end >= duration - END_GUARD_MS
                    && (end - Math.max(0, match.startMs)) >= duration * WHOLE_VIDEO;
            PendingSkip attempt = new PendingSkip(playback, match, result.generation);
            pending = attempt;
            Utils.runOnMainThread(() -> applySkip(aid, attempt, whole));
            return;
        }
    }

    private static boolean eligible(AdMatcher.Match match) {
        if (!AdSkipPlayback.canSkip(match.segment.enabled, SharedDb.myVote(match.segment.sharedServerId))) return false;
        for (AdMatcher.Match watched : allowed) {
            if (AdSkipPlayback.isWatchOverride(watched.segment.id, watched.startMs, watched.endMs,
                    match.segment.id, match.startMs, match.endMs)) return false;
        }
        return true;
    }

    private static synchronized void applySkip(String aid, PendingSkip attempt, boolean whole) {
        if (pending != attempt) return;
        long position = positionMs;
        AdMatcher.Match match = attempt.match;
        long duration = durationMs;
        long target = AdSkipPlayback.target(position, duration, match.startMs, match.endMs);
        if (attempt.playback != playback || !active() || !aid.equals(playingAid)
                || (startedAid != null && !aid.equals(startedAid))
                || attempt.generation != AdScanner.stamp() || target < 0 || !eligible(match)) {
            pending = null;
            return;
        }
        attempt.target = target;
        attempt.from = Math.max(position, match.startMs);
        attempt.duration = duration;
        attempt.issuedAt = android.os.SystemClock.uptimeMillis();
        try {
            if (whole && Settings.AD_SKIP_WHOLE_VIDEO.get() && advance()) {
                attempt.advanced = true;
                AdSegmentStore.recordSkip(match.segment, Math.max(0, duration - position));
                AdSkipLog.add(aid, "whole video is " + match.segment.name + ", moved on");
                notice(match.segment.name, match.segment.sharedServerId, null);
                return;
            }
            attempt.seekAccepted = seekTo(target, duration);
            if (!attempt.seekAccepted) AdSkipLog.add(aid, "could not seek; will retry inside the ad");
        } catch (Throwable failure) {
            Logger.printException(() -> "Ad skip: could not skip ad", failure);
        }
    }

    private static synchronized void skippedOnce(Object session, String aid, PendingSkip attempt) {
        AdMatcher.Match match = attempt.match;
        if (session != videoSession || !aid.equals(playingAid) || !eligible(match)) return;
        skipped.add(match.startMs); // For the bar only; never prevents another skip.
        long savedMs = attempt.target - attempt.from;
        AdSegmentStore.recordSkip(match.segment, savedMs);
        AdSkipLog.add(aid, "skipped " + match.segment.name + ", saved " + savedMs + "ms");
        notice(match.segment.name, match.segment.sharedServerId, () -> {
            synchronized (AdSkip.class) {
                if (session != videoSession || !aid.equals(playingAid)
                        || (startedAid != null && !aid.equals(startedAid))) return;
                allowed.add(match);
                pending = null;
                seekTo(Math.max(0, match.startMs), attempt.duration);
            }
        });
    }

    private static void notice(String name, String serverId, Runnable undo) {
        AdSkipBar.refresh();
        if (!Settings.AD_SKIP_NOTICE.get()) return;
        AdSkipToast.show(name, serverId, undo);
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

    // region state for the editor and the bar

    static String playingAid() {
        return playingAid;
    }

    static long playingPosition() {
        return positionMs;
    }

    static long playingDuration() {
        return durationMs;
    }

    /** Whether this ad of this video was already skipped this play; drawn dimmer on the bar. */
    static synchronized boolean wasSkipped(String aid, long startMs) {
        return aid != null && aid.equals(sessionAid) && skipped.contains(startMs);
    }

    static long playingPositionFor(String aid) {
        return aid != null && aid.equals(playingAid) ? positionMs : -1;
    }

    static Object playingAweme() {
        return playingAweme.get();
    }

    /** Whether TikTok's own player is playing; the editor pauses it while it plays its copy. */
    private static long lastHold;

    /** Re-pauses the background feed player if TikTok resumes it while the editor is open. */
    private static void holdFeed() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastHold < 500) return;
        lastHold = now;
        Utils.runOnMainThread(() -> {
            if (paused) setFeedPlaying(false);
        });
    }

    static void setFeedPlaying(boolean play) {
        try {
            app.morphe.extension.tiktok.wellbeing.SessionPlaybackHold.setCurrentPlaying(play);
        } catch (Throwable ignored) {
            // Best effort: the bridge is part of the patch, but a missing member must not crash.
        }
    }

    // endregion
}
