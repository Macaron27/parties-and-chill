# Other platforms

Home for platforms beyond Velocity, BungeeCord and Paper. None live here yet. Forks that keep their parent's plugin
API should run the existing jars (e.g. Waterfall with the BungeeCord jar, Purpur with the Paper jar); that is untested.

## Adding a proxy platform

A proxy module is a thin adapter over `core/` (see `velocity/` and `bungee/`, a few hundred lines each):

1. **`ProxyPlayer`**: wrap the platform's player. `signedChat()` must be `true` for 1.19.1+ clients if the proxy can't
   drop signed chat safely; `backend().send(...)` sends a plugin message on `pnc:main` to the player's current server.
2. **`ProxyPlatform`**: look players up by UUID and list the names on this proxy.
3. **Startup**: register the `pnc:main` channel *before* any I/O, then `PartiesCore.start(platform, dataDir, logger)`.
   Register commands (`core.partyCommand()`, `core.chatCommand()`) only once it completes.
4. **Events**: forward login, logout, server switch, chat and plugin messages to `PartiesCore`. Never forward
   `pnc:main` messages, and only hand over those sent **by a backend** (a client could forge party data otherwise).
5. **Build**: `implementation(project(":core"))`, shade what the proxy doesn't provide (see `bungee/build.gradle.kts`),
   and write the jar to `builds/PartiesAndChill-<platform>.jar`.

## Adding a backend platform

Backends speak the `pnc:main` protocol documented in `paper/.../BridgeProtocol.java` (mirrored by
`core/.../BridgeMessage.java`; both are pinned by the same golden bytes in their tests). A new backend implements the
hello, snapshot, chat-lock, check/verdict and request/reply messages, and exposes the developer API in `api/` if it
can load Bukkit classes.

Candidates: Folia (needs region schedulers instead of the Bukkit scheduler), Sponge, Minestom.
