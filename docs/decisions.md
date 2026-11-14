# Decisions

Owner decisions that shape Butler, with the date each was made. Change these only with the owner.

## From the design notes (before 2026-11-07)

- Packs are chosen per world, as an ordered list sent together with the multi-pack API. An empty list means Butler sends that world nothing.
- Packs stay required.
- A dead link is reported at startup. Admins are warned, naming the world and the pack, and players get in without that pack. Nobody is kicked over a hosting problem.
- A failed download is retried. A true decline is kicked with a clear message.
- `server.properties` stays empty (`resource-pack=`), and Butler warns at startup if it's set.
- Butler learns a new pack's hash without hand edits. It supports a per-build address with the hash in the file name, and a moving address with a `.sha1` file, preferring the per-build address when one serves the same build.
- Butler never removes or replaces packs it didn't send.
- No shorthand for groups of worlds: every world, each Nether and End included, is listed on its own.
- A re-check command, and no background polling. Players online keep their packs until they change worlds or rejoin.
- Paper API only, no NMS. Butler doesn't depend on any world-management plugin.
- Real world, pack and server names stay in private config, never in this repo.

## 2026-11-07

- **Failed downloads:** two retries, then the player stays in without that pack, and admins are warned, naming the world and the pack.
- **No answer to the prompt:** 60 seconds counts as a decline, and the player is kicked with a clear message.
- **Permissions:** `/butler recheck` is ops only. There's no permission to skip packs.
- **Test servers:** the owner accepted the Minecraft EULA for throwaway local test servers.
- **Versions:** Minecraft 26.1 and up only, on Java 25, with no 1.21.x support. Tested on 26.1, 26.2 and 26.3.
- **Paper only:** `paper-api` and `paper-plugin.yml`, not Spigot.
- **Done well:** what players and admins see should be clear and readable, with sensible defaults. This produced the player messages in `config.yml`.
- **Test hygiene:** one test server at a time, deleted after its run.
- **Going public:** the owner said yes to making this repo public at its first release, after the first-public-push audit passed.
- **Name (2026-11-08, owner):** ResourcePackButler is renamed Packmule, one word in every place: repo, plugin name, jar, package and prose. Released as 0.2.0. Existing servers get their `plugins/ResourcePackButler/` folder copied on first start (never overwritten), and the old commands and permissions keep working for one release with a log warning. Pack IDs keep the old `resourcepackbutler:pack:` namespace for good, because clients see them.
- **pack set for Leaf Juice (2026-11-14, D189):** each pack build is published at a new unguessable path on the join host, and Leaf Juice hands Packmule the new link through `/packmule pack set <pack> <url> --json <id>`, as the Leaf Juice App session proposed and Helm assigned (K644). It takes https only, with the SHA-1 as the file name. Packmule downloads the file and checks the SHA-1 before writing `config.yml` (comments kept) and reloading, and a failure keeps the old address. Released as 0.2.1, so the old ResourcePackButler names stay for this 0.2.x release as promised.
- **Old names (2026-11-14, owner):** the ResourcePackButler commands, permissions, folder copy and pack ID namespace are gone; pack IDs are now packmule:pack:<name>, so clients download each pack once more.
