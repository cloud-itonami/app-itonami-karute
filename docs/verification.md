# Verification (AWAI-3381, 2026-11-18)

Source, build, browser and device evidence are recorded separately below. None of it covers distribution (Itonami Store), production signing, a physical device, registry registration or live bot operation.

## Source

`npm test`: 27/27 pass (22 at the device run, plus 5 added for the review fixes below).

**Profiles**
- Both bundled medical profiles verify.
- Each says `not-registered` and carries a PHI rule.
- Negative controls are refused: a changed mission, a non-`health` family, an unknown registry state, a foreign allowed service, or a profile checked against the wrong blueprint.

**Descriptor and routes**
- The descriptor passes the canonical runtime.
- The derived `connect-src` is exactly `https://bots.itonami.cloud`, the same value as in `tauri.conf.json` and `public/index.html`.
- These do not match a declared route: a query string, POST, or the list path on the activity route.
- The data categories of both operations are only `request-ip` and `bot-id`.

**Bot status**
- In the live list captured on 2026-11-18, neither medical bot is launched.
- The real `held` 7310 bot, relabelled as 8620, is `unhealthy`.

**SOAP check**
- A complete note: all four sections, 体温 37.8 outside the reference range, the other vitals within range, and two problems in A.
- Full-width headers and digits (`【S】`, `（O）`, `ＢＰ１８８／１０２`) are read.
- 「BT365」 is marked implausible.
- `A` missing and `P` without problems are each reported.
- Inputs are bounded.

**レセプト**
The tests reproduce iryo's own expected values at `18cf0f8`:

| Check | Expected |
| -- | -- |
| yakka-to-ten for 15, 5.9, 10.1, 21, 25, 26, 56.4, 193 | 1 1 1 2 2 3 6 19 |
| 一部負担金 rounding | 870, 880, 1080, 1080, 1090 |
| Outpatient basic | 361 点 / 3610 円 / 1080 円 |
| カロナール | 2 点 × 5 日 |
| Material | 56 点 |
| 高額療養費 オ | 37800 → 35400 円 |
| u70/o70 limits | as in iryo |
| 負担割合 by age | as in iryo |

Observations covered:
- 空レセプト, 病名なし, 主病名なし, 病名なし投薬, unknown 区分.
- Malformed lines are refused with their line number.

## Build

- `npm run build`: the canonical runtime and consent CSS match `vendor/PINS.json` (kotoba-lang/kotoba `91ed14c`), and the Awai UI tarball and its files match `vendor/awai-ui-pin.json`.
- `npm run build:android`, run under `forest-storage run` (exit 0): universal debug APK `network.itonami.karute` 0.1.0 (versionCode 1000, minSdk 35, targetSdk 37), 29,512,166 bytes, sha256 `1b14b60cc3b28f29334e266125800b30c43ba77a42a201103433acf7bedcbfb4`.
- The APK is signed with the local debug identity. It is not a release.

## Browser (375 px, local static server)

**On-device work**
- SOAP check: the expected sections, vitals and problems are shown.
- 「病名をレセプトへ渡す」 adds `病名 急性上気道炎 主` and `病名 高血圧症` to the claim input.
- With three acts and カロナール added, age 45: 371 点, 3,710 円, 一部負担金 1,110 円, and no observations.

**Status read**
- Deny: the page says 「取得を中止しました。何も送信していません。」 and the resource timing list holds no external request.
- A script-dispatched click on 「Allow once」 is not taken as approval, and nothing is sent.
- A real click on 「Allow once」 sends exactly one request, `GET https://bots.itonami.cloud/v1/grok-bots/businesses`. The page then shows 「この bot はまだ起動していません。」 with 5 other entries.

## Device: disposable AOSP emulator

The emulator ran `sdk_phone64_arm64` Android 15 (`AE3A.240806.019`, userdebug, test-keys, no Google services). It used a fresh AVD, `Awai_Karute_AWAI3381`, on port 5590, started with `-wipe-data`. Other sessions' emulators and the attached phone were not touched.

