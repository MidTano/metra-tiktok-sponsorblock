/**
 * Metra TikTok — shared ad-segment server.
 *
 * Routes
 *   POST   /v1/segments          submit a new segment
 *   POST   /v1/segments/:id/vote  vote up (+1) or down (-1)
 *   GET    /v1/db.bin             full binary snapshot (R2 object)
 *   GET    /v1/db/since?t=EPOCH   delta since epoch seconds
 *   GET    /v1/health             simple health check
 *   GET    /v1/stats              aggregate counters
 */

export interface Env {
  DB:             D1Database;
  BUCKET:         R2Bucket;
  SUBMIT_LIMITER: RateLimit;
  VOTE_LIMITER:   RateLimit;
  READ_LIMITER:   RateLimit;
}

// ─── constants ───────────────────────────────────────────────────────────────

const MIN_DURATION_MS = 2_000;
const MAX_DURATION_MS = 30_000;      // <= 30 s, as requested
const MAX_NAME_LEN    = 80;
const MAX_HASHES      = 60_000;      // ~400 s at 150 hashes/s
const MIN_HASHES      = 40;
const HIDE_THRESHOLD_VOTES = 5;      // votes needed before auto-hide
const HIDE_RATIO           = 0.4;    // 40% dislikes → hidden
const HIDE_SCORE_ABS       = -3;     // or score ≤ −3

const TT_UID_RE = /^[0-9]{5,30}$/;   // TikTok uid is a decimal string
const UUID_RE   = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

// ─── main handler ────────────────────────────────────────────────────────────

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url  = new URL(req.url);
    const path = url.pathname.replace(/\/$/, '');

    if (req.method === 'GET' && path === '/v1/health')
      return json({ ok: true });

    if (req.method === 'GET' && path === '/v1/stats')
      return handleStats(env);

    if (req.method === 'GET' && path === '/v1/db.bin')
      return handleFullDb(req, env);

    if (req.method === 'GET' && path === '/v1/db/since')
      return handleDelta(req, env);

    if (req.method === 'POST' && path === '/v1/segments')
      return handleSubmit(req, env);

    const voteMatch = path.match(/^\/v1\/segments\/([^/]+)\/vote$/);
    if (req.method === 'POST' && voteMatch)
      return handleVote(req, env, voteMatch[1]);

    return err(404, 'Not found');
  },

  // Cron: rebuild the binary snapshot and push it to R2.
  async scheduled(_event: ScheduledEvent, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(rebuildSnapshot(env));
  },
};

// ─── route handlers ──────────────────────────────────────────────────────────

async function handleStats(env: Env): Promise<Response> {
  const r = await env.DB.prepare(
    `SELECT
       COUNT(*) AS total,
       SUM(CASE WHEN hidden = 0 THEN 1 ELSE 0 END) AS active,
       SUM(CASE WHEN confirmed = 1 THEN 1 ELSE 0 END) AS confirmed,
       SUM(votes_up) AS votes_up,
       SUM(votes_down) AS votes_down
     FROM segments`).first<Record<string, number>>();
  return json(r ?? {});
}

async function handleFullDb(req: Request, env: Env): Promise<Response> {
  // Rate-limit by IP, not user: this is a heavy read.
  const ip = req.headers.get('CF-Connecting-IP') ?? 'unknown';
  const { success } = await env.READ_LIMITER.limit({ key: ip });
  if (!success) return err(429, 'Too many requests');

  const obj = await env.BUCKET.get('db.bin');
  if (!obj) return err(503, 'Snapshot not ready yet — check back in a minute');
  const headers = new Headers({
    'Content-Type':   'application/octet-stream',
    'Cache-Control':  'public, max-age=3600',
    'Last-Modified':  obj.uploaded.toUTCString(),
  });
  return new Response(obj.body, { headers });
}

async function handleDelta(req: Request, env: Env): Promise<Response> {
  const ip = req.headers.get('CF-Connecting-IP') ?? 'unknown';
  const { success } = await env.READ_LIMITER.limit({ key: ip });
  if (!success) return err(429, 'Too many requests');

  const since = Number(new URL(req.url).searchParams.get('t') ?? '0');
  if (!Number.isFinite(since) || since < 0) return err(400, 'Bad t');

  const rows = await env.DB.prepare(
    `SELECT id, name, duration_ms, source_aid, frames,
            hashes, times, hash_count,
            score, votes_up, votes_down, confirmed, hidden, updated_at
     FROM segments WHERE updated_at > ? AND hidden = 0
     ORDER BY updated_at LIMIT 2000`
  ).bind(since).all<SegmentRow>();

  return new Response(packSegments(rows.results ?? []), {
    headers: { 'Content-Type': 'application/octet-stream' },
  });
}

