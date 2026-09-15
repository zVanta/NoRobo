/** SMS spam model inference. Loads data/sms-model.json; null when absent. */
const fs = require('fs');
const path = require('path');
const { tokenize, PSEUDO } = require('./smsFeatures');

const SMS_MODEL_PATH =
  process.env.SMS_MODEL_PATH || path.join(__dirname, 'data', 'sms-model.json');

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
    const m = JSON.parse(fs.readFileSync(SMS_MODEL_PATH, 'utf8'));
    if (!m || !m.logCond || typeof m.priorSpam !== 'number') return null;
    return m;
  } catch {
    return null;
  }
}

/** Returns { score, spam, trainedAt } or null. */
function predict(text) {
  const m = loadModel();
  if (!m) return null;
  let s = Math.log(m.priorSpam) - Math.log(m.priorHam);
  let hasUrl = false;
  let hasMoney = false;
  const len = String(text || '').length;
  for (const tok of tokenize(text)) {
    if (tok === PSEUDO.URL) hasUrl = true;
    if (tok === PSEUDO.MONEY) hasMoney = true;
    const c = m.logCond[tok];
    if (c) s += c.spam - c.ham;
  }
  if (hasUrl && hasMoney) s += 1.5;
  if (hasUrl && len < 40) s += 0.7;
  const p = sigmoid(s);
  return { score: Math.round(p * 1000) / 1000, spam: p >= 0.5, trainedAt: m.trainedAt };
}

module.exports = { predict, loadModel, SMS_MODEL_PATH };
