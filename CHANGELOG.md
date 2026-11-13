# Changelog

What changed in each version of Parties & Chill, for server admins. Install steps and the config example are in the
[README](README.md); downloads are on the [releases page](https://github.com/Macaron27/parties-and-chill/releases).

## 1.3.0

Party roles, per-party settings, bigger parties by permission, a richer party chat, and Pumpkin support.

**Upgrading from 1.2:** update **every proxy at the same time**. A 1.2 proxy can't read 1.3 party chat lines and would
drop roles and settings when it saves a party. Backends can follow at any time. Details in the
[README](README.md#upgrading).

### Added

- **Roles: Owner → Moderator → Member.** `/p promote` makes a member moderator, and a moderator owner (the old owner
  stays on as moderator). `/p demote` turns a moderator back into a member. Kicks only work on lower ranks, and a
  leaving owner hands the party to a moderator first.
- **Moderator rights** in `roles.moderator`: `invite`, `kick`, `warp`, `promote`, `settings`, `start-games`,
  `chat-moderation`.
- **Per-party settings** with `/p settings` (a clickable menu): `autowarp`, `chat`, `allinvite`, `allwarp`, `public`,
  `mute`, `maxsize <n|max>`, `slowmode <time|off>`. New parties start from `party.defaults`.
- **`/p join <player>`** for public parties.
- **Party size by permission:** `party.permission-sizes` (e.g. `parties.size.16: 16`). The owner's biggest permission
  wins; owners without one get `party.max-size`. `maxsize` can only lower that limit.
- **Party chat:**
  - new aliases `/pchat`, `/party-chat` and `/p chat`, plus `/pc toggle` (`/pc` alone still toggles);
  - `/pc spy` for staff with `parties.admin.spy`;
  - clickable names (suggest `/msg <player>`) and hover timestamps (`chat.timestamp-format`);
  - `@name` highlights a party member and pings them with an action bar and a sound (`chat.mention`).
  - Mute and slow mode apply to plain members; owners and moderators are exempt.
- **Pumpkin servers** ([PumpkinMC/README.md](PumpkinMC/README.md)): `partiesandchill_pumpkin.wasm` runs parties on a
  single Pumpkin server, and `partiesandchill_bridge.wasm` is the backend bridge for Pumpkin servers behind Velocity or
  BungeeCord.
- Bridge protocol 3: the proxy sends mention sounds to the backend. 1.2 bridges keep working without the ping.

### Changed

- `/p promote` goes through moderator first: promote twice to hand over the party.
- Auto-warp only pulls members ranked below the player who entered the game, so two players allowed to start games
  can't warp each other back and forth.
- Logins write to Redis in one round trip (pipelined).

### Fixed

- With Redis, `/p invite <name>` stopped finding players who had been online for more than 20 minutes: their name keys
  expired while they were still online.
- A login handled after the player's first server switch now records the server.
- Invites sent by a member who leaves the party are cancelled, and the invited player is told.

### Known limitations

- Roles and settings are not in the developer API yet.
- BedWars integrations only know the owner as party leader: moderators who join an arena don't bring the party along.
- `/pc spy` lasts until the staff member logs out.
- Pumpkin: player names aren't tab-completed, the standalone plugin has no Redis mode, and party chat timestamps are
  UTC. The Pumpkin bridge has no BedWars adapters.
- Tested by 176 automated tests, and end to end with the Velocity and BungeeCord jars in front of a Pumpkin
  backend. Not yet checked on a live network with Paper backends: action bars on 1.8 clients and the mention sound on
  1.8 and 1.21 backends.

## 1.2.0 — 2026-10-02

- **BungeeCord support:** `PartiesAndChill-bungee.jar`, with the same features, `config.yml` and `messages.yml` as on
  Velocity.
- **Renamed jars:** `PartiesAndChill-velocity.jar`, `PartiesAndChill-bungee.jar`, `PartiesAndChill-paper.jar`,
  `PartiesAndChill-api.jar`. **Delete** `PartiesAndChill-Velocity-1.1.0.jar` / `PartiesAndChill-Bridge-1.1.0.jar`
  before adding the new ones, or the server finds the plugin twice.
- **Fix:** with Redis, players on a proxy that crashed now enter the disconnect grace period when it restarts. Before,
  they could stay "online" in their party forever.
- No protocol, config or API change: 1.1 proxies and bridges work with 1.2.

## 1.1.0 — 2026-09-30

- **Developer API** for backend plugins: read and change parties, and cancel party create, join, disband and chat
  events ([api/README.md](api/README.md)).
- **Security fix:** the `pnc:main` channel is now blocked before startup. Before, if the proxy plugin failed to start
  (e.g. Redis down), clients could reach backends on that channel.
- Bridge protocol 2. **Update the proxies before the backends:** a 1.0 proxy doesn't understand a 1.1 bridge, which
  disables the chat lock on that backend.

## 1.0.0 — 2026-09-29

First version, for Velocity: `/party` (invite, accept, deny, list, leave, kick, promote, disband, warp), auto-warp
into game servers, party chat with a chat lock, a disconnect grace period, multi-proxy through Redis, AdvancedBan
mutes, and BedWars1058, BedWars2023 and BWProxy2023 integrations.
