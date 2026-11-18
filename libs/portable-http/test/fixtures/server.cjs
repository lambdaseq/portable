const fs = require("node:fs");
const path = require("node:path");
const http = require("node:http");
const https = require("node:https");
const net = require("node:net");
const zlib = require("node:zlib");

const tlsFixtures = path.resolve(__dirname, "../../../portable-tls/test/fixtures");
const peers = new Set();
const payload = Buffer.from([0, 255, 206, 187, 0]);
const ports = [];

function trackPeer(peer) {
  peers.add(peer);
  peer.on("close", () => peers.delete(peer));
  peer.on("error", () => {});
}

function handleRequest(request, response) {
  response.setHeader("Access-Control-Allow-Origin", "*");
  response.setHeader("Access-Control-Expose-Headers", "*");
  if (request.method === "OPTIONS" && request.headers["access-control-request-method"]) {
    response.setHeader("Access-Control-Allow-Methods", "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS");
    response.setHeader("Access-Control-Allow-Headers", request.headers["access-control-request-headers"] || "*");
    response.writeHead(204);
    response.end();
    return;
  }
  const target = request.url.split("?")[0];
  if (target === "/browser.html") {
    const config = `<script>globalThis.PORTABLE_HTTP_TEST_PORTS=${JSON.stringify(ports)};globalThis.PORTABLE_HTTP_TEST_CA=${JSON.stringify(fs.readFileSync(path.join(tlsFixtures, "ca.pem"), "utf8"))};</script>`;
    response.setHeader("Content-Type", "text/html");
    response.end(fs.readFileSync("target/browser.html", "utf8").replace("</head>", `${config}</head>`));
    return;
  }
  if (["/browser-tests.js", "/target/browser-tests.js"].includes(target)) {
    response.setHeader("Content-Type", "application/javascript");
    response.end(fs.readFileSync("target/browser-tests.js"));
    return;
  }
  if (target === "/slow") return;
  if (target === "/slow-body") {
    response.writeHead(200, { "Content-Length": "5" });
    response.write("a");
    return;
  }
  if (target === "/head") {
    response.writeHead(200, { "Content-Length": "1234" });
    response.end();
    return;
  }
  if (target === "/empty") {
    response.writeHead(204);
    response.end();
    return;
  }
  if (target === "/not-modified") {
    response.writeHead(304, { "Content-Length": "1234" });
    response.end();
    return;
  }
  if (target === "/redirect") {
    response.writeHead(302, { Location: "/get" });
    response.end();
    return;
  }
  if (["/gzip", "/deflate", "/stacked", "/gzip-empty", "/gzip-large", "/gzip-corrupt", "/gzip-truncated"].includes(target)) {
    const data = target === "/gzip-empty" ? Buffer.alloc(0) : target === "/gzip-large" ? Buffer.alloc(2048, 1) : payload;
    const format = target === "/deflate" ? "deflate" : target === "/stacked" ? "gzip, deflate" : "gzip";
    let encoded = format === "deflate" ? zlib.deflateSync(data) : zlib.gzipSync(data);
    if (target === "/stacked") encoded = zlib.deflateSync(encoded);
    if (target === "/gzip-corrupt") encoded[encoded.length - 8] ^= 1;
    if (target === "/gzip-truncated") encoded = encoded.subarray(0, encoded.length - 1);
    response.writeHead(200, { "Content-Encoding": format, "Content-Length": String(encoded.length) });
    response.end(encoded);
    return;
  }
  if (target === "/large") {
    response.end(Buffer.alloc(2048, 1));
    return;
  }
  if (target === "/error") {
    response.writeHead(503);
    response.end("failed");
    return;
  }
  if (target === "/repeat") {
    response.setHeader("X-Repeat", ["a", "b"]);
    response.end("repeat");
    return;
  }
  if (target === "/echo") {
    const chunks = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => {
      response.writeHead(201, { "Content-Type": "application/octet-stream", "X-Method": request.method });
      response.end(Buffer.concat(chunks));
    });
    return;
  }
  response.writeHead(200, { "Content-Type": "application/octet-stream", "X-Test": "shared" });
  response.end(payload);
}

const rawFields = "Access-Control-Allow-Origin: *\r\nAccess-Control-Expose-Headers: *\r\nConnection: close\r\n";
const rawResponses = {
  "/fixed": `HTTP/1.1 200 OK\r\n${rawFields}Content-Length: 3\r\n\r\nabc`,
  "/chunked": `HTTP/1.1 200 OK\r\n${rawFields}Transfer-Encoding: chunked\r\n\r\n2;name=value\r\nab\r\n1\r\nc\r\n0\r\nx-end: yes\r\n\r\n`,
  "/interim": `HTTP/1.1 103 Early Hints\r\n\r\nHTTP/1.1 200 OK\r\n${rawFields}Content-Length: 3\r\n\r\nabc`,
  "/close": `HTTP/1.0 200 OK\r\n${rawFields}\r\nabc`,
  "/ambiguous": `HTTP/1.1 200 OK\r\n${rawFields}Content-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n`,
  "/truncated": `HTTP/1.1 200 OK\r\n${rawFields}Content-Length: 5\r\n\r\nabc`,
  "/badchunk": `HTTP/1.1 200 OK\r\n${rawFields}Transfer-Encoding: chunked\r\n\r\nZ\r\nabc\r\n0\r\n\r\n`,
};
const compressedPayload = zlib.gzipSync(payload);
rawResponses["/gzip-close"] = Buffer.concat([
  Buffer.from(`HTTP/1.0 200 OK\r\n${rawFields}Content-Encoding: gzip\r\n\r\n`), compressedPayload,
]);
rawResponses["/gzip-chunked"] = Buffer.concat([
  Buffer.from(`HTTP/1.1 200 OK\r\n${rawFields}Content-Encoding: gzip\r\nTransfer-Encoding: chunked\r\n\r\n${compressedPayload.length.toString(16)}\r\n`),
  compressedPayload, Buffer.from("\r\n0\r\n\r\n"),
]);
const rawServer = net.createServer((peer) => {
  let received = "";
  let replied = false;
  peer.on("data", (chunk) => {
    if (replied) return;
    received += chunk.toString("latin1");
    if (!received.includes("\r\n\r\n")) return;
    replied = true;
    const target = received.split(" ")[1];
    const data = rawResponses[target] || rawResponses["/fixed"];
    if (["/close", "/gzip-close", "/truncated", "/badchunk", "/ambiguous"].includes(target)) peer.end(data);
    else peer.write(data);
  });
});
const httpServer = http.createServer(handleRequest);
const httpsServer = https.createServer({
  key: fs.readFileSync(path.join(tlsFixtures, "server-key.pem")),
  cert: fs.readFileSync(path.join(tlsFixtures, "server.pem")),
  ALPNProtocols: ["http/1.1"],
}, handleRequest);
const servers = [httpServer, httpsServer, rawServer];
servers.forEach((server, index) => {
  server.on("connection", trackPeer);
  server.on("error", (error) => { console.error(error.message); process.exit(1); });
  server.listen(0, "127.0.0.1", () => {
    ports[index] = server.address().port;
    if (ports.filter(Boolean).length === 3) process.stdout.write(`${ports.join("\n")}\n`);
  });
});
httpsServer.on("tlsClientError", () => {});
process.on("SIGTERM", () => {
  peers.forEach((peer) => peer.destroy());
  servers.forEach((server) => server.close());
});
