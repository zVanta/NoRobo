/**
 * Admin API + web dashboard.
 * Requires ADMIN_TOKEN in .env — when empty, admin routes return 403.
 *
 *   GET  /admin?token=<admin>                        dashboard page
 *   GET  /api/v1/admin/stats?token=<admin>           platform stats
 *   GET  /api/v1/admin/numbers?limit=50&token=       number list
 *   POST /api/v1/admin/numbers/:n/block              force-block a number
 *   POST /api/v1/admin/numbers/:n/allow              clear a number's flags
 *   POST /api/v1/admin/retrain                       retrain the AI model
 */
const express = require('express');
const fs = require('fs');
const path = require('path');
const db = require('./db');
const { computeScore } = require('./scoring');
const ai = require('./model');
const smsAi = require('./smsModel');

const DAY_MS = 24 * 3600 * 1000;

function digitsOf(value) {
  return String(value || '').replace(/\D/g, '').slice(-10);
}

function mount(app) {
  const ADMIN_TOKEN = process.env.ADMIN_TOKEN || '';
  const router = express.Router();

  router.use((req, res, next) => {
    if (!ADMIN_TOKEN) {
      return res.status(403).json({ error: 'admin disabled (ADMIN_TOKEN not set)' });
    }
    const tok =
      req.query.token ||
      (req.headers.authorization || '').replace(/^Bearer\s+/, '') ||
      (req.body && req.body.token);
    if (tok !== ADMIN_TOKEN) return res.status(401).json({ error: 'unauthorized' });
    next();
  });

  router.get('/stats', (req, res) => {
    const model = ai.loadModel();
    res.json({
      calls: db.prepare('SELECT COUNT(*) AS c FROM calls').get().c,
      numbers: db.prepare('SELECT COUNT(*) AS c FROM numbers').get().c,
      reports: db.prepare('SELECT COUNT(*) AS c FROM reports').get().c,
      honeypotCalls: db.prepare('SELECT COUNT(*) AS c FROM honeypot_calls').get().c,
      blockedToday: db
        .prepare("SELECT COUNT(*) AS c FROM calls WHERE action != 'ALLOW' AND ts > ?")
        .get(Date.now() - DAY_MS).c,
      topNumbers: db
        .prepare(
          `SELECT number, score, reports_total, calls_total, calls_last24h,
                  honeypot_hits, line_type
             FROM numbers ORDER BY score DESC LIMIT 10`
        )
        .all(),
      model: model
        ? { trainedAt: model.trainedAt, metrics: model.metrics, threshold: model.threshold }
        : null
    });
  });

  router.get('/numbers', (req, res) => {
    const limit = Math.min(parseInt(req.query.limit, 10) || 50, 200);
    res.json({
      numbers: db
        .prepare('SELECT * FROM numbers ORDER BY score DESC LIMIT ?')
        .all(limit)
    });
  });

  router.post('/numbers/:number/block', (req, res) => {
    const n = digitsOf(req.params.number);
    if (n.length < 7) return res.status(400).json({ error: 'invalid number' });
    const now = Date.now();
    db.prepare('INSERT INTO reports (number, category, ts) VALUES (?, ?, ?)')
      .run(n, 'admin', now);
    const existing = db.prepare('SELECT * FROM numbers WHERE number = ?').get(n);
    if (existing) {
      const merged = { ...existing, reports_total: existing.reports_total + 1 };
      db.prepare('UPDATE numbers SET reports_total = reports_total + 1, score = ?, updated_at = ? WHERE number = ?')
        .run(computeScore(merged), now, n);
    } else {
      db.prepare(
        `INSERT INTO numbers (number, calls_total, calls_last24h, last_seen, distinct_devices, reports_total, honeypot_hits, score, updated_at)
         VALUES (?, 0, 0, ?, 0, 1, 0, ?, ?)`
      ).run(n, now, computeScore({ reports_total: 1 }), now);
    }
    res.json({ ok: true, number: n });
  });

  router.post('/numbers/:number/allow', (req, res) => {
    const n = digitsOf(req.params.number);
    if (n.length < 7) return res.status(400).json({ error: 'invalid number' });
    const existing = db.prepare('SELECT * FROM numbers WHERE number = ?').get(n);
    if (existing) {
      db.prepare(
        'UPDATE numbers SET reports_total = 0, honeypot_hits = 0, score = 0, updated_at = ? WHERE number = ?'
      ).run(Date.now(), n);
    }
    res.json({ ok: true, number: n });
  });

  router.post('/retrain', (req, res) => {
    const { loadDataset } = require('./labeling');
    const { trainModel } = require('./train');
    const dataset = loadDataset();
    if (dataset.length < 50) {
      return res.status(400).json({ error: 'not enough labeled data (need >= 50)' });
    }
    const { model } = trainModel(dataset, { seed: 42 });
    fs.mkdirSync(path.dirname(ai.MODEL_PATH), { recursive: true });
    fs.writeFileSync(ai.MODEL_PATH, JSON.stringify(model, null, 2));
    res.json({ ok: true, metrics: model.metrics });
  });

  router.post('/retrain-sms', (req, res) => {
    const { loadDataset, trainSmsModel } = require('./train-sms');
    const file = path.join(__dirname, 'datasets', 'SMSSpamCollection.txt');
    if (!fs.existsSync(file)) {
      return res.status(400).json({ error: 'dataset missing' });
    }
    const dataset = loadDataset(file);
    const ours = db.prepare('SELECT body, label FROM sms_labels').all();
    for (const r of ours) {
      dataset.push({ text: r.body, y: r.label === 'spam' ? 1 : 0 });
    }
    const { model } = trainSmsModel(dataset, { seed: 42 });
    fs.mkdirSync(path.dirname(smsAi.SMS_MODEL_PATH), { recursive: true });
    fs.writeFileSync(smsAi.SMS_MODEL_PATH, JSON.stringify(model, null, 2));
    res.json({
      ok: true,
      metrics: model.metrics,
      ownLabels: ours.length
    });
  });

  app.use('/api/v1/admin', router);

  app.get('/admin', (req, res) => {
    res.type('html').send(DASHBOARD_HTML);
  });
}

