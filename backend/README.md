# OSINT Unified — Cloud Backend

Runs the heavy / credentialed tools the phone shouldn't. The Android app's
`CloudToolExecutor` POSTs `{"input": "..."}` to `/<toolId>` and stores the
response text as the result.

## Endpoints

| Route | Tool | Status | Input |
|-------|------|--------|-------|
| `POST /subfinder` | Subfinder | works | domain |
| `POST /httpx` | httpx | works | url/host |
| `POST /nuclei` | Nuclei | works | url/host |
| `POST /trufflehog` | TruffleHog | works | git repo url |
| `POST /overpass_turbo` | Overpass | works (keyless) | `lat, lon` |
| `POST /ghunt` | GHunt | 501 — needs Google session cookie | email |
| `POST /h8mail` | h8mail | 501 — needs breach API keys | email |
| `POST /opensanctions` | OpenSanctions | 501 — needs yente/dataset | entity |
| `GET /health` | — | liveness probe | — |

## Deploy on Render (free, no PC)

1. Push this repo to GitHub.
2. In Render: **New → Blueprint**, pick the repo. `render.yaml` provisions a
   Docker web service from `backend/`.
3. Copy the service URL (e.g. `https://osint-unified-backend.onrender.com`).
4. In the app: gear icon → paste it into **Cloud backend URL** → Save.

## Run locally (Docker)

```
cd backend
docker build -t osint-backend .
docker run -p 3000:3000 osint-backend
curl -X POST localhost:3000/overpass_turbo -H 'content-type: application/json' \
  -d '{"input":"40.7128,-74.0060"}'
```

## Security notes

- `execFile` is used (no shell); inputs with shell metacharacters are rejected.
- Rate limit per credential (default 100/hr, `RATE_LIMIT` env var),
  applied after authorisation. It was per-IP, and behind a platform
  proxy every request arrives from a handful of the provider's
  addresses — so a stranger probing the service could exhaust the
  quota of the one person allowed to use it without ever presenting
  a token.
- Active scanners (nuclei) are intrusive — only point them at targets you are
  authorized to test.
- The 501 endpoints are intentionally disabled until you supply the required
  credentials; wire them up in `server.js` when ready.
