package dev.partiesandchill.velocity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginDescriptorTest {

    /** {@code @Plugin(version)} must be a constant, so it can't read the Gradle version: this keeps them in step. */
    @Test
    void descriptorMatchesTheBuildVersion() throws Exception {
        try (Reader json = new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/velocity-plugin.json"), "velocity-plugin.json"), StandardCharsets.UTF_8)) {
            JsonObject descriptor = JsonParser.parseReader(json).getAsJsonObject();
            assertEquals(System.getProperty("pnc.version"), descriptor.get("version").getAsString());
            assertEquals(PartiesAndChill.class.getName(), descriptor.get("main").getAsString());
        }
    }
}
