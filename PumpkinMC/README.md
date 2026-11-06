# Parties & Chill for Pumpkin

Parties & Chill as [Pumpkin](https://github.com/Pumpkin-MC/Pumpkin) plugins. Pumpkin is a Minecraft server written in
Rust; its plugins are WebAssembly components (`wasm32-wasip2`) built against
[`pumpkin-plugin-api`](https://crates.io/crates/pumpkin-plugin-api) (WIT `pumpkin:plugin@0.1.0`, what Pumpkin loads by
default). Built against `0.2.1+26.3-26.51`, tested on the Pumpkin nightly `0.2.1+26.3-26.60` (2026-11-06, Minecraft
26.3), with Velocity 4.3.1 for the bridge.

Two plugins, like the Java jars:

| Plugin | Install it on | Plays the part of |
|---|---|---|
| `partiesandchill_pumpkin.wasm` | a Pumpkin server players join directly | the proxy plugin: parties live on this server (`/party`, `/pc`) |
| `partiesandchill_bridge.wasm` | each Pumpkin backend behind Velocity/BungeeCord proxies running Parties & Chill | `PartiesAndChill-paper.jar`: parties live on the proxies |

Install one or the other, never both on the same server.

## Layout

- `core/` (`parties-core`): the platform-agnostic port of `dev.partiesandchill.core`: party rules (`PartyManager`),
  roles, settings, party chat, both commands, `config.yml`, `messages.yml` (a MiniMessage renderer), the `pnc:main`
  bridge protocol. Nothing in it knows about Pumpkin; it hands the plugin effects to apply (chat lines, warps...).
- `plugin/` (`partiesandchill-pumpkin`): the Pumpkin glue for the party system (the counterpart of the Velocity
  module), built to `partiesandchill_pumpkin.wasm`.
- `bridge/` (`partiesandchill-bridge`): the Paper bridge for Pumpkin backends, built to `partiesandchill_bridge.wasm`.
- `tools/probe/`: a test plugin that uses the API (end-to-end tests only); `tools/e2e/`: the end-to-end tests.

## Build

Needs the Rust toolchain pinned in `rust-toolchain.toml` with the `wasm32-wasip2` target, and a host C linker for
build scripts (no wasi-sdk: there is no C code to compile to WebAssembly). From `PumpkinMC/`:

```bash
cargo plugins
# -> target/wasm32-wasip2/release/partiesandchill_pumpkin.wasm and partiesandchill_bridge.wasm
```

The core embeds `../core/src/main/resources/config.yml` and `messages.yml`, the files every platform ships, so build
inside a full checkout. Without a host toolchain, run cargo in `rust:1.99.1-slim` with `rustup target add
wasm32-wasip2`, mounting the whole repository.

## Install

Put the `.wasm` file in the server's `plugins/` folder. `partiesandchill` asks for `fs.write.data` (its config and
messages); Pumpkin asks the console to confirm on first load, or pre-approve it in `pumpkin.toml`:

```toml
[plugins]
allowed_permissions = ["fs.write.data"]
```

The bridge asks for nothing. Like the Paper bridge, it trusts `pnc:main` plugin messages: run it only behind proxies
running Parties & Chill (they block that channel from clients) with the backend reachable through them only
(`[networking.proxy.velocity]` with the forwarding secret, or BungeeCord forwarding behind a firewall).

## `partiesandchill`: parties on a Pumpkin server

### Config and messages

`plugins/data/partiesandchill/config.yml` and `messages.yml`: the same files and keys as the proxies
(`plugins/partiesandchill/` on Velocity), written on first start. Copy a proxy's files over to keep its rules and
wording. Missing keys use the defaults, as in Java. Every message is MiniMessage: colors, `#hex`, decorations,
`<gradient>`, `<rainbow>`, `<click>`, `<hover:show_text>`, `<insert>`, `<font>`, `<newline>`, `<reset>`; unknown tags
print as typed (as in MiniMessage).

Differences, because this is one server instead of a network of proxies and backends:

