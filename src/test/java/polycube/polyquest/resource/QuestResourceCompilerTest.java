package polycube.polyquest.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import polycube.polyquest.condition.BuiltInConditions;
import polycube.polyquest.condition.ConditionApi;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.BuiltInRewards;
import polycube.polyquest.reward.RewardApi;

final class QuestResourceCompilerTest {
    @BeforeAll
    static void registerTestedTypes() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ConditionApi.register(BuiltInConditions.ExplicitSignal.TYPE);
        RewardApi.register(BuiltInRewards.Experience.TYPE);
    }

    @Test
    void emptyResourceSetProducesEmptyCatalog() {
        QuestResourceCompiler.ResourceSet resources = new QuestResourceCompiler.ResourceSet(
                Map.of(), Map.of(), Map.of());

        var catalog = new QuestResourceCompiler().compile(resources, JsonOps.INSTANCE).getOrThrow();

        assertEquals(QuestModel.Catalog.EMPTY, catalog);
    }

    @Test
    void malformedRootsBecomeCompilationErrorsInsteadOfEscapingAsExceptions() {
        Identifier malformed = Identifier.fromNamespaceAndPath("polyquest_test", "malformed");
        QuestResourceCompiler.ResourceSet resources = new QuestResourceCompiler.ResourceSet(
                Map.of(malformed, new JsonArray()),
                Map.of(malformed, new JsonArray()),
                Map.of(malformed, new JsonArray()));

        var result = new QuestResourceCompiler().compile(resources, JsonOps.INSTANCE);

        assertTrue(result.error().isPresent());
        String message = result.error().orElseThrow().message();
        assertTrue(message.contains("object root"));
    }

    @Test
    void undeclaredTemplatePlaceholderRejectsCatalog() {
        QuestResourceCompiler.ResourceSet resources = new QuestResourceCompiler.ResourceSet(
                Map.of(Identifier.fromNamespaceAndPath("test", "quest"), JsonParser.parseString("""
                        {
                          "template": "test:template",
                          "arguments": { "declared": "value" }
                        }
                        """)),
                Map.of(Identifier.fromNamespaceAndPath("test", "template"), JsonParser.parseString("""
                        {
                          "parameters": ["declared"],
                          "prototype": { "title": "${undeclared}" }
                        }
                        """)),
                Map.of());

        var result = new QuestResourceCompiler().compile(resources, JsonOps.INSTANCE);

        assertTrue(result.error().orElseThrow().message()
                .contains("unknown template parameter 'undeclared'"));
    }

    @Test
    void templateExpansionPreservesJsonTypesAndInterpolatesText() {
        Identifier questId = Identifier.fromNamespaceAndPath("test", "templated");
        QuestResourceCompiler.ResourceSet resources = new QuestResourceCompiler.ResourceSet(
                Map.of(questId, JsonParser.parseString("""
                        {
                          "template": "test:template",
                          "arguments": {
                            "label": "the beacon",
                            "signal": "test:complete",
                            "points": 3
                          }
                        }
                        """)),
                Map.of(Identifier.fromNamespaceAndPath("test", "template"), JsonParser.parseString("""
                        {
                          "parameters": ["label", "signal", "points"],
                          "prototype": {
                            "availability": "unique",
                            "title": "Complete ${label}",
                            "icon": "minecraft:beacon",
                            "condition": {
                              "type": "polyquest:explicit_signal",
                              "signal": "${signal}"
                            },
                            "rewards": {
                              "rewards": [
                                { "type": "polyquest:experience", "points": "${points}" }
                              ]
                            }
                          }
                        }
                        """)),
                Map.of());

        QuestModel.Catalog catalog = new QuestResourceCompiler()
                .compile(resources, JsonOps.INSTANCE)
                .getOrThrow();

        assertEquals("Complete the beacon",
                Objects.requireNonNull(catalog.quests().get(questId)).title());
    }

    @Test
    void compilesRegisteredConditionAndRewardIntoCatalog() {
        Identifier questId = Identifier.fromNamespaceAndPath("test", "signal");
        QuestResourceCompiler.ResourceSet resources = new QuestResourceCompiler.ResourceSet(
                Map.of(questId, JsonParser.parseString("""
                        {
                          "availability": "unique",
                          "title": "Signal test",
                          "icon": "minecraft:beacon",
                          "condition": {
                            "type": "polyquest:explicit_signal",
                            "signal": "test:complete"
                          },
                          "rewards": {
                            "rewards": [
                              { "type": "polyquest:experience", "points": 1 }
                            ]
                          }
                        }
                        """)),
                Map.of(),
                Map.of());

        QuestModel.Catalog catalog = new QuestResourceCompiler()
                .compile(resources, JsonOps.INSTANCE)
                .getOrThrow();

        assertTrue(catalog.quests().containsKey(questId));
        QuestModel.Definition quest = Objects.requireNonNull(catalog.quests().get(questId));
        assertEquals("d9692ca68241ac0a62a547fc7bf268bf663727125fc8b6b78163d49bf81be9f8", quest.behaviorHash());
    }
}
