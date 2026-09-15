# RobocallGuard backend

Self-hosted reputation server for the RobocallGuard app. Independent of paid
datasets: reputation is computed from your own collected data (community
reports, call frequency, device reach, line type).

## Endpoints
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/lookup` | `{number, token}` → `{carrier, lineType, spamScore, business, ...}` |
| POST | `/api/v1/calls` | `{device, token, calls: [...]}` → ingest call records |
| POST | `/api/v1/report` | `{number, category, token}` → community spam report |
| POST | `/api/v1/honeypot/call` | honeypot webhook (JSON or Twilio form) |
| GET | `/api/v1/top-blocked?token=` | `{numbers: [{number, score}]}` for the app's local blocklist |
| GET | `/api/v1/model?token=` | AI model metadata + metrics |
| GET | `/api/v1/admin/*?token=` | admin stats/numbers/block/allow/retrain |
| GET | `/health` | liveness |

## Deploy on Debian

```bash
# 1. Install Node.js 18+ (Debian 12 provides Node 18)
sudo apt update && sudo apt install -y nodejs npm

# 2. Create a service user and install the app
sudo useradd --system --home /opt/robocallguard --shell /usr/sbin/nologin robocallguard
sudo mkdir -p /opt/robocallguard/data
sudo chown -R robocallguard:robocallguard /opt/robocallguard
sudo -u robocallguard cp -r server/* /opt/robocallguard/   # or git clone

# 3. Configure secrets (never commit .env)
sudo cp /opt/robocallguard/.env.example /opt/robocallguard/.env
sudo nano /opt/robocallguard/.env        # set AUTH_TOKEN, PORT, optional keys

# 4. Install dependencies and start
cd /opt/robocallguard
sudo npm install --omit=dev
sudo cp deploy/robocallguard.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now robocallguard

# 5. HTTPS (recommended) — nginx + certbot for your domain
sudo apt install -y nginx certbot python3-certbot-nginx
# adapt deploy/nginx.conf to /etc/nginx/sites-available/robocallguard, then:
sudo certbot --nginx -d your-domain.example
```

Set the same `AUTH_TOKEN` in the app's Server screen along with the URL
(e.g. `https://your-domain.example`).

## Provider model (optional)
The server scores independently. Optional carrier lookups plug into
`providers.js` via env keys (e.g. `NUMVERIFY_KEY`) and are cached for 7 days.

## AI scoring model

A logistic-regression classifier trained only on your collected data.
Ground truth labels:
- **spam** = numbers with >= 1 community report or >= 2 blocklist rejections
- **legitimate** = numbers reached via allowlist/contacts with zero reports

```bash
cd /opt/robocallguard
node train.js --synthetic          # demo on generated data
node train.js --from-db            # train on live DB -> data/model.json
node --test                        # run the test suite (no deps needed)
```

The lookup API returns `aiScore`/`aiSpam` and `/api/v1/model` exposes
metrics. The heuristic scorer always provides a safety floor beneath the
model score. Retrain periodically (e.g. cron) as data accumulates.

## Honeypot collector

Point DID/SIP provider webhooks at:

    POST /api/v1/honeypot/call?token=<AUTH_TOKEN>
    JSON:  {"did": "+12125550100", "from": "+18005550123", "source": "twilio"}
    Twilio: form-encoded From / To

Any number that calls your bait DIDs gets `honeypot_hits` incremented — an
immediate +0.8 reputation boost and an AI feature. Publish the DIDs where
robocall scrapers find them to start baiting callers.

## Admin dashboard

Set `ADMIN_TOKEN` in `.env`, then open `https://your-domain/admin` and
paste the token. The dashboard shows platform stats, top spam numbers,
and block/allow controls, with one-click AI retraining.
