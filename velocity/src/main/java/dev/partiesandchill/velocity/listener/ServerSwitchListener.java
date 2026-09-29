package dev.partiesandchill.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import dev.partiesandchill.velocity.bridge.BridgeChannel;
import dev.partiesandchill.velocity.chat.PartyChat;
import dev.partiesandchill.velocity.party.Party;
import dev.partiesandchill.velocity.party.PartyManager;

import java.util.concurrent.ExecutorService;

/**
 * After every server switch: refresh the new backend's party snapshot and chat lock, then auto-warp the
 * party if its leader just entered a game server.
 */
public final class ServerSwitchListener {

    private final PartyManager manager;
    private final BridgeChannel bridge;
    private final PartyChat chat;
    private final ExecutorService executor;

    public ServerSwitchListener(PartyManager manager, BridgeChannel bridge, PartyChat chat, ExecutorService executor) {
        this.manager = manager;
        this.bridge = bridge;
        this.chat = chat;
        this.executor = executor;
    }

    @Subscribe
    public void onServerSwitched(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        executor.execute(() -> player.getCurrentServer().ifPresent(server -> {
            Party party = manager.partyOf(player.getUniqueId()).orElse(null);
            bridge.sendSnapshot(player, party);
            chat.serverSwitched(player);
            if (party != null && party.isLeader(player.getUniqueId())) { // skip the manager's own lookup for everyone else
                manager.leaderSwitchedServer(player.getUniqueId(), server.getServerInfo().getName());
            }
        }));
    }
}
