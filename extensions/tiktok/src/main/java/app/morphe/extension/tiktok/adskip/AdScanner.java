/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.content.Context;
import android.os.Process;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.AdAudioSource;
import app.morphe.extension.tiktok.settings.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background audio scanner for feed videos. Prioritizes the active video on screen,
 * pre-scans adjacent feed items, and caches results per video ID and index generation.
 */
final class AdScanner {
    static final long MAX_SCAN_MS = 5L * 60 * 1000;
    private static final int CACHE = 300;
    private static final int MAX_QUEUE = 8;
    private static final float[] RATES = {1.05f, 0.95f, 1.10f, 0.90f};

    enum State { PARTIAL, COMPLETE, FAILED }

    static final class Result {
        final State state;
        final int generation;
        final List<AdMatcher.Match> matches;
        final long scannedMs;
        final long tookMs;
        final String error;
        // Accessed only under LOCK. Identity of the manual result is the merge version.
        private Result mergedFrom;
        private Result merged;

        Result(State state, int generation, List<AdMatcher.Match> matches, long scannedMs, long tookMs, String error) {
            this.state = state;
            this.generation = generation;
            this.matches = matches;
            this.scannedMs = scannedMs;
            this.tookMs = tookMs;
            this.error = error;
        }
    }

    interface Listener {
        void scanned(String aid, Result result);
    }

