# Parties & Chill

Hypixel-style parties for Velocity and BungeeCord networks: invites, party chat, leader warps and automatic
"follow the leader into games", shared across every proxy through Redis.

## Features

- `/party` (`/p`): `invite`, `accept`, `deny`, `join`, `list`, `leave`, `kick`, `promote`, `demote`, `disband`, `warp`,
  `settings`, `chat`; `/p <player>` invites.
- Invites expire after 60 s (clickable **[ACCEPT] / [DENY]**).
- **Roles:** Owner → Moderator → Member. `/p promote` makes a member moderator, and a moderator owner (the old owner
  stays as moderator); `/p demote` turns a moderator back into a member. What moderators may do (invite, kick members,
  warp, promote, settings, start games, chat moderation) is set in `roles.moderator`.
- **Per-party settings** (`/p settings`, a clickable menu): `autowarp`, `chat`, `allinvite` (members may invite),
  `allwarp` (members may warp), `public` (anyone may `/p join`), `mute` (only staff talk), `maxsize <n|max>`,
  `slowmode <time|off>`. New parties start from `party.defaults`.
- **Party size by permission:** `parties.size.<n>` entries in `party.permission-sizes` (LuckPerms or any proxy permission
  plugin); the owner's biggest one wins, `party.max-size` otherwise.
- **Auto-warp:** when the owner joins a game server (glob list, e.g. `bw-*`), online members follow after 1 s;
  moderators allowed to start games pull the plain members. Lobbies are excluded.
- **Party chat:** `/pc <message>` (also `/pchat`, `/party-chat`, `/p chat`), `/pc toggle` to send everything you type to
  the party. Names are clickable (suggests `/msg`), timestamps show on hover, `@name` highlights a member and pings
  them (action bar + sound). Staff with `parties.admin.spy` read every party with `/pc spy`.
- **Disconnect grace:** members keep their slot for 5 min; an owner who doesn't return is replaced by a random online
  moderator (or member).
- **Multi-proxy:** with Redis, parties, presence, names and mutes are network-wide; a crashed proxy's players enter the grace period.
- **AdvancedBan:** muted players can't use party chat; their party sees an "I am currently muted…" notice.
- **Minigames:** BedWars1058, BedWars2023 and BWProxy2023 use proxy parties (teams, arena joins). They only know
  the owner as party leader: moderators who join an arena on the same server don't bring the party along.
- **Developer API** for backend plugins: read and change parties, cancel party actions ([api/README.md](api/README.md)).
- Every message (chat formats included) is MiniMessage in `messages.yml`; every rule and timeout is in `config.yml`.

### Permissions

| Permission | Grants |
|---|---|
| `parties.size.<n>` (as listed in `party.permission-sizes`) | owners lead parties of up to `<n>` members |
| `parties.admin.spy` | `/pc spy`: read every party's chat (until you log out) |

## Install

Pick the jar for your proxy, and put the Paper bridge on every backend.

| Jar | Where | Needs |
|---|---|---|
| `PartiesAndChill-velocity.jar` | each Velocity proxy | Velocity 4.2.0+, Java 25 |
| `PartiesAndChill-bungee.jar` | each BungeeCord proxy | BungeeCord 1.21 (built against `1.21-R0.4`), Java 21+ |
| `PartiesAndChill-paper.jar` | each backend server | Spigot/Paper 1.8.8 → 26.x, Java 8+ |
| `PartiesAndChill-api.jar` | your plugin's build (compile only) | see [api/README.md](api/README.md) |

Both proxy jars read the same `config.yml` and `messages.yml` (in `plugins/partiesandchill/` on Velocity,
`plugins/PartiesAndChill/` on BungeeCord).

The backend bridge is required for the party chat lock on 1.19.1+ clients (a proxy can't drop signed chat without
the player being kicked), mention sounds, AdvancedBan mutes, the BedWars integrations and the developer API. Without it,
`/pc <message>` still works.

**Several proxies:** set `redis.enabled: true` with the same `uri` and `prefix` on every proxy
(standalone Redis or Sentinel; Redis Cluster is not supported).

**Upgrading from 1.2:** update **every proxy at the same time**. A 1.2 proxy can't read 1.3 party chat lines (Redis
logs "unknown event type") and would drop roles and settings when it saves a party. Parties created by 1.2 keep their
members and get the built-in default settings. Backends can follow at any time: a 1.2 bridge still works, its players
just don't hear the mention ping (bridge protocol 3). `config.yml` gains `party.permission-sizes`, `party.defaults`,
`roles.moderator`, `chat.timestamp-format` and `chat.mention`; missing keys use the defaults. `/p promote` now makes a
member moderator first (promote again to hand over the party).

**Upgrading from 1.0:** update the proxies first, then the backends. A 1.0 proxy doesn't understand a 1.1+ bridge's
hello, so the chat lock would be unavailable on that backend until its proxy is updated. 1.1 → 1.2 changes no
protocol, config or API. The jars were renamed (`PartiesAndChill-Velocity-1.1.0.jar` → `PartiesAndChill-velocity.jar`,
`PartiesAndChill-Bridge-1.1.0.jar` → `PartiesAndChill-paper.jar`): **delete the old jar** before adding the new one,
or the server finds the plugin twice.

## Build

```bash
./gradlew build
```

Every jar lands in `builds/`. Tests run the party rules against both the in-memory and the Redis store; the Redis
tests start a throwaway `redis-server` from your `PATH` and are skipped if it isn't installed.

## Layout

```
api/        developer API for backend plugins (Java 8): NetworkPartiesAPI, Party, events + usage guide
core/       proxy logic shared by every proxy (Java 21): party rules, Redis sync, config, bridge protocol, commands
velocity/   Velocity adapter: events, commands and players handed to core
bungee/     BungeeCord adapter: same, plus shaded libraries Velocity would otherwise provide
paper/      backend bridge (Java 8 bytecode): chat lock, AdvancedBan hook, BedWars adapters, developer API
etc/        how to add other platforms
builds/     compiled jars (not committed)
```

Party state lives only on the proxy. The bridge receives snapshots on plugin channel `pnc:main` and sends developer API
requests there (the proxy applies them with the same rules as `/party`);
BedWars' own `disband`/`removeFromParty` calls (made when players leave arenas) are ignored on purpose.