| Step | Result | Evidence |
| -- | -- | -- |
| Install and launch | ok, TotalTime 1424 ms | `android-3381-01-launch.png` |
| SOAP check (real tap) | Same result as in the browser. 体温 37.8 ℃ shown outside the reference range. | `android-3381-02-soap.png` |
| Carry to レセプト, calculate (real taps) | 371 点, 3,710 円, 窓口負担 1,110 円, 区分 初診 291 / 投薬 10 / 検査 70 | `android-3381-03-rezept.png` |
| Read bot status | The canonical consent dialog shows the service, operation, endpoint, GET path, data category and no redirects. | `android-3381-04-consent.png` |
| Deny (real tap) | 「取得を中止しました。何も送信していません。」, no health result, no `itonami` resource entry | `android-3381-05-denied.png` |
| Allow once (real tap) | One `GET /v1/grok-bots/businesses`. 8620 not launched, 5 other entries. | `android-3381-06-allowed.png` |
| PHI at rest | After the run, `grep` for 急性上気道炎, カロナール and 咽頭痛 across the app's 49 data files (4.7 MB) found nothing. | `run-as` |

The note and claim text were set through the debug WebView's DevTools, because `adb input` cannot type Japanese. Every button and the consent dialog were real taps.

The first two device attempts at the claim showed 「空レセプト」. The cause was my DevTools helper, not the app: a script scope clash, then a wrong selector. The input was never set. Once it was set, the result matched the browser.

## Review fixes (after the device run)

A review of the PR head found these, now fixed with tests:

- 診療識別 90 (入院) is refused, since the draft is outpatient-only and uses the 外来 高額療養費 cap.
- 診療識別 is checked against a set, so prototype names such as `toString` are refused.
- 点数, 回数, 薬価 and 日数 have upper bounds (999999 / 99 / 999999 / 365).
- Two 主病名 are observed (`multiple-main-diagnoses`). Carrying problems from a note into a claim that already has a 主病名 can cause this.
- 「P: 72」 written in O is read as a pulse rather than as the Plan header. 「P: 3日後に再診」 and 「P: 14日分処方」 stay headers.
- A problem name containing ・ is no longer split.
- A profile without the PHI rule is refused.

These changes were verified by `npm test` and `npm run build`. The APK and emulator run above were made from the source before these fixes (commit `8b9fc33`), and they were not repeated.

## Not verified / known gaps

- Network silence after a deny is not captured at packet level. It rests on the canonical broker, which fetches only after approval; the resource timing list; and the absence of a result.
- The PHI-at-rest check is a plain-text grep of the app data directory. WebView compressed or encrypted stores would not be caught by it. The app itself has no storage code.
- The consent dialog text is English, because it comes from the canonical runtime.
- The 点数 and 薬価 values were typed in. Nothing is checked against the 厚労省 master.
- The launched-bot path (activity feed) is covered only by the domain tests with real 7310 data, not on a device.
- No physical device, no Store enrollment, no production signing, no registry entry, no bot launch.

# Verification: SOAP summary draft through Murakumo (AWAI-3396, 2026-11-18)

## Source and build

- `npm test`: 35/35. The new tests cover:
  - The prompt carries the note, the missing sections, the out-of-range values, and the instruction not to add findings.
  - Prompt bounds stay under Murakumo's 8192-byte limit, and a note over 6000 bytes is refused.
  - Readiness is fail-closed: a missing `remote` field counts as remote.
  - Draft validation.
  - Refusal outside Android, with no fallback.
  - Denial is read from both a Tauri string and an Error.
  - No network operation was added.
- `npm run build`: the canonical runtime, Awai UI and `vendor/awai-inference` pins (awai-os `f52a243`, identical on awai-os main `ce7991c`) all match.

| APK | sha256 |
| -- | -- |
| Karute universal debug (source `490dd31`) | `ce9591be0dd9fdfe8ea1b31178a0dd52648bb9d8429aecdd21056c3388d75fd4` |
| Murakumo universal debug | `da016514b8c204bd634833a174bbac476e73960209cd5d4d62bfb1a5fd9d1c10` |

The Murakumo APK was built from the AWAI-3395 branch head `435b0c5`, which adds Karute to the allowlist. Its `apps/murakumo` tree is the same as on awai-os main `ce7991c`; main's other changes in between touch only store and distribution files. It used the pinned llama.cpp archive, reused from a local copy whose digest `6d06723d…` matches `pins.json`, and arm64 native libraries built by `scripts/build-native.py`.

Both builds ran under `forest-storage run` with `nice -n 10` and `CARGO_BUILD_JOBS=4`.

## Device: one disposable AOSP emulator