| Key | Proxy | Pumpkin |
|---|---|---|
| `auto-warp.servers` | backend server names | world names: `world`, `world_nether`, `world_the_end` (globs work, e.g. `world_*`) |
| `party.permission-sizes` | permission nodes | the same nodes in this plugin's namespace (`parties.size.16` → `partiesandchill:parties.size.16`) |
| `chat.timestamp-format` | proxy's time zone | UTC (the plugin sandbox has no time zone); the same pattern letters |
| `chat.mention.sound` | Bukkit `Sound` name or namespaced key | namespaced keys, and the common Bukkit names (`ENTITY_EXPERIENCE_ORB_PICKUP`, `BLOCK_NOTE_BLOCK_PLING`, `ENTITY_PLAYER_LEVELUP`, `UI_BUTTON_CLICK`...; unknown ones are logged once) |
| `redis.*` | parties shared between proxies | ignored with a warning: parties live on this server |

### Commands

The same commands, arguments and messages as on the proxies:

| Java | Pumpkin |
|---|---|
| `/party` (`/p`): `invite`, `accept`, `deny`, `join`, `list`, `leave`, `warp`, `kick`, `promote`, `demote`, `disband`, `settings`, `chat`, `help`; `/p <player>` invites | the same. `/p warp` teleports the online members to you (on a proxy it sends them to your server). |
| `/pchat` (`/pc`, `/party-chat`): `<message>`, `toggle` (or nothing), `spy` | the same; the chat lock works for every client (a server can cancel signed chat) |
| tab completion of subcommands, setting keys and player names | subcommands and `/pc toggle`/`spy` complete; player names don't (see below) |

### Permissions

Pumpkin only accepts plugin permissions in the plugin's namespace, so each Java node gets the `partiesandchill:`
prefix:

| Java | Pumpkin | Default |
|---|---|---|
| `parties.size.<n>` (`party.permission-sizes`) | `partiesandchill:parties.size.<n>` | deny |
| `parties.admin.spy` | `partiesandchill:parties.admin.spy` | operators level 3+ |
| (none: everyone may use the commands) | `partiesandchill:command.party`, `partiesandchill:command.pchat` | everyone |

Size permissions are re-read every second for online party owners, and whenever a player runs a command (a proxy reads
them live).

### Event listeners

| Velocity | Pumpkin |
|---|---|
| `PostLoginEvent` / `DisconnectEvent` | `PlayerJoinEvent` / `PlayerLeaveEvent` (a leave of a session replaced by a newer login is ignored) |
| `ServerPostConnectEvent` (auto-warp: the owner, or a moderator allowed to start games, enters a game server) | `PlayerChangedWorldEvent`: entering a world listed in `auto-warp.servers` makes the members ranked below follow after `auto-warp.delay` (teleported to the leader) |
| `PlayerChatEvent` (chat lock) | `PlayerChatEvent`, cancelled for locked players |
| `PluginMessageEvent` (`pnc:main`) | not needed: there is no backend |

### API for other plugins (IPC)

Pumpkin plugins talk through byte messages, so `NetworkPartiesAPI` is JSON over IPC:
`ipc::send_ipc_message("partiesandchill", br#"{"method":"getParty","player":"<uuid>"}"#)`. Replies are
`{"ok":true,"result":...}` or `{"ok":false,"error":"bad_request"|"unavailable","message":"..."}`.

