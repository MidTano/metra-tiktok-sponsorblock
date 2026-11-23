/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.content.Context;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The marked ads, kept on the phone in one small binary file. A 15 second ad is about 2,500
 * hashes, 20 KB, so a few hundred ads stay under a few megabytes and load in milliseconds.
 *
 * <p>Every change writes a new file beside the old one and renames it over, so a crash mid
 * write leaves the previous list rather than half of one. The match index is rebuilt on each
 * change and swapped in whole, so a scan running on another thread always sees one list.
 */
final class AdSegmentStore {
    private static final int MAGIC = 0x4D414453; // "MADS"
    private static final int VERSION = 1;
    private static final String FILE = "metra_ad_segments.bin";

    private static final Object LOCK = new Object();
    private static List<AdSegment> segments;
    private static volatile AdMatcher.Index index;
    /** Bumped on every change, so a cached scan result made against an older list is redone. */
    private static volatile int generation;

    private AdSegmentStore() {}

    static List<AdSegment> all() {
        synchronized (LOCK) {
            ensureLoaded();
            return new ArrayList<>(segments);
        }
    }

    static int size() {
        synchronized (LOCK) {
            ensureLoaded();
            return segments.size();
        }
    }

    static int enabledCount() {
        synchronized (LOCK) {
            ensureLoaded();
            int n = 0;
            for (AdSegment s : segments) if (s.enabled) n++;
            return n;
        }
    }

    static AdMatcher.Index index() {
        AdMatcher.Index current = index;
        if (current != null) return current;
        synchronized (LOCK) {
            ensureLoaded();
            return index;
        }
    }

    static int generation() {
        return generation;
    }

    static AdSegment add(String name, long durationMs, String sourceAid, Landmarks.Print print) {
        synchronized (LOCK) {
            ensureLoaded();
            long id = System.currentTimeMillis();
            for (AdSegment s : segments) if (s.id >= id) id = s.id + 1;
            AdSegment segment = new AdSegment(id, name, System.currentTimeMillis(), durationMs, sourceAid,
                    java.util.Arrays.copyOf(print.hashes, print.count),
                    java.util.Arrays.copyOf(print.times, print.count), print.frames, true, 0, 0);
            segments.add(segment);
            changed();
            return segment;
        }
    }

    static void remove(long id) {
        synchronized (LOCK) {
            ensureLoaded();
            for (int i = 0; i < segments.size(); i++) {
                if (segments.get(i).id == id) {
                    segments.remove(i);
                    break;
                }
            }
            changed();
        }
    }

    static void setEnabled(long id, boolean enabled) {
        synchronized (LOCK) {
            ensureLoaded();
            for (AdSegment s : segments) if (s.id == id) s.enabled = enabled;
            changed();
        }
    }

    static void rename(long id, String name) {
        synchronized (LOCK) {
            ensureLoaded();
            for (AdSegment s : segments) if (s.id == id) s.name = name;
            save();
        }
    }

    static void clear() {
        synchronized (LOCK) {
            ensureLoaded();
            segments.clear();
            changed();
        }
    }

    /** Counts a skip. Saved later with the next change or by {@link #flushStats}. */
    static void recordSkip(AdSegment segment) {
        synchronized (LOCK) {
            segment.skips++;
            segment.lastSkipAt = System.currentTimeMillis();
            statsDirty = true;
        }
        Utils.runOnMainThreadDelayed(AdSegmentStore::flushStats, 5_000);
    }

    private static boolean statsDirty;

    static void flushStats() {
        synchronized (LOCK) {
            if (!statsDirty) return;
            statsDirty = false;
        }
        Utils.runOnBackgroundThread(() -> {
            synchronized (LOCK) {
                save();
            }
        });
    }

    static long totalSkips() {
        synchronized (LOCK) {
            ensureLoaded();
            long n = 0;
            for (AdSegment s : segments) n += s.skips;
            return n;
        }
    }

    private static void changed() {
        generation++;
        index = AdMatcher.Index.build(segments);
        save();
    }

    private static void ensureLoaded() {
        if (segments != null) return;
        segments = new ArrayList<>();
        File file = file();
        if (file != null && file.isFile()) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                if (in.readInt() != MAGIC) throw new IOException("Not an ad list");
                int version = in.readInt();
                if (version != VERSION) throw new IOException("Ad list version " + version);
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    long id = in.readLong();
                    String name = in.readUTF();
                    long created = in.readLong();
                    long duration = in.readLong();
                    String aid = in.readUTF();
                    boolean enabled = in.readBoolean();
                    int skips = in.readInt();
                    long lastSkip = in.readLong();
                    int frames = in.readInt();
                    int n = in.readInt();
                    if (n < 0 || n > 2_000_000) throw new IOException("Bad hash count");
                    int[] hashes = new int[n];
                    int[] times = new int[n];
                    for (int k = 0; k < n; k++) hashes[k] = in.readInt();
                    for (int k = 0; k < n; k++) times[k] = in.readInt();
                    segments.add(new AdSegment(id, name, created, duration, aid.isEmpty() ? null : aid,
                            hashes, times, frames, enabled, skips, lastSkip));
                }
            } catch (Exception failure) {
                Logger.printException(() -> "Ad skip: could not read the ad list", failure);
                File broken = new File(file.getParentFile(), FILE + ".broken");
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(broken);
                segments.clear();
            }
        }
        index = AdMatcher.Index.build(segments);
    }

    private static void save() {
        File file = file();
        if (file == null) return;
        File temp = new File(file.getParentFile(), FILE + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(segments.size());
            for (AdSegment s : segments) {
                out.writeLong(s.id);
                out.writeUTF(s.name == null ? "" : s.name);
                out.writeLong(s.createdAt);
                out.writeLong(s.durationMs);
                out.writeUTF(s.sourceAid == null ? "" : s.sourceAid);
                out.writeBoolean(s.enabled);
                out.writeInt(s.skips);
                out.writeLong(s.lastSkipAt);
                out.writeInt(s.frames);
                out.writeInt(s.hashes.length);
                for (int h : s.hashes) out.writeInt(h);
                for (int t : s.times) out.writeInt(t);
            }
        } catch (IOException failure) {
            Logger.printException(() -> "Ad skip: could not save the ad list", failure);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return;
        }
        if (!temp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (!temp.renameTo(file)) Logger.printDebug(() -> "Ad skip: could not replace the ad list");
        }
    }

    private static File file() {
        Context context = Utils.getContext();
        return context == null ? null : new File(context.getFilesDir(), FILE);
    }

    static List<AdSegment> unmodifiable(List<AdSegment> list) {
        return Collections.unmodifiableList(list);
    }
}