const DASHBOARD_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>RobocallGuard Admin</title>
<style>
  body { font-family: system-ui, sans-serif; margin: 2rem; color: #1a1a1a; }
  table { border-collapse: collapse; width: 100%; margin-top: 1rem; }
  th, td { border: 1px solid #ddd; padding: 6px 10px; text-align: left; font-size: 14px; }
  th { background: #f3f4f6; }
  button { margin-right: 4px; }
  #stats span { display: inline-block; margin-right: 1.5rem; }
  .high { background: #fee2e2; }
  input { padding: 6px; width: 20rem; max-width: 80vw; }
</style>
</head>
<body>
<h1>RobocallGuard Admin</h1>
<p>Admin token <input id="token" placeholder="paste admin token" onchange="saveToken()">
   <button onclick="load()">Refresh</button>
   <button onclick="retrain()">Retrain AI model</button></p>
<div id="stats"></div>
<div id="err" style="color:#b91c1c"></div>
<table>
  <thead><tr>
    <th>Number</th><th>Score</th><th>Reports</th><th>Calls</th>
    <th>24h</th><th>Honeypot</th><th>Line type</th><th>Actions</th>
  </tr></thead>
  <tbody id="rows"></tbody>
</table>
<script>
function token() {
  const t = localStorage.getItem('rg_admin_token') || '';
  document.getElementById('token').value = t;
  return t;
}
function saveToken() { localStorage.setItem('rg_admin_token', document.getElementById('token').value); }
function authQuery() { return '?token=' + encodeURIComponent(token()); }

async function load() {
  document.getElementById('err').textContent = '';
  try {
    const s = await (await fetch('/api/v1/admin/stats' + authQuery())).json();
    if (s.error) throw new Error(s.error);
    const stats = document.getElementById('stats');
    stats.innerHTML =
      '<span>Calls: <b>' + s.calls + '</b></span>' +
      '<span>Numbers: <b>' + s.numbers + '</b></span>' +
      '<span>Reports: <b>' + s.reports + '</b></span>' +
      '<span>Honeypot calls: <b>' + s.honeypotCalls + '</b></span>' +
      '<span>Blocked today: <b>' + s.blockedToday + '</b></span>' +
      '<span>Model: <b>' + (s.model ? ('v' + new Date(s.model.trainedAt).toISOString().slice(0,10) + ' acc ' + s.model.metrics.accuracy) : 'none') + '</b></span>';

    const list = await (await fetch('/api/v1/admin/numbers?limit=50' + authQuery())).json();
    const rows = document.getElementById('rows');
    rows.innerHTML = '';
    for (const n of list.numbers) {
      const tr = document.createElement('tr');
      if (n.score >= 0.6) tr.className = 'high';
      tr.innerHTML =
        '<td>' + n.number + '</td><td>' + n.score + '</td><td>' + n.reports_total +
        '</td><td>' + n.calls_total + '</td><td>' + n.calls_last24h + '</td><td>' +
        (n.honeypot_hits || 0) + '</td><td>' + (n.line_type || '') + '</td>' +
        '<td><button onclick="blockNum(' + n.number + ')">Block</button>' +
        '<button onclick="allowNum(' + n.number + ')">Allow</button></td>';
      rows.appendChild(tr);
    }
  } catch (e) { document.getElementById('err').textContent = 'Error: ' + e.message; }
}

async function act(number, action) {
  await fetch('/api/v1/admin/numbers/' + number + '/' + action, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token: token() })
  });
  load();
}

function blockNum(number) { act(number, 'block'); }
function allowNum(number) { act(number, 'allow'); }

async function retrain() {
  document.getElementById('err').textContent = 'Retraining…';
  try {
    const r = await (await fetch('/api/v1/admin/retrain', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: token() })
    })).json();
    if (r.error) throw new Error(r.error);
    document.getElementById('err').textContent = 'Retrained: ' + JSON.stringify(r.metrics);
  } catch (e) { document.getElementById('err').textContent = 'Retrain failed: ' + e.message; }
}

token();
load();
</script>
</body>
</html>`;

module.exports = { mount };
