package dev.partiesandchill.velocity.redis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.partiesandchill.velocity.party.Party;
import dev.partiesandchill.velocity.party.PartyEvent;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** JSON forms of parties (stored in Redis) and events (sent over pub/sub). */
final class Json {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Event types by simple name, derived from the sealed hierarchy so new events need no registration. */
    private static final Map<String, Class<?>> EVENT_TYPES = Arrays.stream(PartyEvent.class.getPermittedSubclasses())
            .collect(Collectors.toUnmodifiableMap(Class::getSimpleName, Function.identity()));

    private Json() {
    }

    static String party(Party party) {
        return GSON.toJson(party);
    }

    static Party party(String json) {
        return GSON.fromJson(json, Party.class);
    }

    static String event(PartyEvent event) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", event.getClass().getSimpleName());
        envelope.add("data", GSON.toJsonTree(event));
        return GSON.toJson(envelope);
    }

    /** @throws IllegalArgumentException for unknown or malformed payloads */
    static PartyEvent event(String json) {
        JsonObject envelope = JsonParser.parseString(json).getAsJsonObject();
        Class<?> type = EVENT_TYPES.get(envelope.get("type").getAsString());
        if (type == null) throw new IllegalArgumentException("unknown event type in " + json);
        return (PartyEvent) GSON.fromJson(envelope.get("data"), type);
    }
}
