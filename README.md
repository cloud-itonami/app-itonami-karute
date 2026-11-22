# Karute

Karute is the Awai app (Tauri v3, Android first) for Itonami's medical-family business bots. It bundles one bot profile per bot: `cloud-itonami-isic-8620` (clinical practice, 電子カルテ, `clinical-practice-governor`) and `iryo` (レセプト, 診療報酬請求). The profiles carry the Itonami mission 「全人類を労働から解放する」.

On the device the app does the first step of each bot's work, with nothing sent and nothing stored:

- **電子カルテ**: checks a SOAP note's structure and vital signs, and lists the problems written in A.
- **レセプト**: drafts the claim arithmetic (点数, 薬剤料, 一部負担金, 高額療養費) and lists integrity observations.

It can also read each bot's public status, once per approval.

Tracking: [AWAI-3381](https://linear.app/kotobalabs/issue/AWAI-3381) (this unit), parent [AWAI-3380](https://linear.app/kotobalabs/issue/AWAI-3380). The pattern is the labor-family app Rodo (`network-awai/app-itonami-rodo`).

## Bot profiles (proposed, not yet in the registry)

- `profiles/business-bot-profile-8620.json` is authored from `cloud-itonami/app-itonami-cloud-itonami-isic-8620` `blueprint.edn` at `017b02d`.
- `profiles/business-bot-profile-iryo.json` is authored from `cloud-itonami/iryo` `manifest.edn` at `18cf0f8`.
- Both follow the `profile_schema` of the canonical registry `com-junkawasaki/root` `90-docs/business/itonami-business-bot-profiles.json`, but **neither is in it**. On 2026-11-18 that file (`7d484e1`, 29 profiles) had no medical entry, and the live registry `https://itonami.cloud/api/open-business` returned HTTP 500. Each profile records `provenance.registry_state: "not-registered"`, and the app shows that.
- Registering them is a separate step under AWAI-3380.
- Each profile adds a `phi` rule: PHI never enters the bot plane, the public feed or the app origin.
- A profile that does not verify (schema, 40-hex revision, mission, `health` family, allowed services, fail-closed health) stops the view.

## 電子カルテ: SOAP note check (on device)

`src/karute/soap.cljc`:

- **Sections.** It finds the S/O/A/P sections, written as `S:`, `(O)`, `【A】` or `P）`. Full-width letters and digits are folded first.
- **Vital signs.** It reads 体温, 脈拍, 血圧, SpO2 and 呼吸数, and marks each value as one of:
  - within a general adult reference range;
  - outside it;
  - implausible, for example 「BT365」, a likely typo.
- **Problems.** It lists the problem names in A (`#1 …`, one per line, or 、-separated). 疑い marks a suspected one.
- **Carry to レセプト.** 「病名をレセプトへ渡す」 opens a consent envelope (`karute.handoff`) that names the exact problems, then writes them into the claim input in memory only after the person confirms there. This is the on-device form of the karute → iryo hand-off. The envelope is fresh for 5 minutes and refuses a changed A section; it is unsigned, and member-signed (Ed25519) verification is a later unit.

It does not diagnose and does not rate care; the physician decides.

## SOAP summary draft through Murakumo (on device, AWAI-3396)

After a SOAP check, Karute can ask Murakumo on the same device (`network.awai.murakumo`) for a 3–5 line summary draft. It uses the pinned `packages/awai-inference` client and Android plugin (`vendor/awai-inference`, awai-os `f52a243`, unchanged, the same pin as Rodo). Murakumo admits Karute by package allowlist plus signature match (awai-os AWAI-3395).

The note is PHI, so it takes two confirmations:

1. Karute first says that the note goes once to Murakumo on this device, and asks the person to confirm or cancel. Cancel gives nothing to Murakumo.
2. Murakumo then opens its own native confirmation with the calling app and a prompt excerpt. Deny runs no inference.

The rest of the flow:

- The prompt holds the note (up to 6000 bytes, under Murakumo's 8192-byte limit), the missing sections and the out-of-range values. The model is told not to add findings, diagnoses, drugs or numbers.
- The draft button is enabled only when Murakumo reports `installed`, `remote: false`, and the model both imported and loaded.
- Outside Android the client refuses, and there is no remote fallback.
- The draft is shown only, never stored or sent, and it is labelled as a draft from a small model that can be wrong. It is not a clinical record or a diagnosis.

## Claim explanation draft through Murakumo (on device, AWAI-3389)

After the claim is calculated, Karute can ask Murakumo on the same device for a 3–5 line explanation draft of that 算定案. It uses the same pinned `packages/awai-inference` client and the same two confirmations (Karute's own, then Murakumo's native one), with no remote fallback. This is a second inference operation with its own consent screen and its own result display: confirming the SOAP summary never confirms the claim explanation, and denying either one runs no inference.

The prompt holds the computed aggregates (区分 totals, total points and yen, patient share and cap, observations) and the problem names from the claim input — not the raw claim lines. The model is told not to add amounts or names and not to judge the claim. The button is enabled only after a calculated draft exists and Murakumo reports ready. Like the summary, the explanation is shown only, never stored or sent, and it is not adjudication or a submission.

## レセプト: claim draft (on device)

`src/karute/rezept.cljc` ports the arithmetic of `cloud-itonami/iryo` at `18cf0f809a0df5b4ae9b444c21bdf89214f16f35` unchanged, and the tests reproduce iryo's own expected values:

- 診療識別 → 区分 totals;
- 薬剤料: 15 円以下は 1 点, otherwise 五捨五超入;
- 1 点 = 10 円;
- 一部負担金 rounded to 10 円;
- 負担割合 from age;
- 高額療養費 自己負担限度額 for 70 歳未満 ア〜オ and 70 歳以上 現役並み/一般/低所得, with the 外来 cap.

Input is one line per item:

```
行為 <診療識別> <名称> <点数> [回数]
薬 <診療識別 2x> <名称> <1 日分の薬価 円> <日数>
材 <名称> <薬価 円> [数量]
病名 <名称> [主|疑い]
```

Lines with 診療識別 90 (入院) are refused, and 点数, 回数, 薬価 and 日数 have upper bounds.

Observations: 空レセプト, 病名なし, 主病名なし, more than one 主病名, 病名なし投薬, unknown 高額療養費 区分. They are observations, not adjudication; the clinic is the billing principal and the 審査支払機関 decides.

**Limits:**
- 点数 and 薬価 are taken as typed; there is no 厚労省 master in the app.
- Outpatient only (no 入院, 食事 or 公費).
- No レセ電 output and no submission.

## Patient data

患者の情報を守るため、この app は画面の撮影と共有ができません。

- Android release builds set `FLAG_SECURE`, so there are no screenshots, screen recording, casting or Recents thumbnail. They also turn off the Recents screenshot, set `allowBackup="false"`, and add data-extraction rules that exclude every domain from cloud backup and device transfer.
- Debug builds skip only `FLAG_SECURE`, so App QA can take evidence screenshots. `scripts/android-hardening.mjs check` enforces the exact `if (!BuildConfig.DEBUG)` gate. `node scripts/android-apk-check.mjs <apk> release` checks a built release APK: it is not debuggable, it sets the flag unconditionally, and the manifest is as above.
- This is the documented exception to the KOTOBA-159 no-loss rule. Owner bot, 2026-11-22.
- The SOAP note and the claim lines are PHI. They live only in the page's memory.
- They are never stored and never sent off the device.
- The ways page data leaves the page are the two Murakumo drafts above (SOAP summary, claim explanation). Each goes through the Tauri command `awai_local_inference` to Murakumo on the same device, only after its own two confirmations. The host keeps nothing; its other command is `awai_host_info`.
- The only network operations are the two public bot-status reads below, whose data categories are `request-ip` and `bot-id`.

## Bot status (public, per approval)

Same as Rodo:
- `GET https://bots.itonami.cloud/v1/grok-bots/businesses`, then `GET /v1/grok-bots/bots/{bot_id}/activity` only for a bot id read from that list.
- Each read goes through the canonical `kotoba-client-runtime.js` broker and opens its own approval dialog. Denying sends nothing.
- Health is fail-closed: `running` alone is never healthy.
- On 2026-11-18 neither medical bot was launched. Launching one creates a trial-LP credit position and needs separate operator approval.

## Build and test

Node 26/npm, pinned Squint 0.16.214, esbuild 0.28.2 and Tauri CLI 3.0.0-alpha.6.

```sh
npm ci
npm test          # domain, SOAP and claim tests, with negative controls
npm run build     # static files in dist/, with dist/release.json digests
```

The build fails if the canonical runtime or consent CSS differs from `vendor/PINS.json` (kotoba-lang/kotoba `91ed14c`), or if the Awai UI tarball or its files differ from `vendor/awai-ui-pin.json`.

Android needs SDK 35+, NDK 27.0.12077973 and JDK 21, with `ANDROID_HOME`, `NDK_HOME` and `JAVA_HOME` set:

```sh
npm run build:android        # arm64 debug APK
npm run build:android:x86    # x86_64 debug APK
```

The APK is a debug test artifact. It is not a signed release, not an OTA and not enrolled in Itonami Store. Verification is in [docs/verification.md](docs/verification.md).

## Boundaries

- The app origin is static only: no SSR, BFF, app-origin API or inference proxy.
- The CSP `connect-src` is exactly `https://bots.itonami.cloud`, the value the runtime derives from the descriptor (checked in the tests).
- Inference is on-device only, through Murakumo. The hand-off envelope verifies freshness and an unchanged A section in memory. Member-signed (Ed25519) verification is implemented in `karute.member-keys` against `karute.handoff/canonical` and covered with real signatures in the tests, but it is not yet called from the UI and no member key set ships here: the runtime path stays unsigned, and activating verification needs the keys and spec of `cloud-itonami/karute` MATURITY #8/#13.
- The bot-state reads live in the shared package `packages/itonami-bot-status` (`itonami.bot-status`, AWAI-3389); `karute.domain` keeps Karute's blueprints, consent operations, descriptor and profile checks and delegates the reads. Moving Rodo onto it and publishing it are owner decisions under AWAI-3380.
- Registry registration is a later unit under AWAI-3380.

## Legacy appview (etzhayyim, not part of the Karute app)

`appview/etzhayyim-wasm-karute-karu7t3e/svelte` is the earlier Svelte UI carried over from `etzhayyim/root`. It calls a remote XRPC API, which the Kotoba end-user app contract does not allow. It is kept unchanged as migration inventory and is not built or shipped by Karute. What follows is the earlier description.

**この repo が持っているのは UI だけである。** 電子カルテ（EMR）の
ドメインモデル・lexicon・サーバ側実装は持っていない。ここに在るのは
`karute.etzhayyim.com` に配信される Svelte 5 の single-page bundle
一式（`appview/etzhayyim-wasm-karute-karu7t3e/svelte/`）と、
移行メタデータ 5 ファイルだけ。

| | |
|---|---|
| actor DID | `did:web:karute.etzhayyim.com`（nanoid `karu7t3e`） |
| UI | https://karute.etzhayyim.com |
| API | https://karu7t3e.etzhayyim.com/xrpc |
| tier | T1（`PROJECT.jsonld`） |
| 出自 | `etzhayyim/root` の `60-apps/etzhayyim-project-karute`（`migration.edn` の `:source-revision be0c17bb`） |

### 最近接 repo との境界

**`orgs/cloud-itonami/karute` が backend であり、契約の正本でもある。**
FHIR R5 互換のドメイン（Patient / Encounter / SOAP / Observation /
Condition / MedicationRequest / ServiceRequest）と 11 本の lexicon
（`lex/*.edn`）はそちらに在る。アーキテクチャの正本は
`orgs/cloud-itonami/karute/AGENTS.md`。

⚠ **この repo の `AGENTS.md` はその正本を `orgs/etzhayyim/com-etzhayyim-karute/AGENTS.md`
という古いパスで指している。そのパスは west.yml に無く、checkout も存在しない**
（実測 2026-08-18）。repo は `cloud-itonami/karute` に改名済みで、GitHub
リダイレクトは効くがローカルのパス参照は解決しない。

### 何を呼んでいるか

`src/lib/api/karute-client.ts` が XRPC で叩く手続きは 16 本:

```
createPatient  createSoapNote  createObservation  createMedicationRequest
createServiceRequest  createDispense  grantConsent  requestIryoBilling
listPatients  listEncounters  listSoapNotes  listObservations
listMedications  listOrders  listDispenses  getChartSummary
```

暗号化と同意は `com.etzhayyim.encrypted.record` / `.keyWrap` /
`com.etzhayyim.consent.capability` を経由する。

### 画面

hash ベースの自前ルータ（`src/lib/router.svelte.ts`、SvelteKit は使わない）。
mobile-first、`SuperAppTabBar` 固定下部、sidebar 無し。

| hash | view |
|---|---|
| `#/` | Home |
| `#/patients` | 患者一覧 |
| `#/patients/:did` | 患者詳細 |
| `#/patients/:did/{soap,rx,vitals,order}` | SOAP / 処方 / バイタル / オーダ |
| `#/orders` `#/pharmacy` `#/portal` `#/zaitaku` `#/talk` | オーダ / 薬局 / 患者ポータル / 在宅 / Talk |

### PHI の不変条件（CRITICAL）

**plaintext の PHI を XRPC の body に直接載せてはいけない。**
`@etzhayyim/sdk` の `encryptedWrite()` を通す。実装上これは
「`fetch(` が `src/lib/api/karute-client.ts` の外に出てこない」という
形で守られている。install も build も要らずに確かめられる:

```bash
cd appview/etzhayyim-wasm-karute-karu7t3e/svelte
grep -rn "fetch(" src/ | awk -F: '{print $1}' | sort | uniq -c
#   2 src/lib/api/karute-client.ts   ← この 1 ファイル以外が出たら違反
```

実測 2026-08-18: 2 件、いずれも `karute-client.ts`。**不変条件は成立している。**

### この repo は今日 standalone では build できない

抽出時に monorepo への結合が 3 本残っており、`AGENTS.md` に書かれた
`pnpm install` → `pnpm build` は**この repo 単体では通らない**。
再現手順と実測した失敗は `docs/operator-quickstart.md` に書いてある。
要約すると:

| # | 結合 | 症状（実測 2026-08-18） |
|---|---|---|
| 1 | `package.json` の `"@etzhayyim/design-system": "workspace:*"` | `ERR_PNPM_WORKSPACE_PKG_NOT_FOUND` で install が止まる。実体は `orgs/kotoba-lang/svelte-design-system` に在るが、`dist/` は commit されておらず `svelte-package` での build が要る |
| 2 | `tailwind.config.js` の content glob `../../../../../packages/ts/design-system/dist/**` | repo の外へ出る。`etzhayyim/root` 側にも `packages/ts/design-system` はもう無い |
| 3 | `tests/*.test.ts` の `ROOT = resolve(__dirname, '../../../../../..')` | repo の外へ出る（clone 先が `/tmp/x` なら `/private`）。`70-tools/scripts/lint/karute-phi-plaintext-guard.mjs` と `00-contracts/lexicons/...` を要求するが、どちらもこの repo に無い |

**これは「壊れている」のではなく「まだ切り離されていない」。** 3 本とも
`etzhayyim/root` の中では解決していた参照で、抽出がパスだけを運んだ結果
repo 境界を跨いだまま残っている。

直すなら順序は 1 → 3 → 2（install が通らないと test も build も走らせて
確かめられない）。1 は design-system を publish するか `dist` を持つ git 参照に
変えるか vendor する、3 は fixture をこの repo に持ち込むか `ROOT` を
repo root に付け替える。**まだどれも着手していない。**

### ライセンス

Apache-2.0 + etzhayyim Charter Compliance Rider v3.1（`NOTICE`）。
