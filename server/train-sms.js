/**
 * Multinomial Naive Bayes SMS spam classifier — pure JS, no dependencies.
 * Trained on the public UCI SMS Spam Collection (~5,574 labeled messages),
 * augmented over time with SMS messages our users report.
 *
 * CLI:  node train-sms.js            # train on datasets/SMSSpamCollection.txt
 */
const fs = require('fs');
const path = require('path');
const { tokenize, PSEUDO } = require('./smsFeatures');

function sigmoid(z) {
  if (z >= 0) {
    const e = Math.exp(-z);
    return 1 / (1 + e);
  }
  const e = Math.exp(z);
  return e / (1 + e);
}

function loadDataset(filePath) {
  const raw = fs.readFileSync(filePath, 'utf8');
  const rows = [];
  for (const line of raw.split(/\r?\n/)) {
    if (!line.trim()) continue;
    const idx = line.indexOf('\t');
    if (idx === -1) continue;
    const label = line.slice(0, idx).trim().toLowerCase();
    const text = line.slice(idx + 1).trim();
    if (label !== 'spam' && label !== 'ham') continue;
    rows.push({ text, y: label === 'spam' ? 1 : 0 });
  }
  return rows;
}

function trainSmsModel(dataset, options = {}) {
  const { alpha = 1.0, minCount = 3, split = 0.2, seed } = options;

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

  const spamCounts = new Map();
  const hamCounts = new Map();
  let nSpam = 0;
  let nHam = 0;
  for (const d of train) {
    for (const tok of tokenize(d.text)) {
      const map = d.y === 1 ? spamCounts : hamCounts;
      map.set(tok, (map.get(tok) || 0) + 1);
    }
    if (d.y === 1) nSpam++;
    else nHam++;
  }

  const vocab = [];
  const allTokens = new Set([...spamCounts.keys(), ...hamCounts.keys()]);
  for (const tok of allTokens) {
    if ((spamCounts.get(tok) || 0) + (hamCounts.get(tok) || 0) >= minCount) {
      vocab.push(tok);
    }
  }
  const V = vocab.length;
  let totalSpam = 0;
  let totalHam = 0;
  for (const tok of vocab) {
    totalSpam += spamCounts.get(tok) || 0;
    totalHam += hamCounts.get(tok) || 0;
  }

  const logCond = {};
  for (const tok of vocab) {
    logCond[tok] = {
      spam: Math.log(((spamCounts.get(tok) || 0) + alpha) / (totalSpam + alpha * V)),
      ham: Math.log(((hamCounts.get(tok) || 0) + alpha) / (totalHam + alpha * V))
    };
  }

  const priorSpam = nSpam / Math.max(1, nSpam + nHam);
  const priorHam = 1 - priorSpam;

  function predict(text) {
    let s = Math.log(priorSpam) - Math.log(priorHam);
    let hasUrl = false;
    let hasMoney = false;
    const len = String(text || '').length;
    for (const tok of tokenize(text)) {
      if (tok === PSEUDO.URL) hasUrl = true;
      if (tok === PSEUDO.MONEY) hasMoney = true;
      const c = logCond[tok];
      if (c) s += c.spam - c.ham;
    }
    if (hasUrl && hasMoney) s += 1.5; // classic smishing combo
    if (hasUrl && len < 40) s += 0.7; // tiny message + link
    return sigmoid(s);
  }

  let tp = 0, fp = 0, tn = 0, fn = 0;
  for (const d of test) {
    const p = predict(d.text);
    const pred = p >= 0.5 ? 1 : 0;
    if (pred === 1 && d.y === 1) tp++;
    else if (pred === 1 && d.y === 0) fp++;
    else if (pred === 0 && d.y === 0) tn++;
    else fn++;
  }
  const accuracy = (tp + tn) / Math.max(1, test.length);
  const precision = tp / Math.max(1, tp + fp);
  const recall = tp / Math.max(1, tp + fn);
  const f1 = (2 * precision * recall) / Math.max(1e-9, precision + recall);

  const model = {
    version: 1,
    algorithm: 'multinomial-naive-bayes',
    vocabSize: V,
    logCond,
    priorSpam,
    priorHam,
    alpha,
    minCount,
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

  return { model, predict };
}

if (require.main === module) {
  const file = path.join(__dirname, 'datasets', 'SMSSpamCollection.txt');
  if (!fs.existsSync(file)) {
    console.error('Dataset not found: ' + file);
    process.exit(1);
  }
  const dataset = loadDataset(file);
  console.log(`Loaded ${dataset.length} messages.`);
  const { model } = trainSmsModel(dataset, { seed: 42 });
  console.log(JSON.stringify(model.metrics, null, 2));
  const outPath =
    process.env.SMS_MODEL_PATH || path.join(__dirname, 'data', 'sms-model.json');
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, JSON.stringify(model));
  console.log('Saved ' + outPath);
}

module.exports = { trainSmsModel, loadDataset, sigmoid };
