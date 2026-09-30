package dev.partiesandchill.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * Static access to the {@link NetworkPartiesAPI}, for plugins that don't want to go through Bukkit's
 * {@code ServicesManager}. Both return the same instance.
 */
public final class NetworkParties {

    private static volatile NetworkPartiesAPI api;

    private NetworkParties() {
    }

    /**
     * @return the API
     * @throws IllegalStateException if the Parties &amp; Chill bridge is not enabled on this server (declare
     *                               {@code depend: [PartiesAndChillBridge]} in your plugin.yml so it enables first)
     */
    public static NetworkPartiesAPI getAPI() {
        NetworkPartiesAPI current = api;
        if (current == null) {
            throw new IllegalStateException("Parties & Chill is not enabled: add 'depend: [PartiesAndChillBridge]' to your plugin.yml");
        }
        return current;
    }

    /** Called by the Parties &amp; Chill bridge when it enables. */
    @ApiStatus.Internal
    public static void register(NetworkPartiesAPI instance) {
        if (instance == null) throw new NullPointerException("instance");
        api = instance;
    }

    /** Called by the Parties &amp; Chill bridge when it disables. */
    @ApiStatus.Internal
    public static void unregister() {
        api = null;
    }
}