async function handleSubmit(req: Request, env: Env): Promise<Response> {
  // ── auth ──
  const auth = req.headers.get('X-TT-UID');
  if (!auth || !TT_UID_RE.test(auth.trim()))
    return err(401, 'Provide your TikTok uid in X-TT-UID');
  const ttUid = auth.trim();

  // ── rate limit per TikTok account ──
  const { success } = await env.SUBMIT_LIMITER.limit({ key: ttUid });
  if (!success)
    // 20/day reached — tell the client to queue it
    return json({ queued: true }, 202);

  // ── body ──
  let body: SubmitBody;
  try { body = await req.json() as SubmitBody; }
  catch { return err(400, 'Invalid JSON'); }

  const v = validateSubmit(body);
  if (v) return err(400, v);

  const id  = crypto.randomUUID();
  const now = nowSec();

  const hBuf = b64ToArrayBuffer(body.hashes_b64);
  const tBuf = b64ToArrayBuffer(body.times_b64);
  const hashCount = hBuf.byteLength / 4;

  if (hashCount < MIN_HASHES) return err(400, 'Too few audio landmarks');
  if (hashCount > MAX_HASHES)  return err(400, 'Too many audio landmarks');

  await env.DB.prepare(
    `INSERT INTO segments
       (id, tt_uid, name, duration_ms, source_aid, frames,
        hash_count, hashes, times, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(
    id, ttUid,
    body.name.trim().slice(0, MAX_NAME_LEN),
    body.duration_ms,
    body.source_aid ?? null,
    body.frames,
    hashCount,
    hBuf, tBuf,
    now, now
  ).run();

  return json({ id, queued: false }, 201);
}

async function handleVote(req: Request, env: Env, rawId: string): Promise<Response> {
  const auth = req.headers.get('X-TT-UID');
  if (!auth || !TT_UID_RE.test(auth.trim()))
    return err(401, 'Provide your TikTok uid in X-TT-UID');
  const ttUid = auth.trim();

  const { success } = await env.VOTE_LIMITER.limit({ key: ttUid });
  if (!success) return err(429, 'Too many votes today');

  if (!UUID_RE.test(rawId)) return err(400, 'Invalid segment id');
  const segId = rawId.toLowerCase();

  let body: { vote: number };
  try { body = await req.json() as { vote: number }; }
  catch { return err(400, 'Invalid JSON'); }
  if (body.vote !== 1 && body.vote !== -1) return err(400, 'vote must be 1 or -1');

  const seg = await env.DB.prepare('SELECT id FROM segments WHERE id = ? AND hidden = 0')
    .bind(segId).first<{ id: string }>();
  if (!seg) return err(404, 'Segment not found');

  // Upsert the vote (one per segment per account).
  const existing = await env.DB.prepare(
    'SELECT vote FROM votes WHERE segment_id = ? AND tt_uid = ?'
  ).bind(segId, ttUid).first<{ vote: number }>();

  const now = nowSec();
  if (!existing) {
    await env.DB.prepare(
      'INSERT INTO votes(segment_id, tt_uid, vote, created_at) VALUES (?, ?, ?, ?)'
    ).bind(segId, ttUid, body.vote, now).run();
  } else if (existing.vote !== body.vote) {
    await env.DB.prepare(
      'UPDATE votes SET vote = ? WHERE segment_id = ? AND tt_uid = ?'
    ).bind(body.vote, segId, ttUid).run();
  } else {
    return json({ changed: false });   // same vote again — no-op
  }

  // Recalculate score from the votes table (safe against concurrent requests).
  const counts = await env.DB.prepare(
    `SELECT
       SUM(CASE WHEN vote = 1 THEN 1 ELSE 0 END)  AS up,
       SUM(CASE WHEN vote = -1 THEN 1 ELSE 0 END) AS down
     FROM votes WHERE segment_id = ?`
  ).bind(segId).first<{ up: number; down: number }>();

  const up   = counts?.up   ?? 0;
  const down = counts?.down ?? 0;
  const score = up - down;
  const total = up + down;

  // Auto-hide when enough votes and either ratio or absolute score bad.
  const confirmed = total >= 2 ? 1 : 0;
  const hidden =
    total >= HIDE_THRESHOLD_VOTES &&
    (score <= HIDE_SCORE_ABS || down / total >= HIDE_RATIO) ? 1 : 0;

  await env.DB.prepare(
    `UPDATE segments
     SET score = ?, votes_up = ?, votes_down = ?,
         confirmed = ?, hidden = ?, updated_at = ?
     WHERE id = ?`
  ).bind(score, up, down, confirmed, hidden, now, segId).run();

  return json({ score, votes_up: up, votes_down: down, confirmed, hidden });
}

// ─── snapshot builder ────────────────────────────────────────────────────────

async function rebuildSnapshot(env: Env): Promise<void> {
  const rows = await env.DB.prepare(
    `SELECT id, name, duration_ms, source_aid, frames,
            hashes, times, hash_count,
            score, votes_up, votes_down, confirmed, hidden, updated_at
     FROM segments WHERE hidden = 0 ORDER BY created_at`
  ).all<SegmentRow>();

  const buf = packSegments(rows.results ?? []);
  await env.BUCKET.put('db.bin', buf, {
    httpMetadata: { contentType: 'application/octet-stream' },
    customMetadata: {
      generated: new Date().toISOString(),
      count:     String((rows.results ?? []).length),
    },
  });

  await env.DB.prepare("UPDATE meta SET value = ? WHERE key = 'last_export'")
    .bind(nowSec()).run();
}

// ─── binary packing ──────────────────────────────────────────────────────────
// Format (little-endian):
//   [4]  magic  MADB
//   [4]  version 1
//   [4]  segment count N
//   per segment:
//     [16] id as bytes
//     [4]  duration_ms
//     [4]  score
//     [4]  hash_count
//     [4*hash_count] hashes
//     [4*hash_count] times

const MAGIC   = 0x4244414D;   // "MADB"
const VERSION = 1;

type SegmentRow = {
  id: string; name: string; duration_ms: number; source_aid: string | null;
  frames: number; hashes: ArrayBuffer; times: ArrayBuffer; hash_count: number;
  score: number; votes_up: number; votes_down: number;
  confirmed: number; hidden: number; updated_at: number;
};

function packSegments(rows: SegmentRow[]): ArrayBuffer {
  // Header: 12 bytes + per segment: 16 + 4 + 4 + 4 + hash_count*8 bytes
  let size = 12;
  for (const r of rows) size += 28 + r.hash_count * 8;

  const buf  = new ArrayBuffer(size);
  const view = new DataView(buf);
  let off = 0;

  view.setUint32(off, MAGIC,   true); off += 4;
  view.setUint32(off, VERSION, true); off += 4;
  view.setUint32(off, rows.length, true); off += 4;

  for (const row of rows) {
    const idBytes = uuidToBytes(row.id);
    new Uint8Array(buf, off, 16).set(idBytes); off += 16;
    view.setUint32(off, row.duration_ms, true); off += 4;
    view.setInt32 (off, row.score,       true); off += 4;
    view.setUint32(off, row.hash_count,  true); off += 4;

    const hSrc = new Uint8Array(row.hashes);
    const tSrc = new Uint8Array(row.times);
    new Uint8Array(buf, off, hSrc.length).set(hSrc); off += hSrc.length;
    new Uint8Array(buf, off, tSrc.length).set(tSrc); off += tSrc.length;
  }
  return buf;
}

function uuidToBytes(uuid: string): Uint8Array {
  const hex = uuid.replace(/-/g, '');
  const out = new Uint8Array(16);
  for (let i = 0; i < 16; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

// ─── validation ──────────────────────────────────────────────────────────────

type SubmitBody = {
  name: string; duration_ms: number; source_aid?: string;
  frames: number; hashes_b64: string; times_b64: string;
};

function validateSubmit(b: SubmitBody): string | null {
  if (!b.name || typeof b.name !== 'string' || !b.name.trim())
    return 'name required';
  if (typeof b.duration_ms !== 'number'
      || b.duration_ms < MIN_DURATION_MS || b.duration_ms > MAX_DURATION_MS)
    return `duration_ms must be ${MIN_DURATION_MS}–${MAX_DURATION_MS}`;
  if (typeof b.frames !== 'number' || b.frames <= 0 || !Number.isInteger(b.frames))
    return 'frames must be a positive integer';
  if (typeof b.hashes_b64 !== 'string' || !b.hashes_b64)
    return 'hashes_b64 required';
  if (typeof b.times_b64 !== 'string' || !b.times_b64)
    return 'times_b64 required';
  if (b.hashes_b64.length !== b.times_b64.length)
    return 'hashes_b64 and times_b64 must be the same length';
  // source_aid if present must look like a TikTok aid (numeric string ≤ 30 chars)
  if (b.source_aid !== undefined && b.source_aid !== null) {
    const s = String(b.source_aid).trim();
    if (s.length > 0 && !/^[0-9]{1,30}$/.test(s))
      return 'source_aid must be a numeric string';
  }
  return null;
}

// ─── helpers ─────────────────────────────────────────────────────────────────

function nowSec() { return Math.floor(Date.now() / 1000); }

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function err(status: number, message: string): Response {
  return json({ error: message }, status);
}

function b64ToArrayBuffer(b64: string): ArrayBuffer {
  const binary = atob(b64);
  const buf    = new ArrayBuffer(binary.length);
  const view   = new Uint8Array(buf);
  for (let i = 0; i < binary.length; i++) view[i] = binary.charCodeAt(i);
  return buf;
}
