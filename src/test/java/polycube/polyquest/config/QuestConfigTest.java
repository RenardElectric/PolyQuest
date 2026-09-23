package polycube.polyquest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import polycube.polyquest.model.QuestModel;

final class QuestConfigTest {
    @Test
    void codecRoundTripsImmutableConfiguration() {
        QuestConfig expected = new QuestConfig(42L, ZoneId.of("UTC"), Map.of(QuestModel.Difficulty.EASY, 2));

        var encoded = QuestConfig.CODEC.encodeStart(JsonOps.INSTANCE, expected).getOrThrow();
        var decoded = QuestConfig.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(expected, decoded);
        assertEquals(2, decoded.rotationHours(QuestModel.Difficulty.EASY));
        assertEquals(24, decoded.rotationHours(QuestModel.Difficulty.MEDIUM));
        assertEquals(48, decoded.rotationHours(QuestModel.Difficulty.HARD));
        assertFalse(encoded.getAsJsonObject().has("announceRotation"));
    }

    @Test
    void codecRejectsInvalidNullnessFreeValues() {
        var invalidZone = QuestConfig.CODEC.parse(
                JsonOps.INSTANCE,
                JsonParser.parseString("{\"dailySeed\":1,\"timeZone\":\"Not/A_Zone\"}"));
        var invalidInterval = QuestConfig.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"dailySeed\":1,\"timeZone\":\"UTC\",\"rotationHours\":{\"easy\":5}}"));

        assertTrue(invalidZone.error().isPresent());
        assertTrue(invalidInterval.error().isPresent());
    }
}
