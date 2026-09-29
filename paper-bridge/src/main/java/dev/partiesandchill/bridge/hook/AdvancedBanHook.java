package dev.partiesandchill.bridge.hook;

import dev.partiesandchill.bridge.BridgePlugin;
import me.leoko.advancedban.bukkit.event.PunishmentEvent;
import me.leoko.advancedban.bukkit.event.RevokePunishmentEvent;
import me.leoko.advancedban.manager.PunishmentManager;
import me.leoko.advancedban.manager.UUIDManager;
import me.leoko.advancedban.utils.Punishment;
import me.leoko.advancedban.utils.PunishmentType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/** Reads mutes from AdvancedBan (runs on backends only; it has no Velocity version) and reports changes. */
public final class AdvancedBanHook implements Listener {

    private final BridgePlugin plugin;

    public AdvancedBanHook(BridgePlugin plugin) {
        this.plugin = plugin;
    }

    /** @return epoch millis the player's mute ends: {@code 0} if not muted, {@link Long#MAX_VALUE} if permanent */
    public long mutedUntil(Player player) {
        Punishment mute = PunishmentManager.get().getMute(UUIDManager.get().getUUID(player.getName()));
        return mute == null ? 0 : until(mute);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPunish(PunishmentEvent event) {
        report(event.getPunishment(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRevoke(RevokePunishmentEvent event) {
        report(event.getPunishment(), true);
    }

    private void report(Punishment punishment, boolean revoked) {
        if (punishment.getType().getBasic() != PunishmentType.MUTE) return;
        UUID player = parseUuid(punishment.getUuid());
        if (player != null) plugin.reportMute(player, revoked ? 0 : until(punishment));
    }

    private static long until(Punishment mute) {
        return mute.getType().isTemp() ? mute.getEnd() : Long.MAX_VALUE;
    }

    /**
     * AdvancedBan stores UUIDs without dashes (and IPs for IP punishments).
     *
     * @return the UUID, or {@code null} if {@code raw} isn't one
     */
    static UUID parseUuid(String raw) {
        if (raw == null) return null;
        String hex = raw.replace("-", "");
        if (!hex.matches("[0-9a-fA-F]{32}")) return null;
        return UUID.fromString(hex.substring(0, 8) + '-' + hex.substring(8, 12) + '-' + hex.substring(12, 16) + '-'
                + hex.substring(16, 20) + '-' + hex.substring(20));
    }
}