| `method` | arguments | `result` |
|---|---|---|
| `getParty` | `player` | `{"id","leader","members","server"}` (`server`: the leader's world) or `null` |
| `isInParty`, `isPartyLeader` | `player` | `true`/`false` |
| `createParty` | `leader` | `true` if created (same rules as Java: online, not in a party, events not cancelled) |
| `addMember`, `removeMember` | `leader`, `target` | `true` if done (`removeMember` = `/party kick`) |
| `disbandParty` | `leader` | `true` if done |
| `setMuted` | `player`, `until` (epoch ms, `0` lifts, `9223372036854775807` forever) | `true`. AdvancedBan's job on Paper: muted players can't use party chat. |
| `subscribe` / `unsubscribe` | optional `events`: a list of event names | `true` |

After `subscribe`, Parties & Chill sends the plugin its events, like the Bukkit ones: `{"event": "PartyCreateEvent" |
"PartyJoinEvent" | "PartyDisbandEvent" | "PartyChatEvent", "player": "<uuid>", "party": {...}, "message": "..." (chat
only), "cancelled": false}`. Answer `{"cancelled": true}` to cancel, anything else (e.g. `{}`) to let it be. Subscribers
are asked in turn, each seeing the verdict so far, before the action; `PartyLeaveEvent` (`player`, `party` as it was)
comes after and can't be cancelled. A plugin may call the API back while handling an event. `tools/probe` is a
working example.

## `partiesandchillbridge`: a Pumpkin backend behind the proxies

It speaks `pnc:main` protocol 3, like `PartiesAndChill-paper.jar`, so the proxies treat a Pumpkin backend like a Paper
one:

| Paper bridge | Pumpkin bridge |
|---|---|
| hello on join / channel registration, with the events plugins listen to | the same (`PlayerJoinEvent`, `PlayerRegisterChannelEvent`) |
| party snapshots and the chat lock pushed by the proxy | cached; locked players' chat is cancelled and sent to the proxy (without a bridge, 1.19.1+ clients can't use `/pc toggle` on that server) |
| mention sounds | played (namespaced keys and the common Bukkit names) |
| AdvancedBan mutes | other plugins report them with `setMuted` (sent to the proxy, and again when the player joins) |
| `NetworkPartiesAPI`, its events, `PartyCache` | IPC, see below |
| BedWars1058 / BedWars2023 / BWProxy2023 adapters | not available (those plugins don't exist on Pumpkin) |

The API is the same as above with the plugin name `partiesandchillbridge`, except that calls the proxy answers
(`getParty`, `createParty`, `addMember`, `removeMember`, `disbandParty`) reply `{"ok":true,"result":{"request":<id>}}`
at once; the answer arrives later as an IPC message `{"reply":<id>,"ok":true,"result":...}` or
`{"reply":<id>,"ok":false,"error":"timeout"|"proxy_error"|"cancelled","message":"..."}` (after 5 s without an answer).
A player must be online on this server to carry them (else `"error":"unavailable"`). `isInParty`, `isPartyLeader` and
`getCachedParty` (`{"leader","members"}`, the 1.0 `PartyCache`) answer at once from what the proxy pushed, for players
on this server only. Events are checked by the proxy only on servers where a plugin listens to them; subscriptions count
from the next player join, as on Paper.

## Not like Java (yet)

- **Player names aren't tab-completed** (subcommands are). Pumpkin's v0.1 Wasm host stops the server when a player
  tab-completes an argument that has a plugin suggestion handler (seen on the 2026-11-06 nightly with the Friends port),
  so no handler is registered.
- **No Redis network mode** on `partiesandchill`: parties are per server. To share parties between servers, run them
  behind proxies with Parties & Chill and use the bridge.
- **Warps teleport** (there is no other server to send members to).
- **Timestamps are UTC.**
- MiniMessage tags beyond chat formatting (`<lang>`, `<key>`, `<selector>`, `<score>`, `<nbt>`, `<transition>`,
  `<pride>`, `<shadow>`, `<hover:show_item>`) print as typed.

## Tests

```bash
cargo native-test   # core suites natively (cargo test -p parties-core --target x86_64-unknown-linux-gnu)
cargo wasi-test     # the same as WASI programs (needs wasmtime on PATH)
```

`core/tests/` ports the Java suites: `PartyManagerContract` (every rule), `PartyRulesTest`, `PartyTest`,
`DurationsTest`, `LocalNetworkTest`, `PluginConfigTest`, `MessagesTest`, `PartiesCoreTest`, and `BridgeMessageTest`
with the same golden bytes as the Java proxy and the Paper bridge.

End-to-end on a real Pumpkin server, with 26.3 bot players (`tools/e2e/mcbot.py`, from the Friends repository):

```bash
cargo plugins && cargo build --release --target wasm32-wasip2 -p pnc-probe
python3 PumpkinMC/tools/e2e/run.py <dir> pumpkin bridge   # from the repository root
```

`<dir>` holds the Pumpkin binary as `pumpkin` (the `pumpkin-X64-Linux` asset of the
[nightly release](https://github.com/Pumpkin-MC/Pumpkin/releases/tag/nightly)); for `bridge` also `velocity.jar` and
`PartiesAndChill-velocity.jar` (`./gradlew :velocity:shadowJar`), run with Java from the `gradle:jdk25` Docker image.
Not observed by the bots: action bars and sounds (sent, not captured).
