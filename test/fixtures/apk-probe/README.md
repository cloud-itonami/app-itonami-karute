# APK probe fixtures (KOTOBA-159)

Real `aapt2 dump xmltree --file AndroidManifest.xml` and `dexdump -d` output (Android build-tools 36.1.0) from two minimal APKs built without Gradle on 2026-11-22. They mirror karute's hardening and AGP's `BuildConfig`, so `scripts/android-apk-check.mjs` is tested against the tools' real output formats:

- `MainActivity.java`: the same calls as karute's `MainActivity.onCreate`.
- release: `BuildConfig.DEBUG = false` (as AGP writes it for release); no `android:debuggable`.
- debug: `BuildConfig.DEBUG = Boolean.parseBoolean("true")` (as AGP writes it for debug); `android:debuggable="true"`.

Built with `javac --release 17` against `platforms/android-35/android.jar`, then `d8 --release --min-api 35`, then `aapt2 link`; `data_extraction_rules.xml` is the app's own. These are **not** karute APKs. A real release APK of karute is checked with `node scripts/android-apk-check.mjs <apk> release` (the check-build worker).
