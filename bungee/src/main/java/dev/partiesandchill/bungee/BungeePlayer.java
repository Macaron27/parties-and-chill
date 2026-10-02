package dev.partiesandchill.bungee;

import dev.partiesandchill.core.ProxyPlayer;
import dev.partiesandchill.core.bridge.BridgeChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.json.JSONOptions;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.protocol.ProtocolConstants;

import java.util.Optional;
import java.util.UUID;

/** A BungeeCord {@link ProxiedPlayer} as core sees it. */
record BungeePlayer(ProxiedPlayer player) implements ProxyPlayer {

    /** The most widely understood chat JSON; BungeeCord re-encodes components for each client version. */
    private static final GsonComponentSerializer JSON =
            GsonComponentSerializer.builder().options(JSONOptions.compatibility()).build();

    /** @return core's (Adventure) text as BungeeCord components */
    static BaseComponent[] bungee(Component message) {
        return ComponentSerializer.parse(JSON.serialize(message));
    }

    @Override
    public UUID uniqueId() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getName();
    }

    @Override
    public void sendMessage(Component message) {
        player.sendMessage(bungee(message));
    }

    @Override
    public Optional<Backend> backend() {
        return Optional.ofNullable(player.getServer()).map(ServerBackend::new);
    }

    /** Like Velocity, the chat lock of 1.19.1+ clients is left to the backend bridge (signed chat can't be dropped). */
    @Override
    public boolean signedChat() {
        return player.getPendingConnection().getVersion() >= ProtocolConstants.MINECRAFT_1_19_1;
    }

    @Override
    public void connect(String server) {
        ServerInfo target = ProxyServer.getInstance().getServerInfo(server);
        if (target != null) player.connect(target);
    }

    record ServerBackend(Server server) implements ProxyPlayer.Backend {

        @Override
        public String name() {
            return server.getInfo().getName();
        }

        @Override
        public boolean send(byte[] message) {
            if (!server.isConnected()) return false;
            server.sendData(BridgeChannel.CHANNEL, message);
            return true;
        }
    }
}