    private static final Map<String, Result> RESULTS = new LinkedHashMap<String, Result>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
            return size() > CACHE;
        }
    };
    private static final Map<String, Result> MARKED = new LinkedHashMap<String, Result>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
            return size() > CACHE;
        }
    };
    private static final Object LOCK = new Object();
    private static final LinkedList<Job> QUEUE = new LinkedList<>();
    private static final List<Job> RUNNING = new ArrayList<>();
    private static final LinkedList<String> WANTED = new LinkedList<>();
    private static final int WANTED_SIZE = 4;
    private static final Map<String, Job> JOBS = new java.util.HashMap<>();
    private static int workers;
    private static final int MAX_BACKGROUND_RUNNING = 2;
    private static final int MAX_WORKERS = 2;
    private static final int ALGORITHM = 5;
    private static final int MAX_WORKERS_URGENT = 3;
    private static final String CACHE_FILE = "metra-adskip-scans.json";
    private static boolean restored;
    private static boolean restoreScheduled;
    private static int cacheEpoch;
    private static volatile Listener listener;

    private static final class Job {
        final String aid;
        final WeakReference<Object> aweme;
        final int generation;
        final AtomicBoolean cancel = new AtomicBoolean();
        final long queuedAt = System.nanoTime();
        volatile boolean urgent;
        volatile int tid;
        boolean deferredByNetwork;

        Job(String aid, Object aweme, int generation) {
            this.aid = aid;
            this.generation = generation;
            this.aweme = new WeakReference<>(aweme);
        }
    }

    private AdScanner() {}

    static void setListener(Listener l) {
        listener = l;
    }

    /** Explicit user marks can be read independently of scan completion. */
    static Result result(String aid) {
        if (aid == null) return null;
        synchronized (LOCK) {
            int generation = stamp();
            Result r = RESULTS.get(aid);
            if (r != null && (r.generation != generation || r.state != State.COMPLETE)) r = null;
            Result marked = MARKED.get(aid);
            if (marked == null || marked.generation != generation) return r;
            if (r == null || r.matches.isEmpty()) return marked;
            if (r.mergedFrom == marked) return r.merged;
            List<AdMatcher.Match> combined = new ArrayList<>(marked.matches);
            for (AdMatcher.Match m : r.matches) {
                boolean overlaps = false;
                for (AdMatcher.Match manual : marked.matches) {
                    if (manual.startMs < m.endMs && m.startMs < manual.endMs) {
                        overlaps = true;
                        break;
                    }
                }
                if (!overlaps) combined.add(m);
            }
            combined.sort((x, y) -> Long.compare(x.startMs, y.startMs));
            r.merged = new Result(r.state, generation, Collections.unmodifiableList(combined),
                    Math.max(marked.scannedMs, r.scannedMs), r.tookMs, null);
            r.mergedFrom = marked;
            return r.merged;
        }
    }

    /** Cache generation token combining segment-list generation, strictness, and retimed setting. */
    static int stamp() {
        int config = AdMatcher.Strictness.of(Settings.AD_SKIP_STRICTNESS.get()).ordinal() * 2
                + (Settings.AD_SKIP_RETIMED.get() ? 1 : 0);
        return AdSegmentStore.generation() * 16 + config;
    }

    /** Shows the exact user selection immediately, without promoting any old scan to the new generation. */
    static void recordMarked(String aid, AdSegment segment, long startMs, long endMs, int votes,
            long videoDurationMs, int generation) {
        if (aid == null || segment == null || endMs <= startMs) return;
        AdMatcher.Index index = AdSegmentStore.indexAt(generation / 16);
        if (index == null || !java.util.Arrays.asList(index.segments).contains(segment)) return;
        synchronized (LOCK) {
            if (generation != stamp()) return;
            AdMatcher.Match added = new AdMatcher.Match(segment, startMs, endMs, Math.max(1, votes), false);
            List<AdMatcher.Match> manual = new ArrayList<>();
            Result previous = MARKED.get(aid);
            if (previous != null) {
                for (AdMatcher.Match m : previous.matches) {
                    if (m.segment != segment && java.util.Arrays.asList(index.segments).contains(m.segment)) manual.add(m);
                }
            }
            manual.add(added);
            manual.sort((x, y) -> Long.compare(x.startMs, y.startMs));
            MARKED.put(aid, new Result(State.PARTIAL, generation, manual,
                    Math.max(videoDurationMs, endMs), 0, null));
        }
        Utils.runOnMainThread(AdSkipBar::refresh);
    }

    /** Asks for a scan. Urgent work overtakes queued prefetches. */
    static void request(Object aweme, boolean urgent) {
        String aid = Reflect.string(aweme, "getAid", "aid");
        if (aid == null || isPhotoPost(aweme)) return;
        synchronized (LOCK) {
            int generation = stamp();
            want(aid, urgent);
            Result r = RESULTS.get(aid);
            if (r != null && r.generation == generation && (r.state == State.COMPLETE
                    || (r.state == State.FAILED && !urgent))) return;
            Job current = JOBS.get(aid);
            if (current != null && current.generation == generation && !current.cancel.get()) {
                if (urgent) {
                    if (QUEUE.remove(current)) QUEUE.addFirst(current);
                    hurry(current);
                    startWorker(true);
                }
                return;
            }
            if (current != null) discard(current);
            Job job = new Job(aid, aweme, generation);
            job.urgent = urgent;
            JOBS.put(aid, job);
            if (urgent) QUEUE.addFirst(job);
            else QUEUE.addLast(job);
            while (QUEUE.size() > MAX_QUEUE) discard(QUEUE.getLast());
            startWorker(urgent);
        }
    }

    private static void discard(Job job) {
        job.cancel.set(true);
        QUEUE.remove(job);
        if (JOBS.get(job.aid) != job) return;
        JOBS.remove(job.aid);
    }

    /** Starts a worker thread (up to 2 background, 3 when urgent). */
    private static void startWorker(boolean urgent) {
        synchronized (LOCK) {
            if (QUEUE.isEmpty()) return;
            if (workers < MAX_WORKERS || (urgent && workers < MAX_WORKERS_URGENT)) {
                workers++;
                Utils.runOnOwnThread("metra-adskip", AdScanner::work);
            }
        }
    }

    /** Promotes a running background job to default thread priority when its video comes on screen. */
    private static void hurry(Job job) {
        job.urgent = true;
        int tid = job.tid;
        if (tid == 0) return;
        try {
            Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DEFAULT);
        } catch (Throwable ignored) {
        }
    }

    /** Tracks the 4 most recent video IDs and cancels jobs outside that window. */
    private static void want(String aid, boolean urgent) {
        WANTED.remove(aid);
        if (urgent) WANTED.addFirst(aid);
        else WANTED.add(Math.min(1, WANTED.size()), aid);
        while (WANTED.size() > WANTED_SIZE) WANTED.removeLast();
        for (Job running : RUNNING) if (!WANTED.contains(running.aid)) discard(running);
        for (Job queued : new ArrayList<>(QUEUE)) if (!WANTED.contains(queued.aid)) discard(queued);
    }

    private static boolean current(Job job) {
        return JOBS.get(job.aid) == job && !job.cancel.get() && job.generation == stamp();
    }

    private static boolean publish(Job job, Result result) {
        synchronized (LOCK) {
            if (!current(job)) return false;
            RESULTS.put(job.aid, result);
            return true;
        }
    }

    private static boolean isPhotoPost(Object aweme) {
        Object images = Reflect.invoke(aweme, "getImageInfos");
        if (images instanceof List<?> && !((List<?>) images).isEmpty()) return true;
        return Reflect.invoke(aweme, "getPhotoModeImageInfo") != null;
    }

    private static void work() {
        while (true) {
            Job job;
            synchronized (LOCK) {
                // The extra urgent worker must not become a third background download.
                Job next = QUEUE.peekFirst();
                int background = 0;
                for (Job running : RUNNING) if (!running.urgent) background++;
                if (next != null && !next.urgent && background >= MAX_BACKGROUND_RUNNING) {
                    workers--;
                    return;
                }
                job = QUEUE.pollFirst();
                if (job == null) {
                    workers--;
                    return;
                }
                RUNNING.add(job);
                job.tid = Process.myTid();
            }
            try {
                Process.setThreadPriority(job.urgent ? Process.THREAD_PRIORITY_DEFAULT : Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {
            }
            try {
                run(job);
            } finally {
                synchronized (LOCK) {
                    RUNNING.remove(job);
                    discard(job);
                }
                if (job.deferredByNetwork) {
                    AdSkip.scanDeferredByNetwork(job.aid, job.generation);
                }
            }
        }
    }

    private static void run(Job job) {
        Object aweme = job.aweme.get();
        int generation = job.generation;
        if (aweme == null || job.cancel.get() || !AdSkip.active()) return;
        AdMatcher.Index index = AdSegmentStore.indexAt(generation / 16);
        if (index == null || index.isEmpty()) return;
        AdMatcher.Strictness strictness = AdMatcher.Strictness.values()[(generation % 16) / 2];
        boolean retimed = (generation & 1) != 0;
        long started = System.nanoTime();
        long queuedMs = (started - job.queuedAt) / 1_000_000L;
        try {
            Context context = Utils.getContext();
            if (context == null) throw new IllegalStateException("No context");
            restoreOnce();
            synchronized (LOCK) {
                if (!current(job)) return;
                Result cached = RESULTS.get(job.aid);
                if (cached != null && cached.generation == generation && cached.state == State.COMPLETE) {
                    Utils.runOnMainThread(AdSkipBar::refresh);
                    return;
                }
            }
            if (!AdSkip.mayDownload()) {
                job.deferredByNetwork = true;
                return;
            }
            // Decode once and match the whole scan window: a prefix can hide a competing peak.
            AdAudioSource.Pcm pcm = AdAudioSource.load(context, aweme, Landmarks.SAMPLE_RATE, MAX_SCAN_MS, job.cancel);
            long loaded = (System.nanoTime() - started) / 1_000_000L;
            if (job.cancel.get()) return;
            List<AdMatcher.Match> matches = AdMatcher.find(index, pcm.samples, pcm.length, strictness, job.cancel);
            if (matches.isEmpty() && retimed) {
                matches = retimedScan(pcm.samples, pcm.length, job.cancel, index, strictness);
            }
            long took = (System.nanoTime() - started) / 1_000_000L;
            Result result = new Result(State.COMPLETE, generation, matches, pcm.durationMs(), took, null);
            if (!publish(job, result)) return;
            AdSkipLog.add(job.aid, (matches.isEmpty() ? "no ad" : "found " + matches)
                    + ", " + pcm.downloadedBytes / 1024 + " KB in " + took + "ms (queue " + queuedMs
                    + ", download " + pcm.downloadMs + ", decode " + pcm.decodeMs + ", match " + (took - loaded) + ")");
            Listener l = listener;
            if (l != null) l.scanned(job.aid, result);
            Utils.runOnMainThread(AdSkipBar::refresh);
            persist();
        } catch (Throwable failure) {
            if (job.cancel.get()) {
                AdSkipLog.add(job.aid, "scan stopped, the feed moved on, elapsed "
                        + (System.nanoTime() - started) / 1_000_000L + "ms (queue " + queuedMs + ")");
                return;
            }
            long took = (System.nanoTime() - started) / 1_000_000L;
            if (!publish(job, new Result(State.FAILED, generation, Collections.emptyList(), 0, took,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage()))) return;
            Utils.runOnMainThread(AdSkipBar::refresh);
            AdSkipLog.add(job.aid, "scan failed: " + failure.getMessage());
            Logger.printDebug(() -> "Ad skip: could not scan " + job.aid, failure instanceof Exception ? (Exception) failure : null);
        }
    }

    /** All ads in this PCM: the plain pass, then retimed copies when that found nothing. */
    static List<AdMatcher.Match> scan(short[] pcm, int length) throws IOException {
        return scan(pcm, length, null);
    }

    static List<AdMatcher.Match> scan(short[] pcm, int length, AtomicBoolean cancel) throws IOException {
        AdMatcher.Index index = AdSegmentStore.index();
        AdMatcher.Strictness strictness = AdMatcher.Strictness.of(Settings.AD_SKIP_STRICTNESS.get());
        try {
            List<AdMatcher.Match> found = AdMatcher.find(index, pcm, length, strictness, cancel);
            if (!found.isEmpty() || !Settings.AD_SKIP_RETIMED.get()) return found;
            return retimedScan(pcm, length, cancel, index, strictness);
        } catch (java.util.concurrent.CancellationException stopped) {
            throw new IOException("Cancelled", stopped);
        }
    }

    /** The video sped up and slowed down a little, which undoes a retimed copy of an ad. */
    private static List<AdMatcher.Match> retimedScan(short[] pcm, int length, AtomicBoolean cancel,
            AdMatcher.Index index, AdMatcher.Strictness strictness) throws IOException {
        Landmarks.checkCancelled(cancel);
        short[] stretched = new short[Math.max(0, (int) (length * (double) RATES[2]))];
        for (float rate : RATES) {
            if (cancel != null && cancel.get()) throw new IOException("Cancelled");
            int samples = Math.max(0, (int) (length * (double) rate));
            stretchInto(pcm, length, rate, stretched, samples, cancel);
            List<AdMatcher.Match> retimed = AdMatcher.match(index, Landmarks.computeQuery(stretched, samples, cancel),
                    strictness, true, cancel);
            if (retimed.isEmpty()) continue;
            List<AdMatcher.Match> mapped = new ArrayList<>();
            for (AdMatcher.Match m : retimed) {
                mapped.add(new AdMatcher.Match(m.segment, Math.round(m.startMs / rate),
                        Math.round(m.endMs / rate), m.votes, m.partial));
            }
            return mapped;
        }
        return Collections.emptyList();
    }

    /** Linearly resamples PCM by {@code rate} using 12.20 fixed-point arithmetic. */
    static short[] stretch(short[] pcm, int length, float rate) {
        if (length <= 0 || rate <= 0f) return new short[0];
        int outLength = (int) (length * (double) rate);
        short[] out = new short[Math.max(0, outLength)];
        stretchInto(pcm, length, rate, out, out.length, null);
        return out;
    }

    private static void stretchInto(short[] pcm, int length, float rate, short[] out, int count, AtomicBoolean cancel) {
        long step = Math.round((1L << 20) / (double) rate);
        long pos = 0;
        int limit = length - 1;
        for (int i = 0; i < count; i++, pos += step) {
            if ((i & 4095) == 0) Landmarks.checkCancelled(cancel);
            int a = (int) (pos >>> 20);
            if (a >= limit) {
                out[i] = pcm[Math.max(0, limit)];
                continue;
            }
            int frac = (int) (pos & 0xFFFFFL);
            int s0 = pcm[a];
            int s1 = pcm[a + 1];
            out[i] = (short) (s0 + (((s1 - s0) * (long) frac + 0x80000L) >> 20));
        }
    }

    static void restoreInBackground() {
        synchronized (LOCK) {
            if (restored || restoreScheduled) return;
            restoreScheduled = true;
        }
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            try {
                restoreOnce();
            } finally {
                synchronized (LOCK) {
                    restoreScheduled = false;
                }
            }
        });
        if (!accepted) {
            synchronized (LOCK) {
                restoreScheduled = false;
            }
        }
    }

    /** Loads persisted scan results matching the current segment-list fingerprint. */
    private static synchronized void restoreOnce() {
        Context context = Utils.getContext();
        if (context == null) return;
        int epoch;
        synchronized (LOCK) {
            if (restored) return;
            restored = true;
            epoch = cacheEpoch;
        }
        File file = new File(context.getFilesDir(), CACHE_FILE);
        Map<String, Result> loaded = new LinkedHashMap<>();
        try {
            JSONObject all = new JSONObject(new String(readFile(file), StandardCharsets.UTF_8));
            int generation = stamp();
            AdMatcher.Index index = AdSegmentStore.indexAt(generation / 16);
            if (index == null) return;
            String fingerprint = fingerprint(index, generation);
            Map<Long, AdSegment> segments = new java.util.HashMap<>();
            for (AdSegment segment : index.segments) segments.put(segment.id, segment);
            Iterator<String> aids = all.keys();
            while (aids.hasNext()) {
                String aid = aids.next();
                JSONObject entry = all.getJSONObject(aid);
                if (!fingerprint.equals(entry.optString("fp"))) continue;
                List<AdMatcher.Match> matches = new ArrayList<>();
                JSONArray found = entry.getJSONArray("m");
                for (int i = 0; i < found.length(); i++) {
                    JSONArray m = found.getJSONArray(i);
                    AdSegment segment = segments.get(m.getLong(0));
                    if (segment == null) throw new IOException("Unknown segment in scan cache");
                    matches.add(new AdMatcher.Match(segment, m.getLong(1), m.getLong(2), m.getInt(3), m.getBoolean(4)));
                }
                loaded.put(aid, new Result(State.COMPLETE, generation, matches, entry.getLong("s"), 0, null));
            }
        } catch (Exception unreadable) {
            return;
        }
        synchronized (LOCK) {
            if (epoch != cacheEpoch) return;
            int generation = stamp();
            for (Map.Entry<String, Result> entry : loaded.entrySet()) {
                if (entry.getValue().generation == generation) RESULTS.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        Utils.runOnMainThread(AdSkipBar::refresh);
    }

    private static volatile boolean persistDirty;
    private static final Object PERSIST_FILE_LOCK = new Object();

    /** Schedules saving finished scans in the background five seconds after the first pending change. */
    private static void persist() {
        synchronized (LOCK) {
            if (persistDirty) return;
            persistDirty = true;
        }
        Utils.runOnMainThreadDelayed(AdScanner::flushPersist, 5_000);
    }

    static void flushPersist() {
        synchronized (LOCK) {
            if (!persistDirty) return;
            persistDirty = false;
        }
        Utils.runOnBackgroundThread(AdScanner::writePersistNow);
    }

    private static void writePersistNow() {
        Context context = Utils.getContext();
        if (context == null) return;
        int generation = stamp();
        AdMatcher.Index index = AdSegmentStore.indexAt(generation / 16);
        if (index == null) return;
        String fingerprint = fingerprint(index, generation);
        try {
            JSONObject all = new JSONObject();
            synchronized (LOCK) {
                for (Map.Entry<String, Result> entry : RESULTS.entrySet()) {
                    Result result = entry.getValue();
                    if (result.state != State.COMPLETE || result.generation != generation) continue;
                    JSONArray found = new JSONArray();
                    for (AdMatcher.Match m : result.matches) {
                        found.put(new JSONArray().put(m.segment.id).put(m.startMs).put(m.endMs).put(m.votes).put(m.partial));
                    }
                    all.put(entry.getKey(), new JSONObject().put("fp", fingerprint).put("s", result.scannedMs).put("m", found));
                }
            }
            byte[] bytes = all.toString().getBytes(StandardCharsets.UTF_8);
            synchronized (PERSIST_FILE_LOCK) {
                android.util.AtomicFile atomic = new android.util.AtomicFile(new File(context.getFilesDir(), CACHE_FILE));
                FileOutputStream out = atomic.startWrite();
                try {
                    out.write(bytes);
                    out.flush();
                    atomic.finishWrite(out);
                } catch (IOException writeErr) {
                    atomic.failWrite(out);
                    throw writeErr;
                }
            }
        } catch (Exception failure) {
            Logger.printDebug(() -> "Ad skip: could not save scans: " + failure);
        }
    }

    private static String fingerprint(AdMatcher.Index index, int generation) {
        int config = generation % 16;
        synchronized (index) {
            if (index.cacheFingerprints[config] == null) {
                index.cacheFingerprints[config] = computeFingerprint(index, config);
            }
            return index.cacheFingerprints[config];
        }
    }

    private static String computeFingerprint(AdMatcher.Index index, int config) {
        // Hash the actual indexed fingerprints, not just their IDs and lengths.
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            java.nio.ByteBuffer word = java.nio.ByteBuffer.allocate(Long.BYTES);
            update(digest, word, ALGORITHM);
            update(digest, word, config);
            update(digest, word, index.segments.length);
            for (AdSegment segment : index.segments) {
                update(digest, word, segment.id);
                update(digest, word, segment.durationMs);
                update(digest, word, segment.frames);
                update(digest, word, segment.hashes.length);
            }
            for (long entry : index.entries) update(digest, word, entry);
            return android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void update(java.security.MessageDigest digest, java.nio.ByteBuffer word, long value) {
        digest.update(word.putLong(0, value).array());
    }

    private static byte[] readFile(File file) throws IOException {
        android.util.AtomicFile atomic = new android.util.AtomicFile(file);
        try (InputStream in = atomic.openRead()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) out.write(chunk, 0, read);
            return out.toByteArray();
        }
    }

    static void clearAll() {
        synchronized (LOCK) {
            for (Job job : QUEUE) job.cancel.set(true);
            for (Job job : RUNNING) job.cancel.set(true);
            QUEUE.clear();
            JOBS.clear();
            WANTED.clear();
            cacheEpoch++;
            restored = true;
            RESULTS.clear();
            MARKED.clear();
        }
    }
}
