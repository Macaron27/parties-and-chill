package dev.partiesandchill.bridge;

/** Exposes package-private cache feeding to tests in other packages. */
public final class TestAccess {

    private TestAccess() {
    }

    public static PartyCache cacheWith(String... hexMessages) {
        PartyCache cache = new PartyCache();
        for (String hex : hexMessages) cache.apply(BridgeProtocol.decode(BridgeProtocolTest.bytes(hex)));
        return cache;
    }
}
