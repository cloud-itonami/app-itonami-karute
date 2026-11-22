// KOTOBA-159: PHI stays out of Recents, screenshots and backups. The committed gen/android passes;
// the files as they were before (test/fixtures/android-before, a negative control) fail and are fixed by apply.
import assert from "node:assert/strict";
import { test } from "node:test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { applyHardening, files, hardeningProblems, RULES_XML } from "./android-hardening.mjs";

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const f = files(path.join(root, "src-tauri/gen/android"));
const committed = { manifest: readFileSync(f.manifest, "utf8"), rules: readFileSync(f.rules, "utf8"), activity: readFileSync(f.activity, "utf8") };
const before = { manifest: readFileSync(path.join(root, "test/fixtures/android-before/AndroidManifest.xml"), "utf8"), rules: null,
  activity: readFileSync(path.join(root, "test/fixtures/android-before/MainActivity.kt"), "utf8") };

test("the committed gen/android has every hardening", () => {
  assert.deepEqual(hardeningProblems(committed), []);
  assert.equal(committed.rules, RULES_XML);
});

test("negative control: the files before this change fail on every point", () => {
  assert.deepEqual(hardeningProblems(before), [
    'manifest: android:allowBackup="false" missing', "manifest: android:dataExtractionRules missing", "data_extraction_rules.xml missing",
    "MainActivity: FLAG_SECURE missing", "MainActivity: setRecentsScreenshotEnabled(false) missing or conditional", "MainActivity: import android.view.WindowManager missing"]);
});

test("apply fixes the old files, is idempotent, and keeps the rest of the manifest", () => {
  const once = applyHardening(before);
  assert.deepEqual(hardeningProblems(once), []);
  assert.deepEqual(applyHardening(once), once);
  assert.deepEqual(once, committed, "apply reproduces the committed files");
  for (const keep of ["network.awai.permission.LOCAL_INFERENCE", '<package android:name="network.awai.murakumo"/>', 'android:usesCleartextTraffic="${usesCleartextTraffic}"']) {
    assert.ok(once.manifest.includes(keep), keep);
  }
});

test("partial or wrong hardening is refused", () => {
  const late = { ...committed, activity: committed.activity.replace(/\n    if \(!BuildConfig\.DEBUG\) \{\n      window\.setFlags[^\n]*\n    \}/, "").replace("    super.onCreate(savedInstanceState)\n", "    super.onCreate(savedInstanceState)\n    if (!BuildConfig.DEBUG) {\n      window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)\n    }\n") };
  assert.ok(hardeningProblems(late).includes("MainActivity: FLAG_SECURE must be set before super.onCreate"));
  const backup = { ...committed, manifest: committed.manifest.replace('android:allowBackup="false"', 'android:allowBackup="true"') };
  assert.ok(hardeningProblems(backup).includes('manifest: android:allowBackup="false" missing'));
  const include = { ...committed, rules: committed.rules.replace('<exclude domain="database" path="." />', '<include domain="database" path="." />') };
  assert.ok(hardeningProblems(include).some((p) => p.includes("includes data")));
  const partial = { ...committed, rules: committed.rules.replace(/\n {8}<exclude domain="device_sharedpref" path="\." \/>/g, "") };
  assert.ok(hardeningProblems(partial).includes("rules: <cloud-backup> does not exclude device_sharedpref"));
});

test("FLAG_SECURE is gated by exactly `if (!BuildConfig.DEBUG)`; release always sets it (Owner bot, 2026-11-22)", () => {
  const gate = "MainActivity: FLAG_SECURE must be set inside exactly `if (!BuildConfig.DEBUG) { ... }`";
  const set = "window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)";
  assert.match(committed.activity, /\n    if \(!BuildConfig\.DEBUG\) \{\n      window\.setFlags\(WindowManager\.LayoutParams\.FLAG_SECURE, WindowManager\.LayoutParams\.FLAG_SECURE\)\n    \}\n    setRecentsScreenshotEnabled\(false\)\n/);
  const swap = (from, to) => ({ ...committed, activity: committed.activity.replace(from, to) });
  for (const [label, variant] of [
    ["ungated (the #8 form)", swap(/    if \(!BuildConfig\.DEBUG\) \{\n      (window[^\n]*)\n    \}/, "    $1")],
    ["inverted gate", swap("if (!BuildConfig.DEBUG) {", "if (BuildConfig.DEBUG) {")],
    ["extra condition", swap("if (!BuildConfig.DEBUG) {", "if (!BuildConfig.DEBUG && Build.VERSION.SDK_INT > 34) {")],
    ["another flag", swap("if (!BuildConfig.DEBUG) {", "if (!qaMode) {")],
  ]) assert.ok(hardeningProblems(variant).includes(gate), label);
  assert.ok(hardeningProblems(swap("    setRecentsScreenshotEnabled(false)\n", "    if (!BuildConfig.DEBUG) setRecentsScreenshotEnabled(false)\n")).includes("MainActivity: setRecentsScreenshotEnabled(false) missing or conditional"));
  assert.ok(hardeningProblems(swap("    enableEdgeToEdge()\n", "    enableEdgeToEdge()\n    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)\n")).includes("MainActivity: something turns the protection off again"));
  assert.ok(hardeningProblems(swap("    setRecentsScreenshotEnabled(false)\n", `    setRecentsScreenshotEnabled(false)\n    ${set}\n`)).includes("MainActivity: FLAG_SECURE must be set exactly once"));
});

test("the app and the README tell users why the screen cannot be captured (Owner bot, 2026-11-22)", () => {
  const line = "患者の情報を守るため、この app は画面の撮影と共有ができません";
  const app = readFileSync(path.join(root, "src/karute/app.cljs"), "utf8");
  assert.ok(app.includes(`:screen-note "${line}"`), "ja copy");
  assert.ok(app.includes(':screen-note "To protect patient information, this app cannot be screenshotted or shared on screen."'), "en copy");
  assert.ok(app.includes('[:p {:id "screen-note" :class "awai-caption awai-muted"} (t :screen-note)]'), "rendered in <main>, outside the profile check");
  assert.ok(readFileSync(path.join(root, "README.md"), "utf8").includes(`${line}。`), "README");
});
