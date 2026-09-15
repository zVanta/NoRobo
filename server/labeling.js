/**
 * Builds a labeled training dataset from our own database.
 *
 *   positive (spam):  numbers with >= 1 community report, OR >= 2 explicit
 *                     blocklist rejections
 *   negative (legit): numbers reached through the allowlist or from contacts
 *                     with zero community reports
 *   everything else:  unlabeled, excluded from training
 */
const db = require('./db');
const { extractFeatures } = require('./features');

const DAY_MS = 24 * 3600 * 1000;

function loadDataset(now = Date.now()) {
  const pos = db
    .prepare(
      `SELECT n.*,
              (SELECT COUNT(*) FROM calls c
                 WHERE c.number = n.number AND c.ts > ?) AS calls_last7d,
              (SELECT COUNT(*) FROM calls c
                 WHERE c.number = n.number
                   AND c.action IN ('REJECT','VOICEMAIL','SILENCE')
                   AND c.reason LIKE 'blocklist%') AS block_rejections
         FROM numbers n
        WHERE n.reports_total >= 1 OR
              (SELECT COUNT(*) FROM calls c
                 WHERE c.number = n.number
                   AND c.action IN ('REJECT','VOICEMAIL','SILENCE')
                   AND c.reason LIKE 'blocklist%') >= 2`
    )
    .all(now - 7 * DAY_MS);

  const neg = db
    .prepare(
      `SELECT n.*,
              (SELECT COUNT(*) FROM calls c
                 WHERE c.number = n.number AND c.ts > ?) AS calls_last7d
         FROM numbers n
        WHERE n.reports_total = 0
          AND n.calls_total >= 1
          AND EXISTS (SELECT 1 FROM calls c
                        WHERE c.number = n.number
                          AND c.reason IN ('allowlist','contact'))`
    )
    .all(now - 7 * DAY_MS);

  const dataset = [];
  for (const rec of pos) {
    dataset.push({ x: extractFeatures(rec), y: 1, number: rec.number });
  }
  for (const rec of neg) {
    dataset.push({ x: extractFeatures(rec), y: 0, number: rec.number });
  }
  return dataset;
}

module.exports = { loadDataset };
