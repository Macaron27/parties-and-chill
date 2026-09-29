package dev.partiesandchill.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import dev.partiesandchill.velocity.chat.PartyChat;
import dev.partiesandchill.velocity.network.Network;
import dev.partiesandchill.velocity.party.PartyManager;

import java.util.concurrent.ExecutorService;

/** Keeps presence and the disconnect grace period in sync with logins and logouts. */
public final class ConnectionListener {

    private final PartyManager manager;
    private final Network network;
    private final PartyChat chat;
    private final ExecutorService executor;

    public ConnectionListener(PartyManager manager, Network network, PartyChat chat, ExecutorService executor) {
        this.manager = manager;
        this.network = network;
        this.chat = chat;
        this.executor = executor;
    }

    @Subscribe
    public void onLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        executor.execute(() -> {
            network.playerJoined(player.getUniqueId(), player.getUsername());
            manager.reconnected(player.getUniqueId());
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        // Only sessions that passed PostLogin; a CONFLICTING_LOGIN means the original session is still online.
        if (event.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN
                && event.getLoginStatus() != DisconnectEvent.LoginStatus.PRE_SERVER_JOIN) return;
        Player player = event.getPlayer();
        executor.execute(() -> {
            chat.forget(player.getUniqueId());
            network.playerLeft(player.getUniqueId());
            manager.disconnected(player.getUniqueId());
        });
    }
}
