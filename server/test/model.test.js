const test = require('node:test');
const assert = require('node:assert');
const { FEATURE_NAMES, extractFeatures } = require('../features');
const { trainModel, syntheticDataset } = require('../train');

test('extractFeatures returns one value per named feature', () => {
  const x = extractFeatures({
    reports_total: 3,
    calls_total: 10,
    calls_last24h: 4,
    calls_last7d: 9,
    distinct_devices: 5,
    honeypot_hits: 2,
    line_type: 'voip'
  });
  assert.strictEqual(x.length, FEATURE_NAMES.length);
  assert.strictEqual(x[5], 2); // honeypot_hits
  assert.strictEqual(x[6], 1); // is_voip
  assert.strictEqual(x[7], 0); // is_landline
});

test('logistic regression reaches strong accuracy on synthetic data', () => {
  const { model } = trainModel(syntheticDataset(4000), { seed: 42 });
  assert.ok(model.metrics.testSize > 500, 'held-out set should be sizable');
  assert.ok(model.metrics.accuracy > 0.85, `accuracy ${model.metrics.accuracy}`);
  assert.ok(model.metrics.precision > 0.8, `precision ${model.metrics.precision}`);
  assert.ok(model.metrics.recall > 0.8, `recall ${model.metrics.recall}`);
});

test('predict scores obvious spam high and clean contact low', () => {
  const { predict } = trainModel(syntheticDataset(4000), { seed: 42 });

  const spam = predict(extractFeatures({
    reports_total: 6,
    calls_total: 10,
    calls_last24h: 5,
    calls_last7d: 9,
    distinct_devices: 6,
    honeypot_hits: 1,
    line_type: 'voip'
  }));
  assert.ok(spam > 0.9, `expected high spam score, got ${spam}`);

  const legit = predict(extractFeatures({
    reports_total: 0,
    calls_total: 3,
    calls_last24h: 0,
    calls_last7d: 1,
    distinct_devices: 1,
    honeypot_hits: 0,
    line_type: 'mobile'
  }));
  assert.ok(legit < 0.1, `expected low spam score, got ${legit}`);
});

test('training is reproducible with a seed', () => {
  const a = trainModel(syntheticDataset(2000), { seed: 7 });
  const b = trainModel(syntheticDataset(2000), { seed: 7 });
  assert.deepStrictEqual(a.model.weights, b.model.weights);
  assert.strictEqual(a.model.metrics.accuracy, b.model.metrics.accuracy);
});
