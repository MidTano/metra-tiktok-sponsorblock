/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The phone's side of the shared ad base: the daily download, sending the reader's own cuts,
 * votes, and the skip counts behind the statistics.
 *
 * <p>Matching only ever reads the local list. The base adds the segments other readers cut, and
 * a cut sent from this phone learns its server id, which is what makes it votable. Everything
 * here runs off the main thread and gives up quietly when the network is not there; the next
 * sync tries again.
 *
 * <p>The snapshot is little-endian:
 * <pre>
 *   [4] magic MADB   [4] version 2 (1 is still read)   [4] segment count
 *   per segment:
 *     [16] uuid
 *     [4] number in the base, the N of adN   (version 2)
 *     [4] flags, bit 0: hidden by votes      (version 2)
 *     [4] frames                             (version 2)
 *     [4] duration ms   [4] score   [4] hash count
 *     [4 x count] hashes   [4 x count] times
 * </pre>
 * The server names the moment its data is current as of in {@code X-Db-Time}; the next delta asks
 * from there, so nothing sent between a snapshot's build and its download is missed. A delta
 * that filled a whole page says so in {@code X-Db-More}, and the next page is asked for at once.
 */
public final class SharedDb {
    public static final String BASE_URL = "https://metra-adskip.falker600.workers.dev";
    /** Server ids carry this prefix on the phone. */
    public static final String SHARED_PREFIX = "s_";

    private static final int MAGIC = 0x4244414D;
    private static final String KEY_LAST_SYNC = "sync_last";
    private static final String KEY_LAST_EPOCH = "sync_as_of";
    private static final String KEY_VOTE = "vote_";
    private static final String QUEUE_FILE = "metra_adskip_queue.bin";
    private static final long SYNC_INTERVAL_MS = 24 * 3600 * 1000L;
    private static final String USER_AGENT = "Metra-AdSkip/2";
    /** Delta pages one sync reads at most; the rest arrives with the next sync. */
    private static final int MAX_DELTA_PAGES = 10;
    /** The limits a snapshot is read under, the same as the server's and the core library's. */
    private static final int MAX_DB_BYTES = 64 * 1024 * 1024;
    private static final int MAX_HASHES = 6_000;
    private static final int MIN_DURATION_MS = 1_000;
    private static final int MAX_DURATION_MS = 30_000;
    private static final int TIME_SLACK = 70;
    private static final int MAX_FRAMES = 30 * Landmarks.FRAMES_PER_SECOND + TIME_SLACK;

    private static volatile boolean syncing;

    private SharedDb() {}

    /** Server-side counters for the statistics card. */
    static final class Stats {
        long segments;
        long contributors;
        long skips;
        long savedMs;
        long mySegments;
        long mySkips;
        long mySavedMs;
        boolean mine;
    }

    // region sync

