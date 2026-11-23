import { execFileSync, spawn, type ChildProcess } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const SERVER = join(here, "..", "..", "..", "..", "server");

export interface Dev { url: string; keys: string[]; stop(): void }

export function haveGo(): boolean {
  if (process.env["NM_DEVSERVER_BIN"]) return true;
  try { execFileSync("go", ["version"], { stdio: "ignore" }); return true; } catch { return false; }
}

/** Build (unless NM_DEVSERVER_BIN is set) and start the real Go MCP dev server. */
export async function startDevServer(): Promise<Dev> {
  let bin = process.env["NM_DEVSERVER_BIN"];
  if (!bin) {
    bin = join(mkdtempSync(join(process.env["GOTMPDIR"] ?? tmpdir(), "nm-dev-")), "mcp-devserver");
    execFileSync("nice", ["-n", "19", "go", "build", "-o", bin, "./cmd/mcp-devserver"], { cwd: SERVER, stdio: "inherit" });
  }
  const proc: ChildProcess = spawn(bin, [], { env: { ...process.env, NM_DEV: "1" }, stdio: ["ignore", "pipe", "inherit"] });
  const line = await new Promise<string>((resolve, reject) => {
    let buf = "";
    proc.stdout!.on("data", (d: Buffer) => {
      buf += d.toString();
      if (buf.includes("\n")) resolve(buf.split("\n")[0]!);
    });
    proc.on("exit", () => reject(new Error("devserver exited early")));
  });
  const m = /url=(\S+) keys=(\S+)/.exec(line);
  if (!m) throw new Error(`unexpected devserver output: ${line}`);
  return { url: m[1]!, keys: m[2]!.split(","), stop: () => { proc.kill("SIGTERM"); } };
}
