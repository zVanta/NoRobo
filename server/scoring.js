/**
 * Independent reputation scoring — built entirely from our own collected data:
 *   1. Community reports (our users flag spam numbers)
 *   2. Call frequency / burst behavior (calls in last 24h)
 *   3. Device reach (how many different devices saw this number)
 *   4. Line type (VoIP/fixed numbers are riskier)
 *   5. Aggressive pattern (reports + heavy volume simultaneously)
 *
 * Returns a 0..1 score. No third-party datasets required.
 */
function computeScore(row) {
  const reports = row.reports_total || 0;
  const last24h = row.calls_last24h || 0;
  const devices = row.distinct_devices || 0;
  const lineType = (row.line_type || '').toLowerCase();

  let score = 0;
  score += Math.min(reports * 0.25, 0.5);          // community reports (cap 0.5)
  score += Math.min(last24h * 0.05, 0.2);          // burst volume
  score += Math.min(devices * 0.08, 0.2);          // reach across devices
  if (lineType.includes('voip')) score += 0.1;     // VoIP line
  if (reports >= 2 && last24h >= 3) score += 0.1;  // aggressive pattern

  return Math.max(0, Math.min(1, Math.round(score * 100) / 100));
}

module.exports = { computeScore };
