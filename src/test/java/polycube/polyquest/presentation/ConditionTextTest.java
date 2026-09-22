package polycube.polyquest.presentation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ConditionTextTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void namesNestedEntityPredicateInsteadOfShowingTriggerId() {
        JsonObject conditions = JsonParser.parseString("""
                {"entity":{"condition":"minecraft:entity_properties","predicate":{"type":"test:moon_beast"}}}
                """).getAsJsonObject();

        assertEquals("Kill Moon Beast", ConditionText.criterionLabel(
                Identifier.parse("minecraft:player_killed_entity"), conditions).getString());
    }

    @Test
    void doesNotPretendAnInventoryCriterionWithManyItemsNeedsOnlyTheFirst() {
        JsonObject conditions = JsonParser.parseString("""
                {"items":[{"items":"test:moon_berry"},{"items":"test:star_berry"}]}
                """).getAsJsonObject();

        assertEquals("Have all required items", ConditionText.criterionLabel(
                Identifier.parse("minecraft:inventory_changed"), conditions).getString());
    }

    @Test
    void namesDirectBlockPredicate() {
        JsonObject conditions = JsonParser.parseString("{\"block\":\"test:moon_stone\"}").getAsJsonObject();

        assertEquals("Enter Moon Stone", ConditionText.criterionLabel(
                Identifier.parse("minecraft:enter_block"), conditions).getString());
    }

    @Test
    void unknownTriggerKeepsAReadableActionAndSignalsExtraFilters() {
        JsonObject conditions = JsonParser.parseString("{\"custom_filter\":true}").getAsJsonObject();

        assertEquals("Complete Moon Ritual event with matching conditions", ConditionText.criterionLabel(
                Identifier.parse("test:moon_ritual"), conditions).getString());
    }
}
