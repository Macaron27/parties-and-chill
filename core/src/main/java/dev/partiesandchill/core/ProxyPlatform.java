package dev.partiesandchill.core;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What core needs from the proxy software (Velocity, BungeeCord) besides its events and commands. */
public interface ProxyPlatform {

    /** @return the player, if connected to <em>this</em> proxy */
    Optional<ProxyPlayer> player(UUID id);

    /** @return names of the players connected to this proxy (tab completion) */
    List<String> playerNames();
}
