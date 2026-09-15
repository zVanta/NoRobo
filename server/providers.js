/**
 * Optional external carrier/line-type lookups.
 *
 * The server is fully functional WITHOUT these: reputation comes from our own
 * data (see scoring.js). Providers are enabled only if their key is present
 * in .env, and results are cached server-side (lookup_cache table).
 *
 * To add another provider (Twilio, Telnyx, etc.), push a function with the
 * same shape and set its key in .env.
 */
const https = require('https');
const http = require('http');

function getJson(url, headers = {}, timeoutMs = 3000) {
  return new Promise((resolve) => {
    const mod = url.startsWith('https') ? https : http;
    const req = mod.get(url, { headers, timeout: timeoutMs }, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => {
        try { resolve(JSON.parse(data)); } catch { resolve(null); }
      });
    });
    req.on('timeout', () => { req.destroy(); resolve(null); });
    req.on('error', () => resolve(null));
  });
}

const providers = [];

if (process.env.NUMVERIFY_KEY) {
  providers.push(async (digits) => {
    const j = await getJson(
      'http://apilayer.net/api/validate?access_key=' +
        process.env.NUMVERIFY_KEY + '&number=' + digits + '&format=1'
    );
    if (!j || !j.valid) return { any: false };
    return {
      any: true,
      carrier: j.carrier || null,
      lineType: j.line_type || null,
      business: null
    };
  });
}

// omkarcloud/phone-lookup-api — free tier: 5,000 lookups/month.
//   GET https://carrier-lookup-api.omkar.cloud/lookup?phone=<digits>
//   Header: API-Key
if (process.env.OMKAR_API_KEY) {
  providers.push(async (digits) => {
    const j = await getJson(
      'https://carrier-lookup-api.omkar.cloud/lookup?phone=' + digits,
      { 'API-Key': process.env.OMKAR_API_KEY }
    );
    if (!j) return { any: false };
    const carrierName =
      (typeof j.carrier === 'string'
        ? j.carrier
        : j.carrier && j.carrier.name) || null;
    const lineTypeRaw = j.line_type || j.lineType || j.type || null;
    if (!carrierName && !lineTypeRaw) return { any: false };
    return {
      any: true,
      carrier: carrierName,
      lineType: lineTypeRaw ? String(lineTypeRaw).toLowerCase() : null,
      business: null
    };
  });
}

/** Try providers in order until one returns data. */
async function lookup(digits) {
  for (const p of providers) {
    const r = await p(digits);
    if (r.any) return r;
  }
  return { any: false, carrier: null, lineType: null, business: null };
}

module.exports = { lookup };
