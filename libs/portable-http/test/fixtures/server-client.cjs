const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const http = require("node:http");
const https = require("node:https");

const mode = process.argv[3];
const secure = mode === "https";
const client = secure ? https : http;
const method = mode === "head" ? "HEAD" : mode === "empty" ? "GET" : "POST";
const payload = Buffer.from([0, 255, 206, 187, 0]);
const request = client.request({
  host: "127.0.0.1", port: Number(process.argv[2]), method, path: "/echo?q=1",
  agent: false,
  ca: secure ? fs.readFileSync(path.join(__dirname, "../../../portable-tls/test/fixtures/ca.pem")) : undefined,
  headers: mode === "fixed" || secure ? { "Content-Length": payload.length } : {},
}, (response) => {
  assert.equal(response.statusCode, mode === "empty" ? 204 : 201);
  assert.equal(response.headers.connection, "close");
  assert.match(response.headers.date, /^[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT$/);
  const chunks = [];
  response.on("data", (data) => chunks.push(data));
  response.on("end", () => {
    assert.deepEqual(Buffer.concat(chunks), method === "HEAD" || mode === "empty" ? Buffer.alloc(0) : payload);
    if (method === "HEAD") assert.equal(response.headers["content-length"], String(payload.length));
  });
  response.on("error", (error) => { console.error(error.message); process.exitCode = 1; });
});
request.on("error", (error) => { console.error(error.message); process.exitCode = 1; });
request.setTimeout(8000, () => { process.exitCode = 1; request.destroy(); });
if (method === "POST") {
  request.write(payload.subarray(0, 2));
  request.end(payload.subarray(2));
} else request.end();
