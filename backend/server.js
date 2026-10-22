"use strict";

const express = require("express");
const { execFile } = require("child_process");

const app = express();
app.use(express.json({ limit: "64kb" }));

const PORT = process.env.PORT || 3000;
const MAX_REQUESTS_PER_HOUR = Number(process.env.RATE_LIMIT || 100);
const EXEC_MAX_BUFFER = 8 * 1024 * 1024; // 8 MB stdout cap

// ---- Simple in-memory per-IP sliding-window rate limiter --------------------
const hits = new Map();
app.use((req, res, next) => {
  const ip = req.ip || "unknown";
  const now = Date.now();
  const windowStart = now - 3600_000;
  const recent = (hits.get(ip) || []).filter((t) => t > windowStart);
  if (recent.length >= MAX_REQUESTS_PER_HOUR) {
    return res.status(429).json({ error: "Rate limit exceeded. Try again later." });
  }
  recent.push(now);
  hits.set(ip, recent);
  next();
});

function runBinary(cmd, args, timeoutMs, res) {
  execFile(cmd, args, { timeout: timeoutMs, maxBuffer: EXEC_MAX_BUFFER }, (err, stdout, stderr) => {
    if (err && !stdout) {
      return res.status(500).json({
        tool: cmd,
        error: err.killed ? `Timed out after ${timeoutMs} ms` : (stderr || err.message).slice(0, 500),
      });
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
      });
      const text = await r.text();
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

app.get("/health", (_req, res) => res.json({ status: "ok", ts: Date.now() }));

app.get("/", (_req, res) =>
  res.json({ service: "osint-unified-backend", tools: Object.keys(handlers) })
);

app.post("/:tool", (req, res) => {
  const handler = handlers[req.params.tool];
  if (!handler) return res.status(404).json({ error: `Unknown tool: ${req.params.tool}` });

  const input = (req.body && req.body.input ? String(req.body.input) : "").trim();
  if (!input) return res.status(400).json({ error: "Missing 'input' in JSON body." });

  // Defensive: reject shell metacharacters; execFile already avoids a shell,
  // but this keeps obviously malformed observables out.
  if (/[;&|`$<>\n\r]/.test(input)) {
    return res.status(400).json({ error: "Input contains disallowed characters." });
  }

  try {
    handler(input, res);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.listen(PORT, () => console.log(`osint-unified-backend listening on :${PORT}`));
