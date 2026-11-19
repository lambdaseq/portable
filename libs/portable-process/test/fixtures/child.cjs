// Bound fixture lifetime even when a lifecycle test fails before cleanup.
const watchdog = setTimeout(() => process.exit(93), 15000);
watchdog.unref();
const mode = process.argv[2];
const args = process.argv.slice(3);
if (mode === "echo") {
  process.stderr.end(Buffer.from([255, 0, 206, 187, 1]));
  process.stdin.on("data", (data) => {
    if (!process.stdout.write(data)) process.stdin.pause();
  });
  process.stdout.on("drain", () => process.stdin.resume());
  process.stdin.on("end", () => process.stdout.end());
} else if (mode === "metadata") {
  const values = [String(args.length), ...args, process.cwd(), process.env.PORTABLE_PROCESS_VALUE ?? "",
    String(Object.hasOwn(process.env, "PORTABLE_PROCESS_REMOVED")), process.env.PORTABLE_PROCESS_INHERITED ?? ""];
  process.stdout.end(values.join("\u0000"));
} else if (mode === "volume") {
  process.stdout.end(Buffer.alloc(262157, 255));
  process.stderr.end(Buffer.alloc(262159, 128));
} else if (mode === "exit" || mode === "delayed-exit") {
  const finish = () => {
    process.stdout.end(Buffer.from([0, 255, 1, 2]));
    process.stderr.end("done");
    process.exitCode = Number(args[0] || 0);
  };
  if (mode === "delayed-exit") setTimeout(finish, 150);
  else finish();
} else if (mode === "hold" || mode === "ignore-term") {
  if (mode === "ignore-term") process.on("SIGTERM", () => {});
  process.stderr.write("ready");
  setInterval(() => {}, 1000);
} else {
  process.exitCode = 99;
}
