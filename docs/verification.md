# Verification (AWAI-3381, 2026-11-18)

Source, build, browser and device evidence are recorded separately below. None of it covers distribution (Itonami Store), production signing, a physical device, registry registration or live bot operation.

## Source

`npm test`: 22/22 pass.

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

## Not verified / known gaps

- Network silence after a deny is not captured at packet level. It rests on the canonical broker, which fetches only after approval; the resource timing list; and the absence of a result.
- The PHI-at-rest check is a plain-text grep of the app data directory. WebView compressed or encrypted stores would not be caught by it. The app itself has no storage code.
- The consent dialog text is English, because it comes from the canonical runtime.
- The 点数 and 薬価 values were typed in. Nothing is checked against the 厚労省 master.
- The launched-bot path (activity feed) is covered only by the domain tests with real 7310 data, not on a device.
- No physical device, no Store enrollment, no production signing, no registry entry, no bot launch.
