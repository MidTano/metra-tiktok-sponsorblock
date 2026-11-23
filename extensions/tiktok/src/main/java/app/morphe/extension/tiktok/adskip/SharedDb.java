/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.content.Context;
import android.content.SharedPreferences;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client side of the shared ad database: downloads the daily snapshot from the Worker
 * and merges it into the local store.
 *
 * <p>The local store is the only source of truth for matching. The shared base adds segments
 * that other users submitted; local-only segments (created before the user ever went online,
 * or when the shared base is turned off) live beside them. The only difference is that a local
 * segment has no server id.
 *
 * <p>Format of the binary snapshot (little-endian):
 *   [4]  magic  MADB (0x4244414D)
 *   [4]  version 1
 *   [4]  segment count N
 *   per segment:
 *     [16] id as 16 bytes (uuid without hyphens)
 *     [4]  duration_ms
 *     [4]  score
 *     [4]  hash_count
 *     [hash_count * 4] hashes
 *     [hash_count * 4] times
 */
public final class SharedDb {
    public static final String BASE_URL = "https://metra-adskip.falker600.workers.dev";

    private static final int MAGIC   = 0x4244414D;
    private static final int VERSION = 1;
    private static final String PREFS = "metra_adskip_sync";
    private static final String KEY_LAST_SYNC  = "last_sync";
    private static final String KEY_LAST_EPOCH = "last_epoch";
    private static final long   SYNC_INTERVAL  = 24 * 3600 * 1000L;
    /** Segments with this prefix come from the shared base. */
    public static final String SHARED_PREFIX = "s_";

    private static volatile boolean syncing;
    static volatile long pendingSubmits;   // count sent to queue file

    private SharedDb() {}

    // ── sync ────────────────────────────────────────────────────────────────

