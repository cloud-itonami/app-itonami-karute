# @itonami/bot-status

Shared bot-state reads for the Itonami family end-user apps (Karute, Rodo),
extracted under AWAI-3389 so the two apps stop carrying near-identical copies
of `karute.domain` / `rodo.domain`.

- Source: `src/itonami/bot_status.cljc` (pure `.cljc`, no I/O; Squint + JVM).
- Holds only app-neutral reads: assignment list, activity feed, fail-closed
  health, URL builders and id/time guards. Callers pass their own blueprint
  id and keep their own descriptors, consent operations and profile checks.
- Karute uses it through `karute.domain`, which re-exports the shared
  surface unchanged.

Tests live with the Karute suite: `test/shared_status_test.cljs` exercises
this namespace directly, and `test/domain_test.cljs` covers the Karute
delegation. Run from the repo root: `npm test`.

Publishing this for Rodo (registry or file dependency) and moving Rodo onto
it are owner decisions under AWAI-3380; this package defines the seam either
consumer needs.
