const test = require('node:test');
const assert = require('node:assert');
const { computeScore } = require('../scoring');

test('honeypot hit floors the score at 0.8', () => {
  assert.strictEqual(computeScore({ honeypot_hits: 1 }), 0.8);
});

test('VoIP line type adds signal over mobile', () => {
  const mobile = computeScore({ line_type: 'mobile' });
  const voip = computeScore({ line_type: 'voip' });
  assert.strictEqual(voip - mobile, 0.1);
});

test('reports, volume, and reach combine with caps', () => {
  const s = computeScore({
    reports_total: 10,
    calls_last24h: 20,
    distinct_devices: 10
  });
  assert.strictEqual(s, 1); // 0.5 + 0.2 + 0.2 + 0.1 (aggressive)
});

test('clean number scores zero', () => {
  assert.strictEqual(computeScore({}), 0);
});