    /** Call once at startup or when the feed is opened. Runs in the background. */
    public static void syncIfDue() {
        if (!Settings.AD_SKIP_SHARED_DB.get()) return;
        if (syncing) return;
        Context ctx = Utils.getContext();
        if (ctx == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong(KEY_LAST_SYNC, 0);
        if (System.currentTimeMillis() - last < SYNC_INTERVAL) return;
        syncing = true;
        Utils.runOnOwnThread("metra-adskip-sync", () -> {
            try {
                doSync(ctx, prefs);
                flushPendingQueue(ctx);
            } catch (Throwable failure) {
                Logger.printException(() -> "Ad skip: shared db sync failed", failure);
            } finally {
                syncing = false;
            }
        });
    }

    private static void doSync(Context ctx, SharedPreferences prefs) throws IOException {
        long since = prefs.getLong(KEY_LAST_EPOCH, 0);
        String endpoint = since > 0
                ? BASE_URL + "/v1/db/since?t=" + since
                : BASE_URL + "/v1/db.bin";
        byte[] data = fetch(endpoint);
        int merged = parseAndMerge(data);
        prefs.edit()
             .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
             .putLong(KEY_LAST_EPOCH, System.currentTimeMillis() / 1000L)
             .apply();
        AdSkipLog.add(null, "shared db: merged " + merged + " segments");
    }

    private static byte[] fetch(String endpoint) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", "Metra-AdSkip/1");
        int code = conn.getResponseCode();
        if (code == 304) return new byte[0];
        if (code != 200) throw new IOException("HTTP " + code + " from " + endpoint);
        try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[65536];
            int read;
            while ((read = in.read(tmp)) != -1) buf.write(tmp, 0, read);
            return buf.toByteArray();
        }
    }

    private static int parseAndMerge(byte[] data) {
        if (data.length < 12) return 0;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buf.getInt();
        if (magic != MAGIC) {
            Logger.printDebug(() -> "Ad skip: bad snapshot magic");
            return 0;
        }
        int version = buf.getInt();
        if (version != VERSION) {
            Logger.printDebug(() -> "Ad skip: unsupported snapshot version " + version);
            return 0;
        }
        int count = buf.getInt();
        int merged = 0;
        for (int i = 0; i < count; i++) {
            if (buf.remaining() < 28) break;
            byte[] idBytes = new byte[16];
            buf.get(idBytes);
            String serverId = SHARED_PREFIX + bytesToHex(idBytes);
            int durationMs = buf.getInt();
            int score      = buf.getInt();
            int hashCount  = buf.getInt();
            if (buf.remaining() < hashCount * 8) break;
            int[] hashes = new int[hashCount];
            for (int k = 0; k < hashCount; k++) hashes[k] = buf.getInt();
            int[] times  = new int[hashCount];
            for (int k = 0; k < hashCount; k++) times[k]  = buf.getInt();
            // Skip segments with low score or ones we already have.
            if (score < -2) continue;
            AdSegmentStore.mergeShared(
                    serverId, "Ad (shared)", durationMs,
                    hashes, times, hashCount, score);
            merged++;
        }
        return merged;
    }

    // ── submit & vote ────────────────────────────────────────────────────────

    /**
     * Sends a segment to the server. If the account is not logged in, it is queued. If the
     * daily limit is reached (server returns 202), it is queued. The queue is flushed at the
     * next successful sync.
     */
    public static void submit(Context ctx, AdSegment segment) {
        String uid = SignedInUser.id();
        if (uid == null) {
            enqueue(ctx, buildSubmitPayload(segment));
            return;
        }
        Utils.runOnOwnThread("metra-adskip-submit", () -> {
            try {
                byte[] body = buildSubmitPayload(segment);
                int code = post(BASE_URL + "/v1/segments", uid, body);
                if (code == 429 || code == 202) {
                    enqueue(ctx, body);  // limit reached: local queue
                } else {
                    AdSkipLog.add(segment.sourceAid, "shared db: submitted, server=" + code);
                }
            } catch (Throwable failure) {
                Logger.printException(() -> "Ad skip: could not submit segment", failure);
                enqueue(ctx, buildSubmitPayload(segment));
            }
        });
    }

    public static void vote(String serverId, int v) {
        if (!serverId.startsWith(SHARED_PREFIX)) return;
        String uid = SignedInUser.id();
        if (uid == null) return;
        String remoteId = uuidFromHex(serverId.substring(SHARED_PREFIX.length()));
        Utils.runOnOwnThread("metra-adskip-vote", () -> {
            try {
                String json = "{\"vote\":" + v + "}";
                int code = post(BASE_URL + "/v1/segments/" + remoteId + "/vote",
                        uid, json.getBytes("UTF-8"));
                AdSkipLog.add(null, "shared db: vote=" + v + " server=" + code);
            } catch (Throwable failure) {
                Logger.printException(() -> "Ad skip: could not send vote", failure);
            }
        });
    }

    private static int post(String endpoint, String ttUid, byte[] body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-TT-UID", ttUid);
        conn.setRequestProperty("User-Agent", "Metra-AdSkip/1");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        conn.getOutputStream().write(body);
        return conn.getResponseCode();
    }

    // ── pending queue ────────────────────────────────────────────────────────

    private static final String QUEUE_FILE = "metra_adskip_queue.bin";

    private static void enqueue(Context ctx, byte[] payload) {
        synchronized (SharedDb.class) {
            pendingSubmits++;
        }
        File f = new File(ctx.getFilesDir(), QUEUE_FILE);
        try (DataOutputStream out = new DataOutputStream(
                new FileOutputStream(f, true))) {
            out.writeInt(payload.length);
            out.write(payload);
        } catch (IOException failure) {
            Logger.printException(() -> "Ad skip: could not enqueue segment", failure);
        }
    }

    private static void flushPendingQueue(Context ctx) throws IOException {
        File f = new File(ctx.getFilesDir(), QUEUE_FILE);
        if (!f.exists()) return;
        String uid = SignedInUser.id();
        if (uid == null) return;    // still not signed in; leave the queue
        byte[] data;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new java.io.FileInputStream(f)))) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int r;
            while ((r = in.read(tmp)) != -1) buf.write(tmp, 0, r);
            data = buf.toByteArray();
        }
        List<byte[]> entries = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        while (buf.remaining() >= 4) {
            int len = buf.getInt();
            if (len <= 0 || len > buf.remaining()) break;
            byte[] entry = new byte[len];
            buf.get(entry);
            entries.add(entry);
        }
        int flushed = 0;
        List<byte[]> retry = new ArrayList<>();
        for (byte[] entry : entries) {
            int code = post(BASE_URL + "/v1/segments", uid, entry);
            if (code == 201) flushed++;
            else if (code == 202 || code == 429 || code >= 500) retry.add(entry);
            // 4xx other than 202/429 → discard (bad data)
        }
        if (retry.isEmpty()) {
            f.delete();
        } else {
            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(f, false))) {
                for (byte[] entry : retry) {
                    out.writeInt(entry.length);
                    out.write(entry);
                }
            }
        }
        synchronized (SharedDb.class) {
            pendingSubmits = retry.size();
        }
        AdSkipLog.add(null, "shared db: flushed " + flushed + " queued, " + retry.size() + " remain");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static byte[] buildSubmitPayload(AdSegment segment) {
        try {
            int n = segment.hashes.length;
            ByteBuffer hBuf = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer tBuf = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < n; i++) hBuf.putInt(segment.hashes[i]);
            for (int i = 0; i < n; i++) tBuf.putInt(segment.times[i]);
            String hb64 = android.util.Base64.encodeToString(hBuf.array(), android.util.Base64.NO_WRAP);
            String tb64 = android.util.Base64.encodeToString(tBuf.array(), android.util.Base64.NO_WRAP);
            String name  = jsonEscape(segment.name);
            String aid   = segment.sourceAid != null ? "\"" + jsonEscape(segment.sourceAid) + "\"" : "null";
            String json  = "{\"name\":\"" + name + "\",\"duration_ms\":" + segment.durationMs
                         + ",\"source_aid\":" + aid + ",\"frames\":" + segment.frames
                         + ",\"hashes_b64\":\"" + hb64 + "\",\"times_b64\":\"" + tb64 + "\"}";
            return json.getBytes("UTF-8");
        } catch (Exception failure) {
            Logger.printException(() -> "Ad skip: could not build submit payload", failure);
            return new byte[0];
        }
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String uuidFromHex(String hex) {
        // 32 hex chars → 8-4-4-4-12
        if (hex.length() != 32) return hex;
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
             + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-"
             + hex.substring(20);
    }
}
