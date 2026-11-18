const dgram = require("node:dgram");
const net = require("node:net");

const udp = dgram.createSocket("udp4");
const other = dgram.createSocket("udp4");
const peers = new Set();
const timers = new Set();

function later(ms, action) {
  const timer = setTimeout(() => {
    timers.delete(timer);
    action();
  }, ms);
  timers.add(timer);
}

function u16(number) {
  const buffer = Buffer.alloc(2);
  buffer.writeUInt16BE(number);
  return buffer;
}

function readQuestion(query) {
  let position = 12;
  const labels = [];
  while (query[position]) {
    const size = query[position++];
    labels.push(query.subarray(position, position + size).toString("ascii"));
    position += size;
  }
  position++;
  return { name: labels.join(".").toLowerCase(), type: query.readUInt16BE(position), end: position + 4 };
}

function record(type, data) {
  return Buffer.concat([Buffer.from([192, 12]), u16(type), u16(1), Buffer.from([0, 0, 0, 60]), u16(data.length), data]);
}

function answerData(type) {
  switch (type) {
    case 28: return Buffer.from([32, 1, 13, 184, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]);
    case 2:
    case 5:
    case 12: return Buffer.from([5, 97, 108, 105, 97, 115, 192, 12]);
    case 15: return Buffer.from([0, 10, 4, 109, 97, 105, 108, 192, 12]);
    case 16: return Buffer.from([3, 0, 255, 65, 0]);
    case 33: return Buffer.from([0, 10, 0, 20, 1, 187, 6, 116, 97, 114, 103, 101, 116, 0]);
    default: return type === 1 ? Buffer.from([192, 0, 2, 1]) : Buffer.from([0, 255]);
  }
}

function response(query, truncated = false) {
  const question = readQuestion(query);
  const negative = question.name.startsWith("nxdomain.") ? 3 : question.name.startsWith("servfail.") ? 2 : 0;
  const count = negative || question.name.startsWith("empty.") ? 0 : 1;
  const header = Buffer.alloc(12);
  header.writeUInt16BE(query.readUInt16BE(0));
  header.writeUInt16BE(0x8080 | (query.readUInt16BE(2) & 0x0100) | (truncated ? 0x0200 : 0) | negative, 2);
  header.writeUInt16BE(1, 4);
  header.writeUInt16BE(count, 6);
  const answers = truncated ? Buffer.from([192, 12, 0])
    : count ? record(question.type, answerData(question.type)) : Buffer.alloc(0);
  return Buffer.concat([header, query.subarray(12, question.end), answers]);
}

function poison(query) {
  const data = response(query);
  data.set([203, 0, 113, 99], data.length - 4);
  return data;
}

udp.on("error", (error) => { throw error; });
other.on("error", (error) => { throw error; });
udp.on("message", (query, sender) => {
  const question = readQuestion(query);
  const send = (data) => udp.send(data, sender.port, sender.address);
  if (question.name.startsWith("silent.")) return;
  if (question.name.startsWith("fallback.")) {
    send(response(query, true));
    return;
  }
  if (question.name.startsWith("delayed-fallback.")) {
    later(80, () => send(response(query, true)));
    return;
  }
  if (question.name.startsWith("large-udp.")) {
    send(Buffer.concat([response(query), Buffer.alloc(513)]));
    return;
  }
  if (question.name.startsWith("bad-compression.")) {
    const malformed = response(query);
    malformed[question.end] = 255;
    malformed[question.end + 1] = 255;
    send(malformed);
    return;
  }
  if (question.name.startsWith("ignored.") || question.name.startsWith("mismatch-only.")) {
    other.send(response(query, true), sender.port, sender.address);
    other.send(Buffer.concat([poison(query), Buffer.alloc(1024)]), sender.port, sender.address);
    const wrongId = poison(query);
    wrongId.writeUInt16BE(query.readUInt16BE(0) ^ 1);
    send(wrongId);
    const wrongQuestion = poison(query);
    wrongQuestion[13] = 120;
    send(wrongQuestion);
    const wrongType = response(query, true);
    wrongType.writeUInt16BE(28, question.end - 4);
    send(wrongType);
    const wrongFlags = poison(query);
    wrongFlags[2] &= 0x7f;
    send(wrongFlags);
    if (question.name.startsWith("ignored.")) later(25, () => send(response(query)));
    return;
  }
  send(response(query));
  if (question.name.startsWith("duplicate.")) send(response(query));
});

const tcp = net.createServer((peer) => {
  peers.add(peer);
  peer.setNoDelay(true);
  peer.on("error", () => {});
  peer.on("close", () => peers.delete(peer));
  let buffer = Buffer.alloc(0);
  let replied = false;
  peer.on("data", (chunk) => {
    if (replied) return;
    buffer = Buffer.concat([buffer, chunk]);
    if (buffer.length < 2 || buffer.length < buffer.readUInt16BE(0) + 2) return;
    replied = true;
    const query = buffer.subarray(2, buffer.readUInt16BE(0) + 2);
    const question = readQuestion(query);
    if (question.name.startsWith("silent.")) return;
    if (question.name.startsWith("short-frame.")) { peer.write(Buffer.from([0, 11])); return; }
    if (question.name.startsWith("huge-frame.")) { peer.write(Buffer.from([255, 255])); return; }
    let data = response(query, question.name.startsWith("tcp-truncated."));
    if (question.name.startsWith("ignored.") || question.name.startsWith("mismatch-only.")) data = poison(query);
    if (question.name.startsWith("tcp-mismatch.")) data.writeUInt16BE(query.readUInt16BE(0) ^ 1);
    const frame = Buffer.concat([u16(data.length), data]);
    if (question.name.startsWith("close.")) { peer.end(frame.subarray(0, frame.length - 1)); return; }
    const send = () => {
      if (peer.destroyed) return;
      peer.write(frame.subarray(0, 1));
      later(2, () => { if (!peer.destroyed) peer.write(frame.subarray(1, 5)); });
      later(4, () => { if (!peer.destroyed) peer.write(frame.subarray(5)); });
    };
    if (question.name.startsWith("delayed-fallback.")) later(100, send);
    else send();
  });
});
tcp.on("error", (error) => { throw error; });
tcp.listen(0, "127.0.0.1", () => {
  const port = tcp.address().port;
  udp.bind(port, "127.0.0.1", () => other.bind(0, "127.0.0.1", () => console.log(port)));
});

process.on("SIGTERM", () => {
  timers.forEach((timer) => clearTimeout(timer));
  peers.forEach((peer) => peer.destroy());
  udp.close();
  other.close();
  tcp.close(() => process.exit(0));
});
