const path = require('path');
const fs = require('fs');

// Minimal .env loader (no dependency). Systemd EnvironmentFile also works.
const envPath = process.env.NOROBO_ENV_FILE || path.join(__dirname, '.env');
if (fs.existsSync(envPath)) {
  for (const line of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$/);
    if (m && process.env[m[1]] === undefined) {
      process.env[m[1]] = m[2].replace(/^["']|["']$/g, '');
    }
  }
}

const express = require('express');
const db = require('./db');
const { computeScore } = require('./scoring');
const providers = require('./providers');
const ai = require('./model');

const AUTH_TOKEN = process.env.AUTH_TOKEN || '';
const PORT = parseInt(process.env.PORT || '3000', 10);
const CACHE_TTL_MS = parseInt(process.env.CACHE_TTL_MS || String(7 * 24 * 3600 * 1000), 10);
const TOP_THRESHOLD = parseFloat(process.env.TOP_THRESHOLD || '0.6');
const DAY_MS = 24 * 3600 * 1000;

const app = express();
app.use(express.json({ limit: '2mb' }));
app.use(express.urlencoded({ extended: false })); // Twilio-style webhooks

function authed(req) {
  const tok =
    (req.body && req.body.token) ||
    req.query.token ||
    (req.headers.authorization || '').replace(/^Bearer\s+/, '');
  return AUTH_TOKEN === '' || tok === AUTH_TOKEN;
}

function fail(res, msg, code = 401) {
  res.status(code).json({ error: msg });
}

function digitsOf(value) {
  return String(value || '').replace(/\D/g, '').slice(-10);
}

// ---- Caller lookup (cached; providers optional) --------------------------
app.post('/api/v1/lookup', async (req, res) => {
  if (!authed(req)) return fail(res, 'unauthorized');
  const digits = digitsOf(req.body.number);
  if (digits.length < 7) return fail(res, 'invalid number', 400);

  const row = db.prepare('SELECT * FROM numbers WHERE number = ?').get(digits);
  let carrier = row ? row.carrier : null;
  let lineType = row ? row.line_type : null;
  let business = row ? row.business : null;

  const cached = db
    .prepare('SELECT * FROM lookup_cache WHERE number = ?')
    .get(digits);
  if (cached && Date.now() - cached.checked_at < CACHE_TTL_MS) {
    carrier = cached.carrier;
    lineType = cached.line_type;
    business = cached.business;
  } else {
    const p = await providers.lookup(digits);
    if (p.any) {
      carrier = p.carrier;
      lineType = p.lineType;
      business = p.business;
      db.prepare(
        `INSERT INTO lookup_cache (number, carrier, line_type, business, checked_at)
         VALUES (?, ?, ?, ?, ?)
         ON CONFLICT(number) DO UPDATE SET
           carrier = excluded.carrier,
           line_type = excluded.line_type,
           business = excluded.business,
           checked_at = excluded.checked_at`
      ).run(digits, carrier, lineType, business, Date.now());
      if (row) {
        db.prepare(
          'UPDATE numbers SET carrier = ?, line_type = ?, business = ? WHERE number = ?'
        ).run(carrier, lineType, business, digits);
      }
    }
  }

  const ownScore = row ? computeScore(row) : 0;
  const isVoip = (lineType || '').toLowerCase().includes('voip');

  const callsLast7d = row
    ? db.prepare('SELECT COUNT(*) AS c FROM calls WHERE number = ? AND ts > ?')
        .get(digits, Date.now() - 7 * DAY_MS).c
    : 0;

  // AI model score when a trained model exists; heuristic fallback otherwise.
  const aiResult = ai.predict({
    reports_total: row ? row.reports_total : 0,
    calls_total: row ? row.calls_total : 0,
    calls_last24h: row ? row.calls_last24h : 0,
    calls_last7d: callsLast7d,
    distinct_devices: row ? row.distinct_devices : 0,
    honeypot_hits: row ? row.honeypot_hits : 0,
    line_type: lineType
  });

  // AI model raises the score when a trained model exists; the heuristic
  // scorer always provides a safety floor (VoIP, reports, volume).
  const heuristicFloor = Math.max(ownScore, isVoip ? 0.55 : 0, row ? row.score : 0);
  const spamScore = aiResult
    ? Math.max(aiResult.score, heuristicFloor)
    : heuristicFloor;

  res.json({
    number: digits,
    carrier,
    lineType,
    business,
    spamScore,
    aiScore: aiResult ? aiResult.score : null,
    aiSpam: aiResult ? aiResult.spam : null,
    aiTrainedAt: aiResult ? aiResult.trainedAt : null,
    reports: row ? row.reports_total : 0,
    callsTotal: row ? row.calls_total : 0,
    callsLast24h: row ? row.calls_last24h : 0,
    distinctDevices: row ? row.distinct_devices : 0,
    honeypotHits: row ? row.honeypot_hits : 0,
    source: row || cached || (carrier ? 1 : 0) ? 'server' : 'none'
  });
});

// ---- Honeypot webhook -------------------------------------------------------
// Point your DID/SIP provider webhooks here (JSON or Twilio-style form post):
//   POST /api/v1/honeypot/call?token=...
//   JSON: {did, from, source}   |   Twilio: form fields From, To
app.post('/api/v1/honeypot/call', (req, res) => {
  if (!authed(req)) return fail(res, 'unauthorized');
  const did = digitsOf(req.body.did || req.body.To);
  const from = digitsOf(req.body.from || req.body.From);
  const source = String(req.body.source || 'webhook').slice(0, 32);
  if (from.length < 7 || did.length < 7) return fail(res, 'invalid number', 400);

  const now = Date.now();
  db.prepare('INSERT INTO honeypot_calls (did, from_number, ts, source, created_at) VALUES (?, ?, ?, ?, ?)')
    .run(did, from, now, source, now);

  const existing = db.prepare('SELECT * FROM numbers WHERE number = ?').get(from);
  if (existing) {
    const merged = { ...existing, honeypot_hits: (existing.honeypot_hits || 0) + 1 };
    db.prepare('UPDATE numbers SET honeypot_hits = honeypot_hits + 1, score = ?, updated_at = ? WHERE number = ?')
      .run(computeScore(merged), now, from);
  } else {
    db.prepare(
      `INSERT INTO numbers (number, calls_total, calls_last24h, last_seen, distinct_devices, reports_total, honeypot_hits, score, updated_at)
       VALUES (?, 0, 0, ?, 0, 0, 1, ?, ?)`
    ).run(from, now, computeScore({ honeypot_hits: 1 }), now);
  }
  res.json({ ok: true });
});

// ---- Model status ----------------------------------------------------------
app.get('/api/v1/model', (req, res) => {
  if (!authed(req)) return fail(res, 'unauthorized');
  const m = ai.loadModel();
  if (!m) return res.json({ model: null });
  res.json({
    model: {
      algorithm: m.algorithm,
      trainedAt: m.trainedAt,
      threshold: m.threshold,
      metrics: m.metrics,
      featureNames: m.featureNames
    }
  });
});

// ---- Call ingestion -------------------------------------------------------
app.post('/api/v1/calls', (req, res) => {
  if (!authed(req)) return fail(res, 'unauthorized');
  const calls = Array.isArray(req.body.calls) ? req.body.calls : [];
  const device = String(req.body.device || 'unknown').slice(0, 64);
  const now = Date.now();

  const insertCall = db.prepare(
    `INSERT INTO calls (device, number, ts, action, reason, carrier, line_type, spam_score, business, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  );
  const getNumber = db.prepare('SELECT * FROM numbers WHERE number = ?');
  const count24h = db.prepare('SELECT COUNT(*) AS c FROM calls WHERE number = ? AND ts > ?');
  const countDevices = db.prepare('SELECT COUNT(DISTINCT device) AS c FROM calls WHERE number = ?');
  const updateNumber = db.prepare(
    `UPDATE numbers SET
       calls_total = calls_total + 1,
       calls_last24h = ?,
       last_seen = ?,
       distinct_devices = ?,
       carrier = COALESCE(?, carrier),
       line_type = COALESCE(?, line_type),
       business = COALESCE(?, business),
       score = ?,
       updated_at = ?
     WHERE number = ?`
  );
  const insertNumber = db.prepare(
    `INSERT INTO numbers (number, calls_total, calls_last24h, last_seen, distinct_devices, reports_total, carrier, line_type, business, score, updated_at)
     VALUES (?, 1, 1, ?, 1, 0, ?, ?, ?, ?, ?)`
  );

  let stored = 0;
  const tx = db.transaction(() => {
    for (const c of calls) {
      const digits = digitsOf(c.number);
      if (digits.length < 7) continue;
      insertCall.run(
        device, digits,
        Number(c.timestamp) || now,
        c.action, c.reason, c.carrier, c.lineType, c.spamScore, c.business, now
      );
      const existing = getNumber.get(digits);
      if (existing) {
        const last24h = count24h.get(digits, now - DAY_MS).c;
        const devices = countDevices.get(digits).c;
        const merged = {
          ...existing,
          calls_total: existing.calls_total + 1,
          calls_last24h: last24h,
          distinct_devices: devices,
          line_type: c.lineType || existing.line_type
        };
        updateNumber.run(
          last24h, now, devices,
          c.carrier, c.lineType, c.business,
          computeScore(merged), now, digits
        );
      } else {
        insertNumber.run(
          digits, now, c.carrier, c.lineType, c.business,
          computeScore({ calls_last24h: 1, distinct_devices: 1, line_type: c.lineType }),
          now
        );
      }
      stored++;
    }
  });
  tx();

  res.json({ ok: true, stored });
});

// ---- Community reports -----------------------------------------------------
app.post('/api/v1/report', (req, res) => {
  if (!authed(req)) return fail(res, 'unauthorized');
  const digits = digitsOf(req.body.number);
  const category = String(req.body.category || 'spam').slice(0, 32);
  if (digits.length < 7) return fail(res, 'invalid number', 400);

  db.prepare('INSERT INTO reports (number, category, ts) VALUES (?, ?, ?)')
    .run(digits, category, Date.now());

  const existing = db.prepare('SELECT * FROM numbers WHERE number = ?').get(digits);
  if (existing) {
    const merged = { ...existing, reports_total: existing.reports_total + 1 };
    db.prepare('UPDATE numbers SET reports_total = reports_total + 1, score = ?, updated_at = ? WHERE number = ?')
      .run(computeScore(merged), Date.now(), digits);
  } else {
    db.prepare(
      `INSERT INTO numbers (number, calls_total, calls_last24h, last_seen, distinct_devices, reports_total, score, updated_at)
       VALUES (?, 0, 0, ?, 0, 1, ?, ?)`
    ).run(digits, Date.now(), computeScore({ reports_total: 1 }), Date.now());
  }
  res.json({ ok: true });
});

// ---- Top-blocked feed (app syncs this for instant local blocking) ---------
app.get('/api/v1/top-blocked', (req, res) => {
  if (!authed(req)) return fail(res, 'unauthorized');
  const rows = db
    .prepare('SELECT number, score FROM numbers WHERE score >= ? ORDER BY score DESC, updated_at DESC LIMIT 500')
    .all(TOP_THRESHOLD);
  res.json({ numbers: rows });
});

app.get('/health', (req, res) => res.json({ ok: true }));

// ---- Admin API + dashboard (see admin.js) -----------------------------------
require('./admin').mount(app);

app.listen(PORT, () => {
  console.log(`RobocallGuard backend listening on :${PORT}`);
});
