// APK-level check of the PHI hardening (KOTOBA-159; Owner bot 2026-11-22). The source check
// (android-hardening.mjs) proves what is written; this proves what was built.
// usage: node scripts/android-apk-check.mjs <app.apk> release|debug
//   release: not debuggable; <application> allowBackup=false and dataExtractionRules; MainActivity.onCreate
//            sets FLAG_SECURE (8192) unconditionally (AGP writes BuildConfig.DEBUG = false for release, so
//            the branch is gone) and calls setRecentsScreenshotEnabled(false).
//   debug:   debuggable; the same manifest; onCreate reads BuildConfig.DEBUG before the FLAG_SECURE set
//            (so QA can take screenshots) and still calls setRecentsScreenshotEnabled(false).
// Tools: aapt2 and dexdump from the newest Android build-tools ($ANDROID_HOME or ~/Library/Android/sdk).
import { execFileSync } from "node:child_process";
import { existsSync, readdirSync } from "node:fs";
import { homedir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ANDROID = "http://schemas.android.com/apk/res/android";
const isTrue = (v) => v === "true" || /^\(type 0x12\)0x[fF]{8}$/.test(v) || v === "(type 0x12)0xffffffff";
const isFalse = (v) => v === "false" || v === "(type 0x12)0x0";

/** Attributes of the <application> element in `aapt2 dump xmltree --file AndroidManifest.xml` output. */
export function applicationAttributes(xmltree) {
  const lines = xmltree.split("\n");
  const start = lines.findIndex((l) => /^\s*E: application\b/.test(l));
  if (start < 0) return null;
  const indent = lines[start].search(/\S/);
  const attrs = {};
  for (const line of lines.slice(start + 1)) {
    const depth = line.search(/\S/);
    if (depth <= indent) break;
    if (/^\s*E: /.test(line)) break; // the first child element ends the attribute list
    const m = new RegExp(`^\\s*A: ${ANDROID.replace(/[.]/g, "\\.")}:([A-Za-z]+)\\(0x[0-9a-f]+\\)=(.*)$`).exec(line);
    // aapt2 prints strings as `"value" (Raw: "value")`, booleans as `true`/`false`, references as `@0x...`.
    if (m) attrs[m[1]] = m[2].trim().replace(/ \(Raw: .*\)$/, "").replace(/^"|"$/g, "");
  }
  return attrs;
}

/** The disassembly of MainActivity.onCreate in `dexdump -d` output, or null. */
export function onCreateCode(dexdump) {
  const header = /\|\[[0-9a-f]+\] network\.itonami\.karute\.MainActivity\.onCreate:\(Landroid\/os\/Bundle;\)V/.exec(dexdump);
  if (!header) return null;
  const rest = dexdump.slice(header.index + header[0].length);
  const next = rest.search(/\|\[[0-9a-f]+\] |\n\s+#\d+\s+: \(in /);
  return next < 0 ? rest : rest.slice(0, next);
}

export function apkProblems({ xmltree, dexdump, variant }) {
  const problems = [];
  if (!["release", "debug"].includes(variant)) return ["variant must be release or debug"];
  const app = applicationAttributes(xmltree);
  if (!app) problems.push("manifest: no <application>");
  else {
    if (!isFalse(app.allowBackup ?? "")) problems.push("manifest: allowBackup is not false");
    if (!/^@0x[0-9a-f]{8}$/.test(app.dataExtractionRules ?? "") && !/^@xml\/data_extraction_rules$/.test(app.dataExtractionRules ?? "")) problems.push("manifest: dataExtractionRules missing");
    const debuggable = isTrue(app.debuggable ?? "");
    if (variant === "release" && debuggable) problems.push("manifest: a release APK is debuggable");
    if (variant === "debug" && !debuggable) problems.push("manifest: a debug APK is not debuggable (wrong variant?)");
  }
  const code = onCreateCode(dexdump);
  if (!code) return [...problems, "dex: MainActivity.onCreate not found"];
  const setFlags = code.search(/Landroid\/view\/Window;\.setFlags:\(II\)V/);
  if (setFlags < 0 || !/#int 8192\b/.test(code)) problems.push("dex: onCreate does not set FLAG_SECURE (8192)");
  if (!/setRecentsScreenshotEnabled:\(Z\)V/.test(code)) problems.push("dex: onCreate does not call setRecentsScreenshotEnabled");
  const debugRead = code.search(/BuildConfig;\.DEBUG:Z/);
  if (variant === "release" && debugRead >= 0) problems.push("dex: a release onCreate still branches on BuildConfig.DEBUG");
  if (variant === "debug" && (debugRead < 0 || (setFlags >= 0 && debugRead > setFlags))) problems.push("dex: a debug onCreate does not gate FLAG_SECURE on BuildConfig.DEBUG");
  if (/Landroid\/view\/Window;\.clearFlags:\(I\)V/.test(code)) problems.push("dex: onCreate clears window flags");
  return problems;
}

export function buildTools() {
  const sdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT ?? path.join(homedir(), "Library/Android/sdk");
  const dir = path.join(sdk, "build-tools");
  if (!existsSync(dir)) throw new Error(`no Android build-tools under ${sdk}`);
  const latest = readdirSync(dir).sort((a, b) => a.localeCompare(b, undefined, { numeric: true })).pop();
  return { aapt2: path.join(dir, latest, "aapt2"), dexdump: path.join(dir, latest, "dexdump") };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [apk, variant] = process.argv.slice(2);
  if (!apk || !variant) { console.error("usage: node scripts/android-apk-check.mjs <app.apk> release|debug"); process.exit(2); }
  const t = buildTools();
  const run = (bin, args) => execFileSync(bin, args, { encoding: "utf8", maxBuffer: 512 * 1024 * 1024 });
  const problems = apkProblems({ xmltree: run(t.aapt2, ["dump", "xmltree", "--file", "AndroidManifest.xml", apk]), dexdump: run(t.dexdump, ["-d", apk]), variant });
  for (const p of problems) console.error(`apk check (${variant}): ${p}`);
  if (problems.length) process.exit(1);
  console.log(`apk check (${variant}): FLAG_SECURE ${variant === "release" ? "always set" : "gated on BuildConfig.DEBUG"}, Recents screenshot off, allowBackup=false, dataExtractionRules`);
}
