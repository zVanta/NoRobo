const path = require('path');
const fs = require('fs');
const Database = require('better-sqlite3');

const DB_PATH = process.env.DB_PATH || path.join(__dirname, 'data', 'calls.db');
fs.mkdirSync(path.dirname(DB_PATH), { recursive: true });

const db = new Database(DB_PATH);
db.pragma('journal_mode = WAL');

db.exec(`
CREATE TABLE IF NOT EXISTS calls (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device TEXT NOT NULL,
  number TEXT NOT NULL,
  ts INTEGER NOT NULL,
  action TEXT,
  reason TEXT,
  carrier TEXT,
  line_type TEXT,
  spam_score REAL,
  business TEXT,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  number TEXT NOT NULL,
  category TEXT NOT NULL,
  ts INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS numbers (
  number TEXT PRIMARY KEY,
  calls_total INTEGER NOT NULL DEFAULT 0,
  calls_last24h INTEGER NOT NULL DEFAULT 0,
  last_seen INTEGER NOT NULL DEFAULT 0,
  distinct_devices INTEGER NOT NULL DEFAULT 0,
  reports_total INTEGER NOT NULL DEFAULT 0,
  carrier TEXT,
  line_type TEXT,
  business TEXT,
  score REAL NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS lookup_cache (
  number TEXT PRIMARY KEY,
  carrier TEXT,
  line_type TEXT,
  business TEXT,
  checked_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS honeypot_calls (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  did TEXT NOT NULL,
  from_number TEXT NOT NULL,
  ts INTEGER NOT NULL,
  source TEXT,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sms_ingest (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device TEXT NOT NULL,
  sender TEXT,
  body TEXT,
  ts INTEGER NOT NULL,
  flag TEXT,
  ai_score REAL,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sms_labels (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  sender TEXT,
  body TEXT NOT NULL,
  label TEXT NOT NULL,
  ts INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_calls_number ON calls(number);
CREATE INDEX IF NOT EXISTS idx_calls_ts ON calls(ts);
CREATE INDEX IF NOT EXISTS idx_numbers_score ON numbers(score DESC);
`);

// Migration: honeypot_hits column for numbers created before the honeypot feature.
const numberCols = db.prepare('PRAGMA table_info(numbers)').all();
if (!numberCols.some((c) => c.name === 'honeypot_hits')) {
  db.exec('ALTER TABLE numbers ADD COLUMN honeypot_hits INTEGER NOT NULL DEFAULT 0');
}

module.exports = db;
