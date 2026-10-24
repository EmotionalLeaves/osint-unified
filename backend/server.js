"use strict";

const express = require("express");
const { execFile } = require("child_process");

const app = express();
app.use(express.json({ limit: "64kb" }));

const PORT = process.env.PORT || 3000;
const MAX_REQUESTS_PER_HOUR = Number(process.env.RATE_LIMIT || 100);
const EXEC_MAX_BUFFER = 8 * 1024 * 1024; // 8 MB stdout cap

// ---- Rate limiting -----------------------------------------------------
//
// Applied after authorisation, not before it. Behind a platform proxy
// every request arrives from a handful of the provider's addresses, so a
// per-IP window counted every caller as the same caller: a stranger
// probing the service could exhaust the quota of the one person allowed
// to use it, without ever presenting a token. The limit now belongs to
// the credential, which is the thing there is exactly one of.
const hits = new Map();

function withinRate(key) {
  const now = Date.now();
  const windowStart = now - 3600_000;
  const recent = (hits.get(key) || []).filter((t) => t > windowStart);
  if (recent.length >= MAX_REQUESTS_PER_HOUR) return false;
  recent.push(now);
  hits.set(key, recent);
  // The map is keyed by credential and there is one credential, but an
  // unbounded map is still an unbounded map.
  if (hits.size > 64) {
    // Entries whose window has emptied, not entries that were never
    // filled: an entry is only ever stored after a timestamp is
    // pushed, so the old `v.length === 0` could not match anything
    // and the guard did nothing.
    for (const [k, v] of hits) {
      if (v.filter((at) => at > windowStart).length === 0) hits.delete(k);
    }
  }
  return true;
}

// A partial answer is not an answer, and must not arrive as a 200.
//
// The test was `err && !stdout`: when execFile kills the child on the
// timeout, or cuts it off at maxBuffer, *after* some output has
// arrived, `err` is set and `stdout` is not empty — so the partial
// body went back with HTTP 200 and the app stored it as a complete
// result. subfinder killed at sixty seconds having emitted forty of
// four hundred subdomains became "40 subdomains" in a dossier, as an
// established set.
//
// The partial output is still worth sending; what must not happen is
// sending it as though the tool had finished. It goes back as 206,
// which the app reads as an incomplete answer, with a first line
// saying so in words in case anything reads only the body.
function runBinary(cmd, args, timeoutMs, res) {
  execFile(cmd, args, { timeout: timeoutMs, maxBuffer: EXEC_MAX_BUFFER }, (err, stdout, stderr) => {
    if (err && !stdout) {
      return res.status(500).json({
        tool: cmd,
        error: err.killed ? `Timed out after ${timeoutMs} ms` : (stderr || err.message).slice(0, 500),
      });
    }
    if (err) {
      const why = err.killed
        ? `${cmd} was stopped after ${timeoutMs} ms`
        : `${cmd} stopped early: ${(stderr || err.message).slice(0, 200)}`;
      return res
        .status(206)
        .type("text/plain")
        .send(`PARTIAL: ${why}. What follows is incomplete.\n` + stdout);
    }
    res.type("text/plain").send(stdout || stderr || "(no output)");
  });
}

// ---- Tool handlers ----------------------------------------------------------
// Each receives the parsed input string and the response object.

