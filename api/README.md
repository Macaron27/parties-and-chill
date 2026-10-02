# Parties & Chill — Developer API

Minigame and lobby plugins on your **backend servers** (Spigot/Paper 1.8.8 → 26.x) can read and change network
parties, and react to party activity. Parties themselves live on the proxy (Velocity or BungeeCord); the API talks
to it through the Parties & Chill bridge (`PartiesAndChill-paper.jar`) installed on the same server.

- Java 8 bytecode: compiles into plugins for any server version.
- Needs the Spigot/Paper API on your classpath (events extend Bukkit's `Event`).
- The classes ship inside the bridge jar: **compile against them, never shade them.**

## 1. Add the dependency

**From a release:** download `PartiesAndChill-api.jar` from the GitHub release and add it as a compile-only file:

```kotlin
dependencies { compileOnly(files("libs/PartiesAndChill-api.jar")) }
```

**From source:** publish it to your local Maven repository, then depend on
`dev.partiesandchill:parties-and-chill-api:1.2.0`:

```bash
./gradlew :api:publishToMavenLocal
```

Gradle (Kotlin DSL):

```kotlin
repositories { mavenLocal() }
dependencies { compileOnly("dev.partiesandchill:parties-and-chill-api:1.2.0") }
```

Gradle (Groovy):

```groovy
repositories { mavenLocal() }
dependencies { compileOnly 'dev.partiesandchill:parties-and-chill-api:1.2.0' }
```

Maven:

```xml
<dependency>
  <groupId>dev.partiesandchill</groupId>
  <artifactId>parties-and-chill-api</artifactId>
  <version>1.2.0</version>
  <scope>provided</scope>
</dependency>
```

To share it with other developers, add your repository (Reposilite, Nexus...) to `publishing.repositories` in
`api/build.gradle.kts` and run `./gradlew :api:publish`.

Then declare the bridge as a dependency so it enables first:

```yaml
# plugin.yml
depend: [PartiesAndChillBridge]
```

## 2. Get the API

```java
NetworkPartiesAPI parties = NetworkParties.getAPI();
// or, the same instance through Bukkit's services:
NetworkPartiesAPI parties = Bukkit.getServicesManager().load(NetworkPartiesAPI.class);
```

`NetworkParties.getAPI()` throws `IllegalStateException` if the bridge isn't enabled (missing `depend:`).

## 3. Read and change parties

| Method | Returns | Notes |
|---|---|---|
| `getParty(uuid)` | `CompletableFuture<Optional<Party>>` | anyone on the network, or offline within the disconnect grace |
| `createParty(leader)` | `CompletableFuture<Boolean>` | leader must be online, not in a party |
| `addMember(leader, target)` | `CompletableFuture<Boolean>` | no invite needed; target must be online |
| `removeMember(leader, target)` | `CompletableFuture<Boolean>` | same as `/party kick` |
| `disbandParty(leader)` | `CompletableFuture<Boolean>` | same as `/party disband` |
| `isInParty(uuid)` / `isPartyLeader(uuid)` | `boolean` | local cache: **players on this server only** |

`Party` is an immutable snapshot: `getId()`, `getLeader()`, `getMembers()` (join order, leader included) and
`getServer()` (the server the leader is on, `null` if offline).

`false` means the party rules refused the change: not the leader, party full, target offline or already in a party,
or a plugin cancelled the matching event. Changes follow the same rules as `/party`, fire the same events and show
the same chat messages to the players involved.

```java
UUID leader = host.getUniqueId();
parties.createParty(leader)
        .thenCompose(created -> created
                ? parties.addMember(leader, friend.getUniqueId())
                : CompletableFuture.completedFuture(false))
        .thenAccept(added -> host.sendMessage(added ? "Your party is ready." : "Couldn't build the party."))
        .exceptionally(error -> {
            getLogger().warning("Parties & Chill API call failed: " + error.getCause());
            return null;
        });

parties.getParty(player.getUniqueId()).thenAccept(party -> {
    if (party.isPresent() && party.get().getMembers().size() > 4) player.sendMessage("Your party is too big for duels.");
});
```

### Threading and failures

- The async methods travel to the proxy over the plugin channel `pnc:main`, so **a player must be online on your
  server** to carry them.
- Futures complete **on the main thread**: callbacks may use the Bukkit API. Never `join()`/`get()` them on the main
  thread, since the answer is processed there.
- A future fails with `IllegalStateException` (nobody online to carry it, or a proxy error such as Redis being down),
  `TimeoutException` (no answer in 5 s, e.g. an outdated proxy plugin) or `CancellationException` (the bridge was
  disabled). With `exceptionally`, the cause is wrapped in a `CompletionException`.

## 4. Listen to events

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

- Every event is **asynchronous**: hop to the main thread (`runTask`) before touching the world.
- `getParty()` shows the party right before the action.
- Cancellable events fire *before* the action while the proxy waits for your server (up to 1 s, then the action
  goes ahead), so keep listeners fast: no blocking I/O. The action can still fail afterwards if the party changed
  meanwhile (e.g. it filled up).
- The proxy only waits on servers where a plugin listens to that event; listeners registered after a player joined
  count from the next join.
- Cancelling is silent: tell the player why yourself.
- API calls fire the same events. The player the event is about carries the request when they are on your server;
  with several proxies, a call carried by someone else can't reach their backend and isn't checked.

## 5. Compatibility

- The 1.0 `PartyCache` service (`dev.partiesandchill.bridge.PartyCache`) is still registered for older plugins; new
  code should use `NetworkPartiesAPI`.
- Upgrade the proxies before the backends: a 1.0 proxy doesn't understand a 1.1+ bridge's hello.
- The API is the same on Velocity and BungeeCord networks; your plugin doesn't need to know which proxy runs.
