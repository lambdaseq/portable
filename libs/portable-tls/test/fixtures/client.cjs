const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const tls = require("node:tls");

const version = process.argv[3];
const payload = Buffer.from([...Array(256).keys(), 0, 255, 1]);
const socket = tls.connect({
  host: "127.0.0.1", port: Number(process.argv[2]), servername: "localhost.test",
  ca: fs.readFileSync(path.join(__dirname, process.argv[4] || "ca.pem")),
  minVersion: version, maxVersion: version, ALPNProtocols: ["http/1.1", "h2"],
}, () => {
  assert.equal(socket.authorized, true);
  assert.equal(socket.getProtocol(), version);
  assert.equal(socket.alpnProtocol, "h2");
  socket.write(payload);
});
const chunks = [];
socket.on("data", (data) => chunks.push(data));
socket.on("end", () => {
  assert.deepEqual(Buffer.concat(chunks), payload);
  socket.end();
});
socket.on("error", (error) => { console.error(error.message); process.exitCode = 1; });
socket.setTimeout(8000, () => { process.exitCode = 1; socket.destroy(); });
