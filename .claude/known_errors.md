# Known errors & fixes

Consult before architectural changes.

## Velocity kicks players when a plugin cancels signed chat (1.19.1+)
- **Error:** denying `PlayerChatEvent` for a 1.19.1+ client disconnects them ("A proxy plugin caused an illegal protocol state"). Source: `SessionChatHandler#invalidCancel`, Velocity `dev/4.0.0`.
- **Fix:** proxy cancels chat only for clients `< MINECRAFT_1_19_1` (`ChatListener`); newer clients are diverted by the backend bridge (`ChatInterceptor`), which cancels `AsyncPlayerChatEvent` on Paper and forwards the line on `pnc:main`.

## Backend bridge must be Java 8 bytecode
- **Error:** 1.8.8 servers run Java 8; a Java 25 jar fails with `UnsupportedClassVersionError`.
- **Fix:** `paper-bridge` compiles with JDK 25 but `options.release = 8`. Never use Java 9+ APIs there (no `List.of`, `var`, records, switch expressions).

## BedWars1058 / BedWars2023 mutate the party on arena leave
- **Error:** `Arena#removePlayer` calls `getParty().disband(owner)` and `removeFromParty(member)`. Forwarding those to the proxy would destroy the network party after every game.
- **Fix:** bridge adapters make all mutation methods no-ops and return `isInternal() == false`; the proxy is the only writer.

## BedWars adapters get overwritten at startup
- **Error:** BedWars1058/2023 assign their own party adapter in a task 10 ticks after `onEnable`.
- **Fix:** the bridge calls `setPartyAdapter` 40 ticks after enable (soft-depend on the BedWars plugins).

## Plugin API artifacts don't resolve in Gradle
- **Error:** JitPack `bedwars-api:24.9` and tomkeuper `proxy-api:1.0` POMs reference unpublished parents; `bedwars-api:5.0.0` Gradle metadata declares Java 11 so a Java 8 compile rejects it.
- **Fix:** `metadataSources { artifact() }` on those repositories (jar only), dependencies declared non-transitive.

## MiniMessage unparsed placeholders don't expand inside tag arguments
- **Error:** `<click:run_command:'/party accept <player>'>` with `Placeholder.unparsed` sends the literal `<player>`.
- **Fix:** `Messages` uses `Placeholder.parsed` for player names that match `[A-Za-z0-9_.*-]{1,36}` (cannot contain tags), `unparsed` otherwise; chat text is always `unparsed`.

## Pub/sub test race
- **Error:** waiting for subscribers by publishing probes, then clearing the queue, still received late probes.
- **Fix:** tests skip probe events instead of clearing (`RedisNetworkTest#nextRealEvent`).

## EVALSHA gave no measurable gain (don't re-try it)
- **Tried:** sending Lua scripts by SHA-1 (`EVALSHA` + `NOSCRIPT` fallback) instead of `EVAL`.
- **Measured:** party lookup 46–48 µs with `EVAL` vs 48–58 µs with `EVALSHA` on localhost: noise. Reverted to keep `EVAL`.
- **What did help:** fewer round trips (Lua member→party lookup, reusing snapshots read under the lock, `ZCOUNT` pre-check, cached live-proxy set). See the redis/ package.
