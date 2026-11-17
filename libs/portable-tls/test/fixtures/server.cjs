const fs = require("node:fs");
const net = require("node:net");
const tls = require("node:tls");
const path = require("node:path");

const mode = process.argv[2];
const peers = new Set();
const payload = Uint8Array.from([...Array(256).keys(), 0, 255, 1]);
const certificate = mode === "expired" ? "expired.pem" : mode === "ip-mismatch" ? "dns-only.pem" : "server.pem";

function trackPeer(peer) {
  peers.add(peer);
  peer.on("close", () => peers.delete(peer));
  peer.on("error", () => {});
  peer.setTimeout(15000, () => peer.destroy());
}

function servePeer(peer) {
  if (mode === "sni") {
    peer.end(String(peer.servername || ""));
    return;
  }
  if (mode === "payload") {
    peer.write(payload.subarray(0, 3));
    peer.end(payload.subarray(3));
    return;
  }
  peer.on("data", (data) => peer.write(data));
  peer.on("end", () => peer.end());
}

const server = ["silent", "plain"].includes(mode)
  ? net.createServer((peer) => {
      if (mode === "plain") peer.end("This is not TLS.\r\n");
    })
  : tls.createServer(
      {
        key: fs.readFileSync(path.join(__dirname, "server-key.pem")),
        cert: fs.readFileSync(path.join(__dirname, certificate)),
        minVersion: mode === "tls13" ? "TLSv1.3" : "TLSv1.2",
        maxVersion: mode === "tls12" ? "TLSv1.2" : "TLSv1.3",
        ALPNProtocols: ["http/1.1"],
        allowHalfOpen: true,
      },
      servePeer,
    );

server.on("connection", trackPeer);
server.on("tlsClientError", () => {});
server.on("error", (error) => {
  console.error(error.message);
  process.exitCode = 1;
});
server.listen(0, "127.0.0.1", () => process.stdout.write(`${server.address().port}\n`));
process.on("SIGTERM", () => {
  peers.forEach((peer) => peer.destroy());
  server.close(() => process.exit(0));
});
