package polycube.polyquest.runtime;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CriterionMatchBatchesTest {
    @Test
    void nestedTriggersPreserveOuterMatchesAndEmitOnlyOnce() {
        CriterionMatchBatches batches = new CriterionMatchBatches();
        UUID player = UUID.randomUUID();
        Identifier outer = Identifier.fromNamespaceAndPath("polyquest_test", "outer");
        Identifier inner = Identifier.fromNamespaceAndPath("polyquest_test", "inner");

        batches.begin(player);
        assertTrue(batches.add(player, outer));
        batches.begin(player);
        assertTrue(batches.add(player, inner));
        assertEquals(Set.of(), batches.end(player));
        assertTrue(batches.isActive(player));
        assertEquals(Set.of(outer, inner), batches.end(player));
        assertFalse(batches.isActive(player));
        assertFalse(batches.add(player, outer));
    }

    @Test
    void disconnectDropsOnlyThatPlayersUnfinishedBatch() {
        CriterionMatchBatches batches = new CriterionMatchBatches();
        UUID disconnected = UUID.randomUUID();
        UUID connected = UUID.randomUUID();
        Identifier match = Identifier.fromNamespaceAndPath("polyquest_test", "match");

        batches.begin(disconnected);
        batches.begin(connected);
        batches.add(disconnected, match);
        batches.add(connected, match);
        batches.discard(disconnected);

        assertEquals(Set.of(), batches.end(disconnected));
        assertEquals(Set.of(match), batches.end(connected));
    }
}
