package dev.partiesandchill.velocity;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.partiesandchill.core.ProxyPlayer;
import net.kyori.adventure.text.Component;

import java.util.Optional;
import java.util.UUID;

/** A Velocity {@link Player} as core sees it. */
record VelocityPlayer(ProxyServer proxy, Player player) implements ProxyPlayer {

    @Override
    public UUID uniqueId() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getUsername();
    }

    @Override
    public void sendMessage(Component message) {
        player.sendMessage(message);
    }

    @Override
    public void sendActionBar(Component message) {
        player.sendActionBar(message);
    }

    @Override
    public boolean hasPermission(String permission) {
        return player.hasPermission(permission);
    }

    @Override
    public Optional<Backend> backend() {
        return player.getCurrentServer().map(ServerBackend::new);
    }

    /** Denying signed chat kicks the client ("illegal protocol state"), see {@code SessionChatHandler#invalidCancel}. */
    @Override
    public boolean signedChat() {
        return player.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_19_1) >= 0;
    }

    @Override
    public void connect(String server) {
        proxy.getServer(server).ifPresent(target -> player.createConnectionRequest(target).fireAndForget());
    }

    record ServerBackend(ServerConnection connection) implements ProxyPlayer.Backend {

        @Override
        public String name() {
            return connection.getServerInfo().getName();
        }

        @Override
        public boolean send(byte[] message) {
            try {
                return connection.sendPluginMessage(PartiesAndChill.CHANNEL, message);
            } catch (IllegalStateException e) { // "not connected"
                return false;
            }
        }
    }
}
