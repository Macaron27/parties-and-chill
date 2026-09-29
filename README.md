# Parties & Chill

Hypixel-style parties for Velocity networks: invites, party chat, leader warps and automatic
"follow the leader into games", shared across every proxy through Redis.

## Features

- `/party` (`/p`): `invite`, `accept`, `deny`, `list`, `leave`, `kick`, `promote`, `disband`, `warp`; `/p <player>` invites.
- Invites expire after 60 s (clickable **[ACCEPT] / [DENY]**).
- **Auto-warp:** when the leader joins a game server (glob list, e.g. `bw-*`), online members follow after 1 s. Lobbies are excluded.
- **Party chat:** `/pc <message>`, or `/pc` alone to lock your chat to the party.
- **Disconnect grace:** members keep their slot for 5 min; a leader who doesn't return is replaced by a random online member.
- **Multi-proxy:** with Redis, parties, presence, names and mutes are network-wide; a crashed proxy's players enter the grace period.
- **AdvancedBan:** muted players can't use party chat; their party sees an "I am currently muted…" notice.
- **Minigames:** BedWars1058, BedWars2023 and BWProxy2023 use proxy parties (teams, arena joins).
- Every message is MiniMessage in `messages.yml`; every rule and timeout is in `config.yml`.

## Install

| Jar | Where | Needs |
|---|---|---|
| `PartiesAndChill-Velocity-1.0.0.jar` | each Velocity proxy | Velocity 4.2.0+, Java 25 |
| `PartiesAndChill-Bridge-1.0.0.jar` | each backend server | Spigot/Paper 1.8.8 → 26.x, Java 8+ |

The backend bridge is required for the party chat lock on 1.19.1+ clients (Velocity can't cancel signed chat
without kicking the player), AdvancedBan mutes and the BedWars integrations. Without it, `/pc <message>` still works.

**Several proxies:** set `redis.enabled: true` with the same `uri` and `prefix` on every proxy
(standalone Redis or Sentinel; Redis Cluster is not supported).

## Backend API

Other backend plugins can read parties through Bukkit's services:

```java
PartyCache parties = Bukkit.getServicesManager().load(PartyCache.class);
PartyCache.PartyView party = parties.partyOf(player.getUniqueId()); // null = no party
```

## Build

```bash
./gradlew build
```

Jars land in `velocity/build/libs/` (use `PartiesAndChill-Velocity-*.jar`, not `-plain`) and `paper-bridge/build/libs/`.
Tests run the party rules against both the in-memory and the Redis store; the Redis tests start a throwaway
`redis-server` from your `PATH` and are skipped if it isn't installed.

## Layout

```
velocity/       proxy plugin (Java 25): party rules, commands, listeners, Redis sync
  party/        Party, PartyMember, PartySettings, PartyManager, PartyStore (no Velocity imports)
  redis/        RedisPartyStore, RedisNetwork
  config/       config.yml / messages.yml loading
paper-bridge/   backend plugin (Java 8 bytecode): chat lock, AdvancedBan hook, BedWars adapters
```

Party state lives only on the proxy. The bridge receives read-only snapshots on plugin channel `pnc:main`;
BedWars' own `disband`/`removeFromParty` calls (made when players leave arenas) are ignored on purpose.