const handlers = {
  subfinder: (input, res) =>
    runBinary("subfinder", ["-d", input, "-silent", "-json"], 60_000, res),

  httpx: (input, res) =>
    runBinary("httpx", ["-u", input, "-json", "-silent", "-title", "-tech-detect", "-status-code"], 60_000, res),

  nuclei: (input, res) =>
    runBinary("nuclei", ["-u", input, "-jsonl", "-silent", "-t", "http/exposures/", "-t", "http/misconfiguration/"], 120_000, res),

  trufflehog: (input, res) =>
    runBinary("trufflehog", ["git", input, "--json", "--no-update"], 120_000, res),

  // Holehe: which sites have an account registered to this email (keyless).
  holehe: (input, res) =>
    runBinary("holehe", [input, "--only-used", "--no-color"], 90_000, res),

  // Overpass is a pure HTTP call — works keyless against the public API.
  overpass_turbo: async (input, res) => {
    const parts = String(input).split(",").map((s) => parseFloat(s.trim()));
    if (parts.length !== 2 || parts.some(Number.isNaN)) {
      return res.status(400).json({ error: "Expected 'lat, lon'." });
    }
    const [lat, lon] = parts;
    const query = `[out:json][timeout:25];(node(around:500,${lat},${lon})[amenity];node(around:500,${lat},${lon})[man_made];);out body 60;`;
    try {
      const r = await fetch("https://overpass-api.de/api/interpreter", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: "data=" + encodeURIComponent(query),
        signal: AbortSignal.timeout(40_000),
      });
      const text = await r.text();
      // Overpass answers 429 "Too Many Requests" and 504 with an HTML
      // body, and this relayed both as 200 application/json — so the
      // app parsed zero elements and reported "nothing mapped near
      // this point" for a request the service had refused.
      if (!r.ok) {
        return res.status(502).json({
          error: `Overpass answered HTTP ${r.status}`,
          body: text.slice(0, 300),
        });
      }
      res.type("application/json").send(text);
    } catch (e) {
      res.status(502).json({ error: "Overpass request failed: " + e.message });
    }
  },

  // ---- Stubs: real tools, but need credentials/datasets. Honest 501s. ----
  ghunt: (_input, res) =>
    res.status(501).json({ error: "GHunt needs an authenticated Google session cookie. See backend/README.md." }),

  h8mail: (_input, res) =>
    res.status(501).json({ error: "h8mail needs breach-service API keys. Configure them and enable this endpoint." }),

  opensanctions: (_input, res) =>
    res.status(501).json({ error: "OpenSanctions needs a local yente/dataset index. See backend/README.md." }),
};

/**
 * Nobody but you may run these tools.
 *
 * This service shells out to nuclei, httpx and trufflehog against whatever
 * it is handed. Unauthenticated, anyone who finds the URL can point them at
 * third parties from your account, and the abuse reports arrive at your
 * door. The token is a Render secret and the same value goes in the app's
 * settings; without one set, the service refuses to start rather than
 * coming up open.
 */
const TOKEN = process.env.OSINT_BACKEND_TOKEN || "";
if (!TOKEN) {
  console.error(
    "OSINT_BACKEND_TOKEN is not set. Refusing to start an open scanning " +
      "service on the public internet. Set it as a secret and put the same " +
      "value in the app under Settings."
  );
  process.exit(1);
}

function authorised(req) {
  const header = req.get("authorization") || "";
  const presented = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (presented.length !== TOKEN.length) return false;
  // Constant time, so the token cannot be recovered a character at a time.
  let diff = 0;
  for (let i = 0; i < TOKEN.length; i += 1) {
    diff |= presented.charCodeAt(i) ^ TOKEN.charCodeAt(i);
  }
  return diff === 0;
}

app.get("/health", (_req, res) => res.json({ status: "ok", ts: Date.now() }));

app.get("/", (_req, res) =>
  res.json({ service: "osint-unified-backend", tools: Object.keys(handlers) })
);

app.post("/:tool", (req, res) => {
  if (!authorised(req)) return res.status(401).json({ error: "Unauthorised." });
  if (!withinRate("token")) {
    return res.status(429).json({ error: "Rate limit exceeded. Try again later." });
  }

  const handler = handlers[req.params.tool];
  if (!handler) return res.status(404).json({ error: `Unknown tool: ${req.params.tool}` });

  const input = (req.body && req.body.input ? String(req.body.input) : "").trim();
  if (!input) return res.status(400).json({ error: "Missing 'input' in JSON body." });

  // Defensive: reject shell metacharacters; execFile already avoids a shell,
  // but this keeps obviously malformed observables out.
  if (/[;&|`$<>\n\r]/.test(input)) {
    return res.status(400).json({ error: "Input contains disallowed characters." });
  }

  // The metacharacter filter above assumes a shell, which execFile avoids.
  // What it misses is that these tools read a leading dash as a flag, so an
  // input of "-oJ /tmp/x" rewrites their command line from the body.
  if (input.startsWith("-")) {
    return res.status(400).json({ error: "Input may not begin with '-'." });
  }
  if (input.length > 512) {
    return res.status(400).json({ error: "Input too long." });
  }

  try {
    handler(input, res);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.listen(PORT, () => console.log(`osint-unified-backend listening on :${PORT}`));