    /** Syncs in the background once a day. Safe to call on every feed open. */
    public static void syncIfDue() {
        if (!Settings.AD_SKIP_SHARED_DB.get() || syncing) return;
        Context context = Utils.getContext();
        SharedPreferences prefs = AdSkipPrefs.get();
        if (context == null || prefs == null) return;
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_SYNC, 0) < SYNC_INTERVAL_MS) return;
        syncing = true;
        Utils.runOnOwnThread("metra-adskip-sync", () -> {
            try {
                download(prefs);
                flushQueue(context);
                reportSkips();
            } catch (Throwable failure) {
                Logger.printException(() -> "Ad skip: shared base sync failed", failure);
            } finally {
                syncing = false;
            }
        });
    }

    private static void download(SharedPreferences prefs) throws IOException {
        int added = 0;
        boolean more = true;
        for (int page = 0; more && page < MAX_DELTA_PAGES; page++) {
            long since = prefs.getLong(KEY_LAST_EPOCH, 0);
            String endpoint = since > 0 ? BASE_URL + "/v1/db/since?t=" + since : BASE_URL + "/v1/db.bin";
            HttpURLConnection connection = open(endpoint);
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " from " + endpoint);
            long asOf;
            try {
                asOf = Long.parseLong(connection.getHeaderField("X-Db-Time"));
            } catch (Exception missing) {
                asOf = System.currentTimeMillis() / 1000L - 86_400L;
            }
            more = since > 0 && "1".equals(connection.getHeaderField("X-Db-More")) && asOf > since;
            byte[] data;
            try (InputStream in = connection.getInputStream()) {
                data = readAll(in, MAX_DB_BYTES);
            }
            added += merge(data);
            prefs.edit().putLong(KEY_LAST_EPOCH, asOf).apply();
        }
        prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply();
        AdSkipLog.add(null, "shared base: " + added + " new segments");
    }

    private static int merge(byte[] data) {
        if (data.length < 12) return 0;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.getInt() != MAGIC) return 0;
        int version = buf.getInt();
        if (version != 1 && version != 2) return 0;
        int count = buf.getInt();
        List<AdSegmentStore.Shared> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (buf.remaining() < (version == 2 ? 40 : 28)) break;
            byte[] id = new byte[16];
            buf.get(id);
            int number = version == 2 ? buf.getInt() : 0;
            int flags = version == 2 ? buf.getInt() : 0;
            int frames = version == 2 ? buf.getInt() : 0;
            int durationMs = buf.getInt();
            int score = buf.getInt();
            int hashCount = buf.getInt();
            if (hashCount < 0 || buf.remaining() < hashCount * 8L) break;
            if (hashCount > MAX_HASHES) {
                buf.position(buf.position() + hashCount * 8);
                continue;
            }
            int[] hashes = new int[hashCount];
            int[] times = new int[hashCount];
            for (int k = 0; k < hashCount; k++) hashes[k] = buf.getInt();
            for (int k = 0; k < hashCount; k++) times[k] = buf.getInt();
            if (frames <= 0) frames = durationMs * Landmarks.FRAMES_PER_SECOND / 1000;
            if (!isValid(number, durationMs, frames, hashes, times)) continue;
            boolean hidden = (flags & 1) != 0 || score < -2;
            entries.add(new AdSegmentStore.Shared(SHARED_PREFIX + hex(id), number, hidden, durationMs, frames,
                    hashes, times, score));
        }
        return AdSegmentStore.mergeShared(entries);
    }

    private static boolean isValid(int number, int durationMs, int frames, int[] hashes, int[] times) {
        if (number < 0 || durationMs < MIN_DURATION_MS || durationMs > MAX_DURATION_MS) return false;
        if (frames < 1 || frames > MAX_FRAMES) return false;
        for (int k = 0; k < hashes.length; k++) {
            if (!isHash(hashes[k]) || times[k] < 0 || times[k] > frames + TIME_SLACK) return false;
        }
        return true;
    }

    /** Two peak bins below the band's width, then a gap of 1 to 63 frames, as Landmarks packs them. */
    private static boolean isHash(int hash) {
        int f1 = hash >>> 15;
        int f2 = (hash >>> 6) & 511;
        int dt = hash & 63;
        return f1 < Landmarks.BINS && f2 < Landmarks.BINS && dt >= 1;
    }

    // endregion

    // region submitting

    /**
     * Sends a new cut. Without a signed-in account, over the daily limit, or offline it waits in a
     * queue on the phone, and works locally meanwhile.
     */
    public static void submit(Context context, AdSegment segment) {
        byte[] body;
        try {
            body = payload(segment);
        } catch (IllegalStateException failure) {
            Logger.printException(() -> "Ad skip: could not encode a segment for the shared base", failure);
            return;
        }
        Utils.runOnOwnThread("metra-adskip-submit", () -> {
            try {
                Reply reply = post("/v1/segments", body);
                if (reply.code == 202 || reply.code == 401 || reply.code == 429 || reply.code >= 500) {
                    enqueue(context, body);
                } else if (reply.code == 200 || reply.code == 201) {
                    String id = reply.json == null ? null : reply.json.optString("id", null);
                    if (id != null) AdSegmentStore.linkShared(segment.id, SHARED_PREFIX + id.replace("-", ""));
                }
                AdSkipLog.add(segment.sourceAid, "shared base: sent, HTTP " + reply.code);
            } catch (Throwable failure) {
                Logger.printException(() -> "Ad skip: could not send a segment", failure);
                enqueue(context, body);
            }
        });
    }

    private static void enqueue(Context context, byte[] payload) {
        synchronized (SharedDb.class) {
            try (DataOutputStream out = new DataOutputStream(
                    new FileOutputStream(new File(context.getFilesDir(), QUEUE_FILE), true))) {
                out.writeInt(payload.length);
                out.write(payload);
            } catch (IOException failure) {
                Logger.printException(() -> "Ad skip: could not queue a segment", failure);
            }
        }
    }

    private static void flushQueue(Context context) throws IOException {
        List<byte[]> entries = new ArrayList<>();
        synchronized (SharedDb.class) {
            File file = new File(context.getFilesDir(), QUEUE_FILE);
            if (!file.exists()) return;
            byte[] data;
            try (InputStream in = new FileInputStream(file)) {
                data = readAll(in, Integer.MAX_VALUE);
            }
            ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.remaining() >= 4) {
                int length = buf.getInt();
                if (length <= 0 || length > buf.remaining()) break;
                byte[] entry = new byte[length];
                buf.get(entry);
                entries.add(entry);
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        int sent = 0;
        for (byte[] entry : entries) {
            Reply reply = post("/v1/segments", entry);
            if (reply.code == 200 || reply.code == 201) sent++;
            else if (reply.code == 202 || reply.code == 401 || reply.code == 429 || reply.code >= 500) enqueue(context, entry);
        }
        AdSkipLog.add(null, "shared base: sent " + sent + " of " + entries.size() + " queued");
    }

    private static byte[] payload(AdSegment segment) {
        try {
            int n = segment.hashes.length;
            ByteBuffer hashes = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer times = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < n; i++) {
                hashes.putInt(segment.hashes[i]);
                times.putInt(segment.times[i]);
            }
            JSONObject json = new JSONObject()
                    .put("name", segment.name)
                    .put("duration_ms", segment.durationMs)
                    .put("source_aid", segment.sourceAid == null ? JSONObject.NULL : segment.sourceAid)
                    .put("frames", segment.frames)
                    .put("hashes_b64", Base64.encodeToString(hashes.array(), Base64.NO_WRAP))
                    .put("times_b64", Base64.encodeToString(times.array(), Base64.NO_WRAP));
            return json.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not encode a segment", failure);
        }
    }

    // endregion

    // region votes

    /** The reader's vote on this segment: 1, -1, or 0 when none. */
    static int myVote(String serverId) {
        SharedPreferences prefs = AdSkipPrefs.get();
        return prefs == null || serverId == null ? 0 : prefs.getInt(KEY_VOTE + serverId, 0);
    }

    /**
     * Votes once per segment. The same vote again does nothing; the other one replaces it, on the
     * server too, so a segment never carries two votes from one account.
     */
    static boolean vote(String serverId, int vote) {
        if (serverId == null || !serverId.startsWith(SHARED_PREFIX) || myVote(serverId) == vote) return false;
        SharedPreferences prefs = AdSkipPrefs.get();
        if (prefs == null) return false;
        prefs.edit().putInt(KEY_VOTE + serverId, vote).apply();
        String remoteId = uuid(serverId.substring(SHARED_PREFIX.length()));
        Utils.runOnOwnThread("metra-adskip-vote", () -> {
            try {
                byte[] body = new JSONObject().put("vote", vote).toString().getBytes(StandardCharsets.UTF_8);
                Reply reply = post("/v1/segments/" + remoteId + "/vote", body);
                AdSkipLog.add(null, "shared base: vote " + vote + ", HTTP " + reply.code);
            } catch (Throwable failure) {
                Logger.printException(() -> "Ad skip: could not send a vote", failure);
            }
        });
        return true;
    }

    // endregion

    // region statistics

    /** Sends how often each shared segment was skipped here since the last report. */
    private static void reportSkips() throws Exception {
        Map<String, long[]> pending = AdSkipStats.pendingReport();
        if (pending.isEmpty()) return;
        JSONArray items = new JSONArray();
        for (Map.Entry<String, long[]> entry : pending.entrySet()) {
            items.put(new JSONObject()
                    .put("id", uuid(entry.getKey().substring(SHARED_PREFIX.length())))
                    .put("n", entry.getValue()[0])
                    .put("ms", entry.getValue()[1]));
        }
        byte[] body = new JSONObject().put("skips", items).toString().getBytes(StandardCharsets.UTF_8);
        Reply reply = post("/v1/skips", body);
        if (reply.code == 200) AdSkipStats.reported(pending);
    }

    /** The base's totals and, when signed in, what the reader's own cuts saved others. */
    @Nullable
    static Stats fetchStats() {
        try {
            Stats stats = new Stats();
            HttpURLConnection global = open(BASE_URL + "/v1/stats");
            if (global.getResponseCode() != 200) return null;
            JSONObject all;
            try (InputStream in = global.getInputStream()) {
                all = new JSONObject(new String(readAll(in, 64 * 1024), StandardCharsets.UTF_8));
            }
            stats.segments = all.optLong("active");
            stats.contributors = all.optLong("contributors");
            stats.skips = all.optLong("skips");
            stats.savedMs = all.optLong("saved_ms");
            HttpURLConnection me = open(BASE_URL + "/v1/me");
            if (me.getResponseCode() == 200) {
                JSONObject mine;
                try (InputStream in = me.getInputStream()) {
                    mine = new JSONObject(new String(readAll(in, 64 * 1024), StandardCharsets.UTF_8));
                }
                stats.mine = true;
                stats.mySegments = mine.optLong("segments");
                stats.mySkips = mine.optLong("skips");
                stats.mySavedMs = mine.optLong("saved_ms");
            }
            return stats;
        } catch (Throwable failure) {
            Logger.printDebug(() -> "Ad skip: no shared base statistics: " + failure);
            return null;
        }
    }

    // endregion

    // region http

    private static final class Reply {
        final int code;
        @Nullable
        final JSONObject json;

        Reply(int code, @Nullable JSONObject json) {
            this.code = code;
            this.json = json;
        }
    }

    private static HttpURLConnection open(String endpoint) throws IOException {
        String token = AdSkipPrefs.token();
        if (token == null) throw new IOException("No sync token yet");
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("X-SB-Token", token);
        return connection;
    }

    private static Reply post(String path, byte[] body) throws IOException {
        HttpURLConnection connection = open(BASE_URL + path);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.getOutputStream().write(body);
        int code = connection.getResponseCode();
        JSONObject json = null;
        if (code < 400) {
            try (InputStream in = connection.getInputStream()) {
                json = new JSONObject(new String(readAll(in, 16 * 1024), StandardCharsets.UTF_8));
            } catch (Exception notJson) {
                json = null;
            }
        }
        return new Reply(code, json);
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int read;
        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
            if (out.size() > limit) throw new IOException("Reply larger than " + limit + " bytes");
        }
        return out.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b));
        return text.toString();
    }

    /** 32 hex digits as a hyphenated uuid. */
    private static String uuid(String hex) {
        if (hex.length() != 32) return hex;
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    // endregion
}
