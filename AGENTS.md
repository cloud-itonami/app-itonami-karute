# Karute

Follow the workspace AGENTS.md, including the Kotoba end-user apps contract.

- The common domain is `.cljc` (`src/karute/domain.cljc`, `soap.cljc`, `rezept.cljc`), and the UI is CLJS through Squint with the pinned `@awai/ui`. Tauri v3 is a thin host. Do not fork the UI components or the consent logic.
- Every external request goes through the canonical client runtime broker with `each-operation` consent. Add a route only by declaring it in `karute.domain/operations`, and keep the CSP `connect-src` equal to `clientRuntimeConnectSrc` of the descriptor.
- PHI (SOAP text, claim lines, names, birth dates, insurer numbers) stays in page memory. Do not store it, hand it to the Tauri host, put it in a URL, or send it. A feature that needs to move PHI needs its own declared operation, consent and review.
- The claim arithmetic is a port of `cloud-itonami/iryo` at the revision named in `src/karute/rezept.cljc`. Change it only together with iryo, and keep the tests on iryo's expected values. Results are drafts and observations, never adjudication or submission.
- The bundled profiles are proposed and not in the canonical registry. Do not mark them registered until `com-junkawasaki/root` contains them, then copy the registry entry and record its revision.
- Health stays fail-closed. Do not launch or reconfigure live bots from this repository.
- Keep source, build, device, distribution and live-operation evidence separate, and record unknowns as unknown.

## Legacy appview

The rest of this file is the earlier etzhayyim Svelte appview guide (`appview/`). That UI is migration inventory and is not part of the Karute app.

### etzhayyim-project-karute — 電子カルテ EMR

actor: `did:web:karute.etzhayyim.com` (nanoid `karu7t3e`).

See `orgs/etzhayyim/com-etzhayyim-karute/AGENTS.md` for the authoritative architecture doc.
This file documents the Svelte SuperApp UI only.

### Build

```bash
cd appview/etzhayyim-wasm-karute-karu7t3e/svelte
pnpm install
pnpm dev      # local dev server
pnpm build    # production bundle → dist/
pnpm check    # svelte-check type errors
```

### UI shape

- **Mobile-first**, `max-w-[600px]` 内側
- **SuperAppTabBar** (固定下部): Home / Chart / Orders / Talk
- **Sidebar 禁止**
- Svelte 5 runes (`$state`, `$derived`, `$effect`) で state
- 簡易ルーター (`hashchange`-based) — SvelteKit を使わない

### PHI handling rule (CRITICAL)

App code は plaintext PHI を **絶対に** XRPC body に直書きしてはいけない。
`@etzhayyim/sdk` の `encryptedWrite()` を介してのみ書く。

Phase 1 では `lib/api/karute-client.ts` が encrypted-write 呼び出しを抽象化する。
PHI plaintext が `karute-client` の外で `fetch()` body に乗らないことを review で確認。
