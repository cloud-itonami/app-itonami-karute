// KOTOBA-159: the APK-level check, against real aapt2 / dexdump output (test/fixtures/apk-probe).
import assert from "node:assert/strict";
import { test } from "node:test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { apkProblems, applicationAttributes, onCreateCode } from "./android-apk-check.mjs";

const dir = path.join(path.dirname(fileURLToPath(import.meta.url)), "../test/fixtures/apk-probe");
const load = (v) => ({ xmltree: readFileSync(path.join(dir, `${v}-xmltree.txt`), "utf8"), dexdump: readFileSync(path.join(dir, `${v}-dexdump.txt`), "utf8") });
const release = load("release"); const debug = load("debug");

test("parses the real <application> attributes and the onCreate disassembly", () => {
  assert.deepEqual(applicationAttributes(release.xmltree), { allowBackup: "false", dataExtractionRules: "@0x7f010000", label: "probe" });
  assert.equal(applicationAttributes(debug.xmltree).debuggable, "true");
  assert.match(onCreateCode(release.dexdump), /Window;\.setFlags:\(II\)V/);
  assert.doesNotMatch(onCreateCode(release.dexdump), /BuildConfig;\.DEBUG/);
  assert.match(onCreateCode(debug.dexdump), /sget-boolean v0, Lnetwork\/itonami\/karute\/BuildConfig;\.DEBUG:Z/);
});

test("a release APK passes: not debuggable, FLAG_SECURE always set; a debug APK passes as debug", () => {
  assert.deepEqual(apkProblems({ ...release, variant: "release" }), []);
  assert.deepEqual(apkProblems({ ...debug, variant: "debug" }), []);
});

test("negative controls: wrong variant, missing flag, backup on, flags cleared", () => {
  assert.deepEqual(apkProblems({ ...debug, variant: "release" }), ["manifest: a release APK is debuggable", "dex: a release onCreate still branches on BuildConfig.DEBUG"]);
  assert.deepEqual(apkProblems({ ...release, variant: "debug" }), ["manifest: a debug APK is not debuggable (wrong variant?)", "dex: a debug onCreate does not gate FLAG_SECURE on BuildConfig.DEBUG"]);
  const noFlag = { ...release, dexdump: release.dexdump.replace(/[^\n]*Window;\.setFlags:\(II\)V[^\n]*\n/, "") };
  assert.deepEqual(apkProblems({ ...noFlag, variant: "release" }), ["dex: onCreate does not set FLAG_SECURE (8192)"]);
  const backup = { ...release, xmltree: release.xmltree.replace("allowBackup(0x01010280)=false", "allowBackup(0x01010280)=true") };
  assert.deepEqual(apkProblems({ ...backup, variant: "release" }), ["manifest: allowBackup is not false"]);
  const noRules = { ...release, xmltree: release.xmltree.replace(/\n[^\n]*dataExtractionRules[^\n]*/, "") };
  assert.deepEqual(apkProblems({ ...noRules, variant: "release" }), ["manifest: dataExtractionRules missing"]);
  const noRecents = { ...release, dexdump: release.dexdump.replace(/[^\n]*setRecentsScreenshotEnabled:\(Z\)V[^\n]*\n/, "") };
  assert.deepEqual(apkProblems({ ...noRecents, variant: "release" }), ["dex: onCreate does not call setRecentsScreenshotEnabled"]);
  const cleared = { ...release, dexdump: release.dexdump.replace("Landroid/view/Window;.setFlags:(II)V // method@0002", "Landroid/view/Window;.setFlags:(II)V // method@0002\n000206: 6e20 |0007: invoke-virtual {v0, v1}, Landroid/view/Window;.clearFlags:(I)V") };
  assert.deepEqual(apkProblems({ ...cleared, variant: "release" }), ["dex: onCreate clears window flags"]);
  assert.deepEqual(apkProblems({ ...release, dexdump: "", variant: "release" }), ["dex: MainActivity.onCreate not found"]);
});
