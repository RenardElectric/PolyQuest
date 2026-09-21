package polycube.polyquest.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
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
        ConditionApi.register(BuiltInConditions.AdvancementCriterion.TYPE);
        ConditionApi.register(BuiltInConditions.ExplicitSignal.TYPE);
        ConditionApi.register(BuiltInConditions.LootItem.TYPE);
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

    @Test
    void compilesVanillaAdvancementCriterionThroughMinecraftCodec() {
        Identifier questId = Identifier.fromNamespaceAndPath("test", "vanilla_criterion");
        QuestResourceCompiler.ResourceSet resources = new QuestResourceCompiler.ResourceSet(
                Map.of(questId, JsonParser.parseString("""
                        {
                          "availability": "unique",
                          "title": "Vanilla criterion test",
                          "icon": "minecraft:beacon",
                          "condition": {
                            "type": "polyquest:advancement_criterion",
                            "trigger": "minecraft:tick",
                            "conditions": {}
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

        BuiltInConditions.AdvancementCriterion condition = assertInstanceOf(
                BuiltInConditions.AdvancementCriterion.class,
                Objects.requireNonNull(catalog.quests().get(questId)).condition());
        assertSame(CriteriaTriggers.TICK, condition.criterion().trigger());
    }

    @Test
    void compilesLootItemWithAnEntityPredicateAndDefaultCount() {
        Identifier questId = Identifier.fromNamespaceAndPath("test", "entity_loot");
        QuestResourceCompiler.ResourceSet resources = new QuestResourceCompiler.ResourceSet(
                Map.of(questId, JsonParser.parseString("""
                        {
                          "availability": "unique",
                          "title": "Entity loot test",
                          "icon": "minecraft:iron_ingot",
                          "condition": {
                            "type": "polyquest:loot_item",
                            "item": {
                              "items": "minecraft:iron_ingot"
                            },
                            "entity": {
                              "minecraft:entity_type": "minecraft:zombie"
                            }
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
                .compile(
                        resources,
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
                                .createSerializationContext(JsonOps.INSTANCE))
                .getOrThrow();

        BuiltInConditions.LootItem condition = assertInstanceOf(
                BuiltInConditions.LootItem.class,
                Objects.requireNonNull(catalog.quests().get(questId)).condition());
        assertTrue(condition.entity().isPresent());
        assertEquals(1, condition.count());
    }
}
