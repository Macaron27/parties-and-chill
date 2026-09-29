package dev.partiesandchill.velocity.listener;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import dev.partiesandchill.velocity.bridge.BridgeChannel;
import dev.partiesandchill.velocity.chat.PartyChat;

import java.util.concurrent.ExecutorService;

/**
 * Chat lock for clients older than 1.19.1, whose chat is unsigned and can be cancelled on the proxy.
 * Newer clients are diverted by the backend bridge instead (see {@link BridgeChannel#needsBackendForChatLock}).
 */
public final class ChatListener {

    private final PartyChat chat;
    private final ExecutorService executor;

    public ChatListener(PartyChat chat, ExecutorService executor) {
        this.chat = chat;
        this.executor = executor;
    }

    @Subscribe(order = PostOrder.EARLY)
    @SuppressWarnings("deprecation") // denying is only unsafe for signed (1.19.1+) chat, which is excluded here
    public void onChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!event.getResult().isAllowed() || !chat.isLocked(player.getUniqueId())
                || BridgeChannel.needsBackendForChatLock(player)) return;
        event.setResult(PlayerChatEvent.ChatResult.denied());
        String message = event.getMessage();
        executor.execute(() -> chat.send(player, message, 0));
    }
}
