import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";

const require = createRequire(new URL("../package.json", import.meta.url));
const ts = require("typescript");
const source = readFileSync(new URL("../src/api/harness-answers.ts", import.meta.url), "utf8");
const js = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const storage = new Map();
globalThis.sessionStorage = {
  getItem: key => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: key => storage.delete(key),
};
Object.defineProperty(globalThis, "crypto", { value: webcrypto, configurable: true });
let identity = "user-session";
let calls = [];
let responses = [];
const module = { exports: {} };
new Function("require", "module", "exports", js)(name => {
  if (name === "./session") return { readSession: () => ({ identity }) };
  if (name === "./http") return { request: async config => {
    calls.push(config);
    const response = responses.shift();
    if (response instanceof Error) throw response;
    return response;
  }};
  throw new Error(`Unexpected import ${name}`);
}, module, module.exports);
const { routedRagAnswer } = module.exports;

responses = [new Error("network lost")];
await assert.rejects(routedRagAnswer({ question: "same request" }), /network lost/);
const firstKey = calls[0].headers["Idempotency-Key"];
responses = [{ answer: "done" }];
assert.equal((await routedRagAnswer({ question: "same request" })).answer, "done");
assert.equal(calls[1].headers["Idempotency-Key"], firstKey);
assert.equal(storage.size, 0);

const routeId = "11111111-1111-4111-8111-111111111111";
responses = [{ owner: "HARNESS", routeId, status: "NEEDS_ATTENTION",
  statusUrl: `/api/rag/harness/answers/${routeId}` }];
await assert.rejects(routedRagAnswer({ question: "unknown result" }), /NEEDS_ATTENTION/);
responses = [{ answer: "reconciled" }];
await routedRagAnswer({ question: "unknown result" });
assert.equal(calls.at(-1).url, `/api/rag/harness/answers/${routeId}`);
assert.equal(calls.at(-1).method, undefined);
assert.equal(storage.size, 0);

responses = [{ owner: "HARNESS", routeId, status: "RUNNING", statusUrl: "https://attacker.invalid" }];
await assert.rejects(routedRagAnswer({ question: "bad URL" }), /无效/);
const oldKey = calls.at(-1).headers["Idempotency-Key"];
identity = "other-user";
responses = [{ answer: "other user result" }];
await routedRagAnswer({ question: "bad URL" });
assert.notEqual(calls.at(-1).headers["Idempotency-Key"], oldKey);
assert.equal(calls.at(-1).expectedIdentity, "other-user");
console.log("PASS Harness answers: retry key, UNKNOWN read-only resume, URL boundary and identity isolation");
