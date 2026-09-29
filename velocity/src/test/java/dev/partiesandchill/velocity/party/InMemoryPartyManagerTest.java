package dev.partiesandchill.velocity.party;

class InMemoryPartyManagerTest extends PartyManagerContract {

    @Override
    PartyStore newStore() {
        return new InMemoryPartyStore();
    }
}
