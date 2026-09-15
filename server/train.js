/**
 * Logistic regression spam classifier — pure JS, no dependencies.
 *
 * CLI usage:
 *   node train.js --synthetic                 # demo train on generated data
 *   node train.js --synthetic --out model.json
 *   node train.js --from-db                   # train on live DB, save to data/model.json
 */
const fs = require('fs');
const path = require('path');
const { FEATURE_NAMES, extractFeatures } = require('./features');

function sigmoid(z) {
  if (z >= 0) {
    const e = Math.exp(-z);
    return 1 / (1 + e);
  }
  const e = Math.exp(z);
  return e / (1 + e);
}

function dot(a, b) {
  let s = 0;
  for (let i = 0; i < a.length; i++) s += a[i] * b[i];
  return s;
}

/** Z-score normalization. If stats provided, uses them (inference mode). */
function normalize(rows, stats) {
  const m = FEATURE_NAMES.length;
  const n = rows.length;
  const mean = stats ? stats.mean : new Array(m).fill(0);
  const std = stats ? stats.std : new Array(m).fill(1);
  if (!stats) {
    for (const row of rows) {
      for (let j = 0; j < m; j++) mean[j] += row[j] / Math.max(1, n);
    }
    for (const row of rows) {
      for (let j = 0; j < m; j++) std[j] += Math.pow(row[j] - mean[j], 2) / Math.max(1, n);
    }
    for (let j = 0; j < m; j++) std[j] = Math.sqrt(std[j]) || 1;
  }
  const out = rows.map((row) => row.map((v, j) => (v - mean[j]) / std[j]));
  return { rows: out, stats: { mean, std } };
}

function trainModel(dataset, options = {}) {
  const { epochs = 250, lr = 0.5, l2 = 0.01, split = 0.2, seed } = options;

  // Deterministic shuffle (simple LCG) so results are reproducible.
  const data = [...dataset];
  if (seed !== undefined) {
    let s = seed >>> 0;
    for (let i = data.length - 1; i > 0; i--) {
      s = (s * 1664525 + 1013904223) >>> 0;
      const j = s % (i + 1);
      [data[i], data[j]] = [data[j], data[i]];
    }
  }

  const cut = Math.max(1, Math.floor(data.length * (1 - split)));
  const train = data.slice(0, cut);
  const test = data.slice(cut);

  const norm = normalize(train.map((d) => d.x));
  const m = FEATURE_NAMES.length;
  const weights = new Array(m).fill(0);
  let bias = 0;

  for (let e = 0; e < epochs; e++) {
    let gw = new Array(m).fill(0);
    let gb = 0;
    for (let i = 0; i < norm.rows.length; i++) {
      const p = sigmoid(dot(weights, norm.rows[i]) + bias);
      const err = p - train[i].y;
      for (let j = 0; j < m; j++) gw[j] += err * norm.rows[i][j];
      gb += err;
    }
    const n = Math.max(1, norm.rows.length);
    for (let j = 0; j < m; j++) weights[j] -= lr * (gw[j] / n + l2 * weights[j]);
    bias -= lr * (gb / n);
  }

  // Evaluate on the held-out split.
  const testNorm = normalize(test.map((d) => d.x), norm.stats);
  let tp = 0, fp = 0, tn = 0, fn = 0;
  test.forEach((d, i) => {
    const p = sigmoid(dot(weights, testNorm.rows[i]) + bias);
    const pred = p >= 0.5 ? 1 : 0;
    if (pred === 1 && d.y === 1) tp++;
    else if (pred === 1 && d.y === 0) fp++;
    else if (pred === 0 && d.y === 0) tn++;
    else fn++;
  });

  const accuracy = (tp + tn) / Math.max(1, test.length);
  const precision = tp / Math.max(1, tp + fp);
  const recall = tp / Math.max(1, tp + fn);
  const f1 = (2 * precision * recall) / Math.max(1e-9, precision + recall);

  const model = {
    version: 1,
    algorithm: 'logistic-regression',
    weights,
    bias,
    stats: norm.stats,
    threshold: 0.5,
    featureNames: FEATURE_NAMES,
    trainedAt: Date.now(),
    metrics: {
      accuracy,
      precision,
      recall,
      f1,
      trainSize: train.length,
      testSize: test.length
    }
  };

  const predict = (x) => {
    const xn = normalize([x], norm.stats).rows[0];
    return sigmoid(dot(weights, xn) + bias);
  };

  return { model, predict };
}

function syntheticDataset(n = 4000, seed = 42) {
  const rand = () => {
    seed = (seed * 1664525 + 1013904223) >>> 0;
    return seed / 4294967296;
  };
  const out = [];
  for (let i = 0; i < n; i++) {
    const spam = rand() < 0.5;
    const reports = spam
      ? 1 + Math.floor(rand() * 8)
      : rand() < 0.92 ? 0 : 1;
    const callsTotal = 1 + Math.floor(rand() * 30);
    const calls24 = spam
      ? Math.min(callsTotal, 1 + Math.floor(rand() * 6))
      : Math.floor(rand() * 3);
    const calls7d = spam
      ? Math.min(callsTotal, calls24 + Math.floor(rand() * 8))
      : Math.floor(rand() * 5);
    const devices = spam ? 1 + Math.floor(rand() * 8) : 1;
    const voip = spam ? (rand() < 0.55 ? 1 : 0) : rand() < 0.05 ? 1 : 0;
    const landline = spam ? 0 : rand() < 0.3 ? 1 : 0;
    out.push({
      x: [
        Math.min(reports, 10),
        reports / callsTotal,
        calls24,
        calls7d,
        devices,
        voip,
        landline
      ],
      y: spam ? 1 : 0
    });
  }
  return out;
}

if (require.main === module) {
  const args = process.argv.slice(2);

  if (args.includes('--synthetic')) {
    const { model } = trainModel(syntheticDataset(), { seed: 42 });
    console.log('Synthetic training complete.');
    console.log(JSON.stringify(model.metrics, null, 2));
    const outIdx = args.indexOf('--out');
    if (outIdx !== -1 && args[outIdx + 1]) {
      const outPath = path.resolve(args[outIdx + 1]);
      fs.mkdirSync(path.dirname(outPath), { recursive: true });
      fs.writeFileSync(outPath, JSON.stringify(model, null, 2));
      console.log('Saved model to ' + outPath);
    }
  } else if (args.includes('--from-db')) {
    const { loadDataset } = require('./labeling');
    const dataset = loadDataset();
    console.log(`Loaded ${dataset.length} labeled numbers from DB.`);
    if (dataset.length < 50) {
      console.log('Not enough labeled data yet (<50). Keep collecting — training aborted.');
      process.exit(0);
    }
    const { model } = trainModel(dataset, { seed: 42 });
    const outPath = process.env.MODEL_PATH || path.join(__dirname, 'data', 'model.json');
    fs.mkdirSync(path.dirname(outPath), { recursive: true });
    fs.writeFileSync(outPath, JSON.stringify(model, null, 2));
    console.log('Saved model to ' + outPath);
    console.log(JSON.stringify(model.metrics, null, 2));
  } else {
    console.log('Usage: node train.js --synthetic [--out file] | --from-db');
  }
}

module.exports = { trainModel, syntheticDataset, sigmoid };
