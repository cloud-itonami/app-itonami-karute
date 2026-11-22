// Keep PHI out of Recents, screenshots and backups (KOTOBA-159; Legal's host-local rules, Dev's Murakumo review).
// gen/android is committed in this repository, and `tauri android init` would regenerate it without these
// changes. `check` fails the Android build and `npm test` if any is missing; `apply` writes them back.
//   - AndroidManifest.xml <application>: android:allowBackup="false" and
//     android:dataExtractionRules="@xml/data_extraction_rules" (Android 12+ honours only the rules file,
//     for cloud backup and device-to-device transfer; minSdk is 35).
//   - res/xml/data_extraction_rules.xml: every domain excluded from cloud-backup and device-transfer.
//   - MainActivity.kt: FLAG_SECURE before the content view (no screenshots, a blank Recents thumbnail)
//     and setRecentsScreenshotEnabled(false).
// usage: node scripts/android-hardening.mjs check|apply [gen/android dir]
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const DOMAINS = ["root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref"];
const excludes = DOMAINS.map((d) => `        <exclude domain="${d}" path="." />`).join("\n");
export const RULES_XML = `<?xml version="1.0" encoding="utf-8"?>
<!-- Karute keeps PHI in page memory only. Nothing of this app is backed up or transferred (KOTOBA-159). -->
<data-extraction-rules>
    <cloud-backup>
${excludes}
    </cloud-backup>
    <device-transfer>
${excludes}
    </device-transfer>
</data-extraction-rules>
`;
const SECURE_LINES = [
  "    // PHI stays out of screenshots, screen recording and the Recents thumbnail (KOTOBA-159).",
  "    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)",
  "    setRecentsScreenshotEnabled(false)",
];

export const files = (gen) => ({
  manifest: path.join(gen, "app/src/main/AndroidManifest.xml"),
  rules: path.join(gen, "app/src/main/res/xml/data_extraction_rules.xml"),
  activity: path.join(gen, "app/src/main/java/network/itonami/karute/MainActivity.kt"),
});

const applicationTag = (xml) => /<application\b[^>]*>/.exec(xml)?.[0] ?? null;

/** Problems with the three files' text (null for a missing file). Empty when all hardening is present. */
export function hardeningProblems({ manifest, rules, activity }) {
  const problems = [];
  const app = manifest == null ? null : applicationTag(manifest);
  if (!app) problems.push("manifest: no <application>");
  else {
    if (!/\sandroid:allowBackup="false"/.test(app)) problems.push('manifest: android:allowBackup="false" missing');
    if (!/\sandroid:dataExtractionRules="@xml\/data_extraction_rules"/.test(app)) problems.push("manifest: android:dataExtractionRules missing");
    if (/\sandroid:fullBackupContent="(?!false")/.test(app)) problems.push("manifest: fullBackupContent includes data");
  }
  if (rules == null) problems.push("data_extraction_rules.xml missing");
  else for (const section of ["cloud-backup", "device-transfer"]) {
    const body = new RegExp(`<${section}\\b[^>]*>([\\s\\S]*?)</${section}>`).exec(rules)?.[1];
    if (body == null) { problems.push(`rules: no <${section}>`); continue; }
    if (/<include\b/.test(body)) problems.push(`rules: <${section}> includes data`);
    for (const d of DOMAINS) if (!new RegExp(`<exclude domain="${d}" path="\\." */>`).test(body)) problems.push(`rules: <${section}> does not exclude ${d}`);
  }
  if (activity == null) problems.push("MainActivity.kt missing");
  else {
    const onCreate = /override fun onCreate\([^)]*\)\s*\{([\s\S]*?)\n  \}/.exec(activity)?.[1];
    if (!onCreate) problems.push("MainActivity: no onCreate");
    else {
      const secure = onCreate.indexOf("WindowManager.LayoutParams.FLAG_SECURE");
      const superCall = onCreate.indexOf("super.onCreate(");
      if (secure < 0) problems.push("MainActivity: FLAG_SECURE missing");
      else if (superCall >= 0 && secure > superCall) problems.push("MainActivity: FLAG_SECURE must be set before super.onCreate");
      if (!/setRecentsScreenshotEnabled\(false\)/.test(onCreate)) problems.push("MainActivity: setRecentsScreenshotEnabled(false) missing");
    }
    if (!/^import android\.view\.WindowManager$/m.test(activity)) problems.push("MainActivity: import android.view.WindowManager missing");
  }
  return problems;
}

/** The three files with the hardening applied (idempotent). */
export function applyHardening({ manifest, activity }) {
  let app = applicationTag(manifest);
  if (!app) throw new Error("AndroidManifest.xml has no <application>");
  let fixed = app.replace(/\s+android:(allowBackup|dataExtractionRules|fullBackupContent)="[^"]*"/g, "");
  fixed = fixed.replace(/^<application\b/, '<application\n        android:allowBackup="false"\n        android:dataExtractionRules="@xml/data_extraction_rules"');
  const nextManifest = manifest.replace(app, fixed);
  let nextActivity = activity;
  if (!/^import android\.view\.WindowManager$/m.test(nextActivity)) nextActivity = nextActivity.replace(/^import android\.view\.View$/m, "import android.view.View\nimport android.view.WindowManager");
  if (!/^import android\.view\.WindowManager$/m.test(nextActivity)) nextActivity = nextActivity.replace(/^(package [^\n]+\n)/, "$1\nimport android.view.WindowManager\n");
  if (!nextActivity.includes("WindowManager.LayoutParams.FLAG_SECURE")) {
    nextActivity = nextActivity.replace(/(override fun onCreate\([^)]*\)\s*\{\n)/, `$1${SECURE_LINES.join("\n")}\n`);
  }
  return { manifest: nextManifest, rules: RULES_XML, activity: nextActivity };
}

async function read(file) { try { return await readFile(file, "utf8"); } catch { return null; } }

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [mode, dir] = process.argv.slice(2);
  const gen = path.resolve(dir ?? path.join(path.dirname(fileURLToPath(import.meta.url)), "../src-tauri/gen/android"));
  const f = files(gen);
  const text = { manifest: await read(f.manifest), rules: await read(f.rules), activity: await read(f.activity) };
  if (mode === "apply") {
    const out = applyHardening(text);
    await mkdir(path.dirname(f.rules), { recursive: true });
    await writeFile(f.manifest, out.manifest); await writeFile(f.rules, out.rules); await writeFile(f.activity, out.activity);
  } else if (mode !== "check") { console.error("usage: node scripts/android-hardening.mjs check|apply [gen/android]"); process.exit(2); }
  const problems = hardeningProblems({ manifest: await read(f.manifest), rules: await read(f.rules), activity: await read(f.activity) });
  for (const p of problems) console.error(`android hardening: ${p}`);
  if (problems.length) { console.error("run: node scripts/android-hardening.mjs apply"); process.exit(1); }
  console.log("android hardening: FLAG_SECURE, Recents screenshot off, allowBackup=false, data extraction rules exclude everything");
}
