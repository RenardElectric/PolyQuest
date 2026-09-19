package polycube.polyquest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

final class QuestConfigTest {
    @Test
    void codecRoundTripsImmutableConfiguration() {
        QuestConfig expected = new QuestConfig(42L, ZoneId.of("UTC"), 12);

        var encoded = QuestConfig.CODEC.encodeStart(JsonOps.INSTANCE, expected).getOrThrow();
        var decoded = QuestConfig.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(expected, decoded);
        assertFalse(encoded.getAsJsonObject().has("announceRotation"));
    }

    @Test
    void codecRejectsInvalidNullnessFreeValues() {
        var invalidRetry = QuestConfig.CODEC.parse(
                JsonOps.INSTANCE,
                JsonParser.parseString("{\"pendingRewardRetrySeconds\":0}"));
        var invalidZone = QuestConfig.CODEC.parse(
                JsonOps.INSTANCE,
                JsonParser.parseString("{\"timeZone\":\"Not/A_Zone\"}"));

        assertTrue(invalidRetry.error().isPresent());
        assertTrue(invalidZone.error().isPresent());
        assertThrows(IllegalArgumentException.class,
                () -> new QuestConfig(0L, ZoneId.of("UTC"), 0));
    }
}
