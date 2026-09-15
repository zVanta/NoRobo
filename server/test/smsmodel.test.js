const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const { tokenize, PSEUDO } = require('../smsFeatures');
const { trainSmsModel, loadDataset } = require('../train-sms');

const DATASET = path.join(__dirname, '..', 'datasets', 'SMSSpamCollection.txt');

test('tokenizer produces structural pseudo-tokens', () => {
  const tokens = tokenize('WIN $500 now! Click https://bit.ly/abc now');
  assert.ok(tokens.includes(PSEUDO.URL), 'url pseudo-token');
  assert.ok(tokens.includes(PSEUDO.MONEY), 'money pseudo-token');
});

test('dataset loads with spam and ham labels', () => {
  const data = loadDataset(DATASET);
  assert.ok(data.length > 5000, `expected >5000, got ${data.length}`);
  assert.ok(data.some((d) => d.y === 1) && data.some((d) => d.y === 0));
});

test('NB model reaches strong accuracy on the UCI dataset', () => {
  const { model } = trainSmsModel(loadDataset(DATASET), { seed: 42 });
  assert.ok(model.metrics.testSize > 900, 'held-out set sizable');
  assert.ok(model.metrics.accuracy > 0.95, `accuracy ${model.metrics.accuracy}`);
  assert.ok(model.metrics.precision > 0.9, `precision ${model.metrics.precision}`);
  assert.ok(model.metrics.recall > 0.85, `recall ${model.metrics.recall}`);
});

test('predict flags obvious spam and passes ham', () => {
  const { predict } = trainSmsModel(loadDataset(DATASET), { seed: 42 });
  const spam = predict(
    'Congratulations! You have WON a FREE iPhone. Claim $500 now at http://bit.ly/win123'
  );
  assert.ok(spam > 0.9, `expected spam, got ${spam}`);
  const ham = predict('Hey are we still on for lunch tomorrow?');
  assert.ok(ham < 0.5, `expected ham, got ${ham}`);
});

test('training is deterministic for a fixed seed', () => {
  const dataset = loadDataset(DATASET);
  const a = trainSmsModel(dataset, { seed: 42 });
  const b = trainSmsModel(dataset, { seed: 42 });
  assert.strictEqual(a.model.metrics.accuracy, b.model.metrics.accuracy);
  assert.strictEqual(a.model.vocabSize, b.model.vocabSize);
});
