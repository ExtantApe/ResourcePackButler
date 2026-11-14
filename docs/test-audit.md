# Test audit

Kept by this repo's owning session (Minecraft: Plugins Lead), per `~/Code/CLAUDE.md`, "Test self-audits". Newest first.

## 2026-11-09: first full audit (at 0.2.0)

Each README promise and the test that holds it. "Bots" is `test/suite.mjs` on throwaway Paper 26.1.2, 26.2 and 26.3 with a local pack host; "unit" is `src/test`.

| Promise | Covered by |
| --- | --- |
| Ordered per-world list, sent together; empty list sends nothing | unit `PackPlanTest` (order, inserted pack, empty list); bots "a world with two packs gets both, in order", "an empty list removes Packmule's" |
| Same packs across worlds send nothing; different packs swap only Packmule's | unit `samePacksChangeNothing`, `swapReplacesTheWholeList`, `sharedStartIsKept`; bots teleport, same pack, End, swap |
| Never removes or replaces another plugin's pack | unit `emptyListRemovesOnlyPackmulesPacks`; bots "Packmule never removed a pack it didn't send", BlueMapMini's pack through switches, reloads and hopping |
| Every pack required: decline kicked, no answer kicked after 60 s | bots "a decline is kicked", "kicked after the timeout"; the 60 s default in the long run (`PACKMULE_TEST_LONG`) |
| Failed download retried twice, then the player stays in; admins told | bots retry, "the player stays in without it", admin warning, quiet once retries run out, recovery |
| **Players never see pack names, world names or addresses** | bots "the player is told once for the world… without internal names" |
| Startup check: 404, timeout, wrong hash, bad URL reported with worlds; the rest keep working; startup doesn't wait | unit `LinkCheckerTest` (each failure kind); bots startup section |
| `/packmule recheck`; players online keep their packs | bots "a re-check leaves players online alone", new hash on next world change and join |
| `.sha1` problems keep the last good hash | unit `brokenHashFileKeepsTheLastGoodHash`, `hashStoreSurvivesARestart`; bots |
| `server.properties` pack warns | bots |
| **Fail closed on a broken config:** reload keeps the old one; startup sends no packs and players still get in | bots "a reload with broken config warns and keeps the old config", "fails safe: players get in, with no packs from Packmule and no kick" |
| Commands need permission | bots "a player without permission can't…", "an op can" |
| Pack IDs are stable (clients see them) | unit `packIdsUseThePackmuleNamespace` |

**Gaps added this audit:** none. Every promise already had a test.

**Skipped, with reasons:**
- "No background polling": it's an absence of code (there's no scheduled check); a test watching for traffic over time would prove little more than reading `Packmule.java`, done at this audit.
- How the prompt and kick messages read on screen, and how a real loading screen feels: real client only, listed under "What hasn't been tested".
