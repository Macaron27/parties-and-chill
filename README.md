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
| `PartiesAndChill-Velocity-1.1.0.jar` | each Velocity proxy | Velocity 4.2.0+, Java 25 |
| `PartiesAndChill-Bridge-1.1.0.jar` | each backend server | Spigot/Paper 1.8.8 → 26.x, Java 8+ |

The backend bridge is required for the party chat lock on 1.19.1+ clients (Velocity can't cancel signed chat
without kicking the player), AdvancedBan mutes and the BedWars integrations. Without it, `/pc <message>` still works.

**Several proxies:** set `redis.enabled: true` with the same `uri` and `prefix` on every proxy
(standalone Redis or Sentinel; Redis Cluster is not supported).

**Upgrading from 1.0:** update the proxies first, then the backends. A 1.0 proxy doesn't understand a 1.1 bridge's
hello, so the chat lock would be unavailable on that backend until its proxy is updated.

## Developer API

Minigame and lobby plugins on the backends can read and change parties, and react to party activity.
The API ships inside the bridge jar, so you compile against it and declare the dependency:

```yaml
# plugin.yml
depend: [PartiesAndChillBridge]
```

### Get the API

```java
NetworkPartiesAPI parties = NetworkParties.getAPI();
// or, the same instance through Bukkit's services:
NetworkPartiesAPI parties = Bukkit.getServicesManager().load(NetworkPartiesAPI.class);
```

| Method | Returns | Notes |
|---|---|---|
| `getParty(uuid)` | `CompletableFuture<Optional<Party>>` | anyone on the network |
| `createParty(leader)` | `CompletableFuture<Boolean>` | leader must be online, not in a party |
| `addMember(leader, target)` | `CompletableFuture<Boolean>` | no invite needed; target must be online |
| `removeMember(leader, target)` | `CompletableFuture<Boolean>` | same as `/party kick` |
| `disbandParty(leader)` | `CompletableFuture<Boolean>` | same as `/party disband` |
| `isInParty(uuid)` / `isPartyLeader(uuid)` | `boolean` | local cache: **players on this server only** |

`Party` has `getId()`, `getLeader()`, `getMembers()` (join order, leader included) and `getServer()` (the server the
leader is on, `null` if offline). `false` means the party rules refused the change (not the leader, party full, a
plugin cancelled it...).

- Parties live on the proxy, so the async methods travel over the plugin channel: a player must be online on your
  server to carry them.
- Futures complete **on the main thread**, so callbacks can touch the world. Never `join()`/`get()` them on the
  main thread, since the answer arrives there.
- A future fails with `IllegalStateException` (nobody online to carry it, or a proxy error), `TimeoutException`
  (no answer in 5 s), or `CancellationException` (the bridge was disabled).

### Listen to events

```java
public final class ArenaListener implements Listener {

    @EventHandler
    public void onJoin(PartyJoinEvent event) { // async: don't touch the world here
        if (arenas.isPlaying(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("You can't join a party during a game.");
        }
    }

    @EventHandler
    public void onLeave(PartyLeaveEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> arenas.removeFromPartyTeam(player));
    }
}
```

| Event | Cancellable | Fired on the server of | When |
|---|---|---|---|
| `PartyCreateEvent` | yes | the leader | first `/party invite`, or `createParty` |
| `PartyJoinEvent` | yes | the joining player | `/party accept`, or `addMember` |
| `PartyDisbandEvent` | yes | the leader | `/party disband`, or `disbandParty` |
| `PartyChatEvent` | yes | the sender | `/pc <message>` or the chat lock (muted players are blocked before) |
| `PartyLeaveEvent` | no | the player who left | leave, kick, disband, party broke up |

Every event is **asynchronous** and `getParty()` shows the party right before the action. Cancellable events fire
*before* the action while the proxy waits for your server (up to 1 s, then the action goes ahead), so keep
listeners fast. The proxy only waits on servers where a plugin listens to that event; listeners registered after a
player joined count from the next join. Cancelling is silent: tell the player why yourself.
API calls fire the same events. The player the event is about carries the request when they are on your server;
with several proxies, a call carried by someone else can't reach their backend and isn't checked.

### Add the dependency

The API is published as `dev.partiesandchill:parties-and-chill-api:1.1.0` (Java 8, needs the Spigot/Paper API on
your classpath). Publish it to your local Maven repository:

```bash
./gradlew :api:publishToMavenLocal
```

Gradle (Kotlin DSL):

```kotlin
repositories { mavenLocal() }
dependencies { compileOnly("dev.partiesandchill:parties-and-chill-api:1.1.0") }
```

Gradle (Groovy):

```groovy
repositories { mavenLocal() }
dependencies { compileOnly 'dev.partiesandchill:parties-and-chill-api:1.1.0' }
```

Maven:

```xml
<dependency>
  <groupId>dev.partiesandchill</groupId>
  <artifactId>parties-and-chill-api</artifactId>
  <version>1.1.0</version>
  <scope>provided</scope>
</dependency>
```

Use `compileOnly` / `provided` and never shade it: the bridge provides these classes at runtime. To share it with
other developers, add your repository (Reposilite, Nexus...) to `publishing.repositories` in `api/build.gradle.kts`
and run `./gradlew :api:publish`.

The 1.0 `PartyCache` service is still registered for older plugins; new code should use `NetworkPartiesAPI`.

## Build

```bash
./gradlew build
```

Jars land in `velocity/build/libs/` and `paper-bridge/build/libs/`; ship the jars without `-plain` (only those contain
Jedis and the developer API, respectively).
Tests run the party rules against both the in-memory and the Redis store; the Redis tests start a throwaway
`redis-server` from your `PATH` and are skipped if it isn't installed.

## Layout

```
api/            developer API (Java 8): NetworkPartiesAPI, Party, events; shipped inside the bridge jar
velocity/       proxy plugin (Java 25): party rules, commands, listeners, Redis sync
  party/        Party, PartyMember, PartySettings, PartyManager, PartyStore (no Velocity imports)
  redis/        RedisPartyStore, RedisNetwork
  config/       config.yml / messages.yml loading
paper-bridge/   backend plugin (Java 8 bytecode): chat lock, AdvancedBan hook, BedWars adapters, developer API
```

Party state lives only on the proxy. The bridge receives snapshots on plugin channel `pnc:main` and sends developer API
requests there (the proxy applies them with the same rules as `/party`);
BedWars' own `disband`/`removeFromParty` calls (made when players leave arenas) are ignored on purpose.
