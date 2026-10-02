package dev.partiesandchill.core.party;

class InMemoryPartyManagerTest extends PartyManagerContract {

    @Override
    PartyStore newStore() {
        return new InMemoryPartyStore();
    }
}
