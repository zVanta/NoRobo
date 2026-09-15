/**
 * Feature extraction for the spam classifier.
 * All features derive from our own collected data — no third-party datasets.
 */
const FEATURE_NAMES = [
  'reports_total',
  'reports_ratio',
  'calls_last24h',
  'calls_last7d',
  'distinct_devices',
  'is_voip',
  'is_landline'
];

function isType(lineType, kind) {
  const lt = String(lineType || '').toLowerCase();
  return lt.includes(kind) || lt === kind;
}

function extractFeatures(rec) {
  const callsTotal = rec.calls_total || 0;
  const reports = rec.reports_total || 0;
  return [
    Math.min(reports, 10),
    callsTotal > 0 ? reports / callsTotal : 0,
    rec.calls_last24h || 0,
    rec.calls_last7d || 0,
    rec.distinct_devices || 0,
    isType(rec.line_type, 'voip') ? 1 : 0,
    isType(rec.line_type, 'landline') ? 1 : 0
  ];
}

module.exports = { FEATURE_NAMES, extractFeatures };