The emulator was `Awai_Karute_AWAI3381` on port 5590: Android 15 `AE3A.240806.019`, userdebug, no Google services, 4 GB RAM, started with `-wipe-data`. It was started after the host load fell below 20 and stopped right after the run.

| Step | Result | Evidence |
| -- | -- | -- |
| Permission | `network.awai.permission.LOCAL_INFERENCE` shows `granted=true` for Karute, so the debug signatures match. | dumpsys |
| Model | Pinned Qwen3 0.6B Q8_0 (sha256 `9465e63a…`), imported through Murakumo's 「Import from file」 and the system picker (real taps). Murakumo admitted it after its own digest check, and it loaded with a real tap on 「Load」. | `android-3396-m01-loaded.png` |
| Readiness | After the SOAP check: 「端末内の推論を使えます。」, and the draft button is enabled. | — |
| Karute confirmation, cancel | The confirmation names Murakumo and states that the note (PHI) goes once to it on this device. 「やめる」 shows 「やめました。記録は渡していません。」, and no Murakumo dialog appears. | `android-3396-k01-karute-confirm.png` |
| Murakumo confirmation, deny | Murakumo's native dialog is titled 「Karute からの生成」 and shows the prompt excerpt. 「キャンセル」 shows 「下書きを中止しました。推論は実行していません。」. No llama.cpp lines appeared in logcat, and no draft is shown. | `android-3396-k02-murakumo-confirm.png`, `android-3396-k03-denied.png` |
| Both allowed | The draft arrived in about 9 s, labelled 要約の下書き（Murakumo・端末内） and followed by the low-quality and not-a-record note. | `android-3396-k04-draft.png` |
| PHI at rest | `grep` for 咽頭痛, 急性上気道炎 and カロナール found nothing in Karute's 46 data files or in Murakumo's 56 data files (model excluded). | `run-as` |

The note was set through the debug WebView's DevTools, because `adb input` cannot type Japanese. Every button was a real tap, including both native confirmation buttons.

## Findings

- **The draft repeats only facts that are in the note**: the vitals, 咽頭発赤 and 高血圧症. It leaves out the chief complaint (咽頭痛, 鼻汁) and the plan, and it drops 急性上気道炎. This is one sample from a 0.6B model, and quality was not measured beyond it.
- **English vital ids in the prompt.** The prompt lists out-of-range values with internal ids, for example `temperature 37.8`, which then appear in Murakumo's prompt excerpt. Using the Japanese labels there is a small follow-up.

## Not verified

- After the device run, a review added a notice for one edge case: Murakumo stops being ready between Karute's confirmation and 「Murakumo に渡す」. Before, the confirmation closed silently. That path is checked by `npm test` and `npm run build` only.
- No physical device.
- Murakumo's own qualification is not repeated here.
- Network silence during generation is not captured at packet level. It rests on Murakumo having no INTERNET permission, and on Karute adding no network operation.

# Verification: 16 KB-aligned native library (AWAI-3426, AWAI-3393 rollout, 2026-11-18)

- **Change.** `src-tauri/build.rs` links Android targets with `-Wl,-z,max-page-size=16384`.
- **No inset change.** `MainActivity` already pads the content view for system bars, display cutout and the IME, and `uiMode` is in `configChanges`. Under the rollout rule, no VM was run.
- **Build.** A clean debug APK: `outputs/apk`, `intermediates/apk` and `intermediates/incremental` were deleted first. It was built with `nice -n 10` and `CARGO_BUILD_JOBS=4`, under the shared rollout `build.lock` (taken 05:43:55 UTC, released after the build), inside `forest-storage run`.

| Check | Before (APK `ce9591be…`, AWAI-3396) | After |
| -- | -- | -- |
| APK | 29.5 MB | 29,580,724 bytes, sha256 `69a82a625bdb7401ff01b625dc11c7b1b908a97ebdd4264554fb6ded9f756eb0` |
| `llvm-readelf -lW lib/arm64-v8a/libitonami_karute_lib.so` | 4 LOAD, all `Align 0x1000` | 4 LOAD, all `Align 0x4000` |
| `zipalign -c -P 16 -v 4` | — | `libitonami_karute_lib.so (OK)`, verification successful |

**Not established.** No 16 KB-page device or system image was used. The fix is shown by the ELF alignment only.
