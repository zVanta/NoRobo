/**
 * Model inference. Loads the trained model (data/model.json) and scores a
 * number record. Returns null when no model exists yet — callers fall back
 * to the heuristic scorer.
 */
const fs = require('fs');
const path = require('path');
const { extractFeatures } = require('./features');

const MODEL_PATH = process.env.MODEL_PATH || path.join(__dirname, 'data', 'model.json');

function sigmoid(z) {
  if (z >= 0) {
    const e = Math.exp(-z);
    return 1 / (1 + e);
  }
  const e = Math.exp(z);
  return e / (1 + e);
}

function loadModel() {
  try {
    const raw = fs.readFileSync(MODEL_PATH, 'utf8');
    const m = JSON.parse(raw);
    if (!m || !Array.isArray(m.weights)) return null;
    return m;
  } catch {
    return null;
  }
}

/** Returns { score, spam, trainedAt } or null. */
function predict(rec) {
  const m = loadModel();
  if (!m) return null;
  const x = extractFeatures(rec);
  let z = m.bias || 0;
  for (let j = 0; j < x.length; j++) {
    const std = (m.stats.std[j] || 1);
    z += m.weights[j] * ((x[j] - m.stats.mean[j]) / std);
  }
  const p = sigmoid(z);
  const threshold = m.threshold === undefined ? 0.5 : m.threshold;
  return {
    score: Math.round(p * 1000) / 1000,
    spam: p >= threshold,
    trainedAt: m.trainedAt
  };
}

module.exports = { predict, loadModel, MODEL_PATH };
