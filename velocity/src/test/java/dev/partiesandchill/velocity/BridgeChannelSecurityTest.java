package dev.partiesandchill.velocity;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.partiesandchill.core.PartiesCore;
import dev.partiesandchill.core.ProxyPlatform;
import dev.partiesandchill.core.ProxyPlayer;
import dev.partiesandchill.core.bridge.BridgeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code pnc:main} is never forwarded, and only backends may talk to the proxy on it. */
class BridgeChannelSecurityTest {

    @TempDir
    Path dataDirectory;

    final BlockingQueue<byte[]> toBackend = new LinkedBlockingQueue<>();
    final Player player = fake(Player.class, (name, args) -> {
        throw new UnsupportedOperationException(name);
    });
    final ServerConnection server = fake(ServerConnection.class, (name, args) -> switch (name) {
        case "getServerInfo" -> new ServerInfo("lobby", InetSocketAddress.createUnresolved("lobby", 25565));
        case "getPlayer" -> player;
        case "sendPluginMessage" -> toBackend.add((byte[]) args[1]);
        default -> throw new UnsupportedOperationException(name);
    });
    final byte[] request = BridgeMessage.encode(new BridgeMessage.Request(7, BridgeMessage.ACTION_GET, new UUID(0, 1), new UUID(0, 1)));
    PartiesCore core;

    @BeforeEach
    void start() throws Exception {
        ProxyPlatform nobody = new ProxyPlatform() {
            @Override
            public Optional<ProxyPlayer> player(UUID id) {
                return Optional.empty();
            }

            @Override
            public List<String> playerNames() {
                return List.of();
            }
        };
        core = PartiesCore.start(nobody, dataDirectory, LoggerFactory.getLogger("test")).get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() {
        core.close();
    }

    @Test
    void backendMessagesAreHandledAndNotForwarded() throws Exception {
        PluginMessageEvent event = new PluginMessageEvent(server, player, PartiesAndChill.CHANNEL, request);
        PartiesAndChill.route(event, core, null);

        assertFalse(event.getResult().isAllowed());
        BridgeMessage.Reply reply = assertInstanceOf(BridgeMessage.Reply.class,
                BridgeMessage.decode(toBackend.poll(5, TimeUnit.SECONDS)), "answered on the connection it came through");
        assertEquals(7, reply.id());
    }

    @Test
    void clientsCantForgeBridgeMessages() throws Exception {
        PluginMessageEvent event = new PluginMessageEvent(player, server, PartiesAndChill.CHANNEL, request);
        PartiesAndChill.route(event, core, null);

        assertFalse(event.getResult().isAllowed(), "never reaches the backend");
        assertNull(toBackend.poll(300, TimeUnit.MILLISECONDS), "and the proxy doesn't act on it");
    }

    @Test
    void blockedEvenWhenStartupFailed() {
        PluginMessageEvent event = new PluginMessageEvent(player, server, PartiesAndChill.CHANNEL, request);
        PartiesAndChill.route(event, null, null);
        assertFalse(event.getResult().isAllowed());
    }

    @Test
    void otherChannelsAreLeftAlone() {
        PluginMessageEvent event = new PluginMessageEvent(server, player, MinecraftChannelIdentifier.create("other", "main"),
                new byte[0]);
        PartiesAndChill.route(event, core, null);
        assertTrue(event.getResult().isAllowed());
    }

    interface Answer {
        Object call(String method, Object[] args);
    }

    /** An interface implementation answering {@code answer}; {@code equals}/{@code hashCode} are identity-based. */
    @SuppressWarnings("unchecked")
    static <T> T fake(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName();
                    default -> answer.call(method.getName(), args);
                });
    }
}
