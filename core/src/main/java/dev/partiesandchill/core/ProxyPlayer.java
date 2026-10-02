package dev.partiesandchill.core;

import net.kyori.adventure.text.Component;

import java.util.Optional;
import java.util.UUID;

/** A player connected to this proxy. Each platform wraps its own player type; wrappers are cheap and short-lived. */
public interface ProxyPlayer {

    UUID uniqueId();

    String name();

    void sendMessage(Component message);

    /** Shows {@code message} above the hotbar. */
    void sendActionBar(Component message);

    /** @return {@code true} if the proxy's permission plugin (e.g. LuckPerms) grants {@code permission} */
    boolean hasPermission(String permission);

    /** @return the backend connection, empty while the player is between servers */
    Optional<Backend> backend();

    /**
     * Proxies can't cancel signed chat (1.19.1+) without the client being kicked, so for those clients the chat
     * lock has to be enforced by the backend bridge.
     *
     * @return {@code true} if the client signs its chat
     */
    boolean signedChat();

    /** Sends the player to backend {@code server}; does nothing if the proxy doesn't know that server. */
    void connect(String server);

    /** A player's connection to a backend server. */
    interface Backend {

        /** @return the server name, as configured on the proxy */
        String name();

        /**
         * Sends a {@link dev.partiesandchill.core.bridge.BridgeChannel#CHANNEL} plugin message to the backend.
         *
         * @return {@code false} if the connection is gone (e.g. the player is switching servers)
         */
        boolean send(byte[] message);
    }
}
