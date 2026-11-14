# Packmule

A Paper plugin that sends each world its own required resource packs, and warns at startup when a pack link is dead.

**Status:** early. Tested with Mineflayer bots on throwaway Paper 26.1.2, 26.2 and 26.3 servers (26.2 and 26.3 reached through ViaVersion and ViaBackwards), with packs served by a small local web server. The bots answer pack requests the way a client's pack manager does, but no real game client has loaded a pack from it yet. See [What hasn't been tested](#what-hasnt-been-tested).

## What it does

- Each world has an ordered list of packs. They're sent together with the multi-pack API, and later packs sit on top of earlier ones. A world with an empty list gets no packs from Packmule.
- Moving between worlds with the same packs sends nothing. Moving to a world with different packs swaps only Packmule's packs.
- Packmule never removes or replaces a pack it didn't send. Another plugin's pack, such as BlueMapMini's, stays put.
- Every pack is required. A player who declines is kicked. A client that doesn't answer the prompt in 60 seconds is kicked.
- A failed download is sent again twice. If it still fails, the player stays in without that pack and admins are told which world and pack. Nobody is kicked over a hosting problem.
- At startup, Packmule downloads every pack and checks its SHA-1. A 404, a timeout, a wrong hash or a malformed address is reported with the worlds it affects. That pack is skipped until it's fixed, and the rest keep working. Startup doesn't wait for the check.
- `/packmule recheck` runs the check again. There's no background polling. Players already online keep their packs until they change worlds or rejoin.
- It warns at startup if `server.properties` sets `resource-pack=`, because that pack would fight Packmule's.

Paper API only, no NMS. It doesn't depend on any world-management plugin.

## Supported versions

Paper 26.1 and up, on Java 25. Tested on 26.1.2, 26.2 and 26.3. It won't load on 1.21.x. [docs/26x-audit.md](docs/26x-audit.md) covers which newer Paper APIs it uses and which it doesn't.

## Pack addresses

A pack's hash comes from one of three places:

| Config | Hash from |
| --- | --- |
| `url: https://host/TOKEN/<sha1>.zip` | The file name. Nothing else needed. |
| `url: https://host/TOKEN/pack.zip`<br>`sha1-url: https://host/TOKEN/pack.zip.sha1` | The hash file, read at startup and on `/packmule recheck`. |
| `url: ...` with `sha1: ...` | The config. |

With a hash file, Packmule also looks for `<same folder>/<sha1>.zip`. If that address serves the same build, players get it instead of the moving one, so nobody downloads a file that changed under an old hash. If the hash file is missing or doesn't hold a SHA-1, Packmule warns and keeps the last hash it confirmed. It remembers that hash across restarts, in `last-good-hashes.yml`.

Leaf Juice serves its recipes at both kinds of address, but Packmule works with any host.

## Install

1. Put `Packmule-<version>.jar` in `plugins/` and start the server once.
2. Edit `plugins/Packmule/config.yml`: name your packs, then list every world, each Nether and End included.
3. Leave `resource-pack=` empty in `server.properties`.
4. Run `/packmule reload`, or restart.

```yaml
packs:
  main:
    url: https://packs.example.com/TOKEN/0123456789abcdef0123456789abcdef01234567.zip
  extra:
    url: https://packs.example.com/OTHER/pack.zip
    sha1-url: https://packs.example.com/OTHER/pack.zip.sha1

worlds:
  world: [main]
  world_nether: [main]
  world_the_end: [main]
  some_world: [main, extra]
  plain_world: []
```

A world that isn't listed gets nothing, and Packmule warns about it at startup. There's no shorthand for groups of worlds.

### What players see

Every message is in `config.yml` under `messages`, in MiniMessage, and the defaults are written to be read by players:

- **The pack prompt** says the world has its own pack and to choose Proceed.
- **The kick after a decline** says the packs are needed. It also explains the usual cause of a decline nobody chose: the game is set to refuse server packs, and the message says where to change that.
- **The kick after no answer** gives the timeout in seconds.
- **In chat, once per session:** a note when a pack couldn't be downloaded after its retries, and a note when part of a world's pack is unavailable because of a dead link. Players never see pack names, world names or addresses. Those go to admins.

If `config.yml` has a mistake, Packmule says what it is. On `/packmule reload` it keeps the config it already had. At startup it sends no packs at all until the mistake is fixed, and players still get in.

## Commands

| Command | Permission (default op) | What |
| --- | --- | --- |
| `/packmule recheck` | `packmule.recheck` | Re-test every link and re-read every hash file |
| `/packmule reload` | `packmule.reload` | Read `config.yml` again, then re-check |
| `/packmule status [player]` | `packmule.status` | Each pack's state, each world's list, or what one player has |
| `/packmule pack set <pack> <url>` | `packmule.pack.set` | Point a pack at a new per-build address. Packmule downloads it first and checks its SHA-1 against the file name. Only a pass is written to `config.yml`, with its comments kept, and then the config is reloaded. Anything else leaves the old address. |
| `/packmule describe` | `packmule.status` | Lists the commands tools can use |

`pack set` and `describe` also answer tools such as Leaf Juice. Add `--json <id>` at the end and the answer is one line in the [Signalbox](https://github.com/ExtantApe/Signalbox) format. A pass answers `{"ok":true,"pack":"main","sha1":"…","check":"passed"}`. A refusal answers `ok:false` with a code:
- `bad-argument` for an address that isn't https, or a file name that isn't the SHA-1;
- `not-found` for an unknown pack;
- `check-failed` when the download fails or its SHA-1 doesn't match;
- `busy` while another change is being checked;
- `config-problem` when `config.yml` can't be read or changed.

`packmule.notify` (default op) shows dead-link and failed-download warnings in chat.

## Build

Java 25.

```
./gradlew build
```

The jar is `build/libs/Packmule-<version>.jar`. A `v*` tag builds it and attaches it to a GitHub Release.

## Tests

`./gradlew test` runs the unit tests: config parsing, the link checker against a local web server, and the pack diff.

`test/` holds the bot tests. They start throwaway Paper servers on 127.0.0.1:25613 in a temp folder you choose, and serve made-up packs on 127.0.0.1:25614. Bots connect as 26.1 clients: directly on 26.1.2, and through ViaVersion and ViaBackwards on 26.2 and 26.3. They answer pack requests the way the client's pack manager does, and download and hash each pack. `test/waystation.mjs` walks a bot through a Waystation gate and back.

On 26.3, a bot behind ViaBackwards is kicked for "invalid player movement" as soon as it moves, so there the bots stand still and every world change is a server teleport. Packmule's tests don't need walking. The Waystation gate walk only runs on 26.1.2 and 26.2.

```
cd test && npm install
PACKMULE_TEST_WORK=/tmp/rpb PACKMULE_TEST_JARS=/path/to/jars PACKMULE_TEST_JAVA=/path/to/java25 node suite.mjs 26.3
```

`PACKMULE_TEST_JARS` holds `paper-<version>.jar` for each version, Multiverse-Core 5.8.2, Multiverse-Inventories 5.3.6, `bluemap-5.29-paper.jar`, and ViaVersion and ViaBackwards 5.12.3. BlueMapMini and Waystation come from their own builds in `~/Code`.

## What hasn't been tested

Only a real client can tell:

- Whether a pack visibly loads, and how long the loading screen interrupts play. Bots only answer the request.
- Whether a player who clicks Disconnect on the prompt sees Packmule's kick message, or the game's own screen. The client may leave before the kick arrives.
- How the prompt and kick text read on screen, line breaks included.
- Whether switching feels acceptable when worlds use large packs.
- Whether packs stack in the right order next to BlueMapMini's, and whether its font still draws.
- What happens across a server transfer.
- How older clients connected through ViaVersion treat the pack format.

Bedrock players through Geyser haven't been tested at all.

## License

MIT. See [LICENSE](LICENSE).
