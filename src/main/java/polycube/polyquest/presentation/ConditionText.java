package polycube.polyquest.presentation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.Advancement;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import polycube.polyquest.condition.BuiltInConditions;
import polycube.polyquest.condition.CompositeConditions;
import polycube.polyquest.condition.ConditionApi;

import static polycube.polycore.text.TextCore.humanize;
import static polycube.polycore.text.TextUtil.itemTarget;

/// Gives atomic objectives a readable label while leaving Minecraft's predicates authoritative.
public final class ConditionText {
    private ConditionText() {}

    public static Component summary(MinecraftServer server, ConditionApi.Definition condition) {
        return switch (condition) {
            case CompositeConditions.AllOf value when value.children().size() == 1 -> summary(server, value.children().getFirst());
            case CompositeConditions.AllOf value -> Component.literal("Complete all " + value.children().size() + " objectives");
            case CompositeConditions.AnyOf value when value.children().size() == 1 -> summary(server, value.children().getFirst());
            case CompositeConditions.AnyOf value -> Component.literal("Complete any one of " + value.children().size() + " objectives");
            case CompositeConditions.Repeat value -> Component.literal("Repeat ").append(summary(server, value.child())).append(" " + value.times() + " times");
            case CompositeConditions.Sequence value -> Component.literal("Complete " + value.children().size() + " objectives in order");
            case CompositeConditions.TimeWindow value -> Component.literal("Timed: ").append(summary(server, value.child()));
            case CompositeConditions.NOfM value -> Component.literal("Complete " + value.required() + " of " + value.children().size() + " objectives");
            case CompositeConditions.OptionalChild value -> Component.literal("Optional: ").append(summary(server, value.child()));
            case CompositeConditions.Choice value -> Component.literal("Choose one of " + value.branches().size() + " paths");
            case BuiltInConditions.AdvancementCriterion value -> value.displayName().<Component>map(Component::literal).orElseGet(() -> criterion(server, value.criterion()));
            case BuiltInConditions.ConsumeItems value -> value.displayName().map(Component::literal).orElseGet(() -> Component.literal("Turn in " + value.count() + "× " + itemTarget(value.item())));
            case BuiltInConditions.LootItem value -> value.displayName().map(Component::literal).orElseGet(() -> Component.literal(lootItem(value)));
            case BuiltInConditions.ObtainAdvancement value -> advancement(server, value.advancement());
            case BuiltInConditions.ExplicitSignal value when value.count() > 1 -> Component.literal("Trigger " + humanize(value.signal()) + " " + value.count() + " times");
            case BuiltInConditions.ExplicitSignal value -> Component.literal("Trigger " + humanize(value.signal()));
            default -> Component.literal(humanize(condition.type().id()));
        };
    }

    private static Component advancement(MinecraftServer server, Identifier id) {
        var holder = server.getAdvancements().get(id);
        if (holder == null) {
            return Component.literal("Complete ")
                    .append(Component.literal("[" + id + "]").withStyle(ChatFormatting.GRAY))
                    .append(" (advancement not found)");
        }
        if (holder.value().display().isEmpty()) {
            return Component.literal("Complete ")
                    .append(Component.literal("[" + id + "]").withStyle(ChatFormatting.GRAY));
        }
        // Vanilla supplies the title's frame color, brackets, and description hover.
        return Component.literal("Complete ").append(Advancement.name(holder));
    }

    private static Component criterion(MinecraftServer server, Criterion<?> criterion) {
        Identifier trigger = BuiltInRegistries.TRIGGER_TYPES.getKey(criterion.trigger());
        if (trigger == null) return Component.literal("Complete an advancement criterion");

        // Encode through the same registry-aware codec used by datapacks, so named and tagged
        // predicates are read from their actual definition rather than guessed from Java types.
        JsonObject conditions = Criterion.CODEC
                .encodeStart(server.registryAccess().createSerializationContext(JsonOps.INSTANCE), criterion)
                .result()
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .filter(json -> json.has("conditions") && json.get("conditions").isJsonObject())
                .map(json -> json.getAsJsonObject("conditions"))
                .orElseGet(JsonObject::new);

        return criterionLabel(trigger, conditions);
    }

    /// Keeps the compact label separate from codec serialization for focused predicate tests.
    static Component criterionLabel(Identifier trigger, JsonObject conditions) {
        if (!trigger.getNamespace().equals("minecraft")) {
            return Component.literal("Complete " + humanize(trigger) + " event")
                    .append(conditions.isEmpty() ? "" : " with matching conditions");
        }
        return switch (trigger.getPath()) {
            case "player_killed_entity" -> Component.literal("Kill ").append(entityTarget(conditions, "entity"));
            case "entity_killed_player" -> Component.literal("Be killed by ").append(entityTarget(conditions, "entity"));
            case "inventory_changed" -> !conditions.has("items")
                    ? Component.literal("Change inventory to the required state")
                    : conditions.get("items").isJsonArray() && conditions.getAsJsonArray("items").size() > 1
                            ? Component.literal("Have all required items")
                            : Component.literal("Have ").append(criterionItemTarget(conditions, "items"));
            case "fishing_rod_hooked" -> conditions.has("item")
                    ? Component.literal("Catch ").append(criterionItemTarget(conditions, "item"))
                    : conditions.has("entity")
                            ? Component.literal("Hook ").append(entityTarget(conditions, "entity"))
                            : Component.literal("Use a fishing rod");
            case "consume_item" -> Component.literal("Consume ").append(criterionItemTarget(conditions, "item"));
            case "using_item" -> Component.literal("Use ").append(criterionItemTarget(conditions, "item"));
            case "placed_block" -> Component.literal("Place ").append(blockTarget(conditions, "location"));
            case "enter_block" -> Component.literal("Enter ").append(blockTarget(conditions, "block"));
            case "location" -> Component.literal("Visit a matching location");
            case "fall_from_height" -> Component.literal("Fall a matching distance");
            case "slept_in_bed" -> Component.literal("Sleep in a bed");
            case "changed_dimension" -> Component.literal("Travel between dimensions");
            case "recipe_unlocked" -> Component.literal("Unlock a matching recipe");
            default -> Component.literal("Complete " + humanize(trigger) + " event").append(conditions.isEmpty() ? "" : " with matching conditions");
        };
    }

    private static Component entityTarget(JsonObject conditions, String field) {
        JsonElement predicate = conditions.get(field);
        if (predicate == null) return Component.literal("an entity");
        Identifier id = firstIdentifier(predicate);
        if (id == null) id = findIdentifier(predicate, "type");
        if (id == null) return Component.literal("a matching entity");
        Identifier target = id;
        return BuiltInRegistries.ENTITY_TYPE.getOptional(target)
                .map(type -> type.getDescription().copy())
                .orElseGet(() -> Component.literal(humanize(target)));
    }

    private static Component criterionItemTarget(JsonObject conditions, String field) {
        JsonElement predicate = conditions.get(field);
        if (predicate == null) return Component.literal("a matching item");
        Identifier id = firstIdentifier(predicate);
        if (id == null) id = findIdentifier(predicate, "items");
        if (id == null) return Component.literal("a matching item");
        Identifier target = id;
        return BuiltInRegistries.ITEM.getOptional(target)
                .map(item -> new ItemStack(item).getHoverName().copy())
                .orElseGet(() -> Component.literal(humanize(target)));
    }

    private static Component blockTarget(JsonObject conditions, String field) {
        JsonElement predicate = conditions.get(field);
        if (predicate == null) return Component.literal("a matching block");
        Identifier id = firstIdentifier(predicate);
        if (id == null) id = findIdentifier(predicate, "blocks");
        if (id == null) id = findIdentifier(predicate, "block");
        if (id == null) return Component.literal("a matching block");
        Identifier target = id;
        return BuiltInRegistries.BLOCK.getOptional(target)
                .map(block -> block.getName().copy())
                .orElseGet(() -> Component.literal(humanize(target)));
    }

    /// Finds a concrete identifier in a predicate, including Minecraft's nested loot conditions.
    private static @Nullable Identifier findIdentifier(JsonElement element, String field) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has(field)) {
                Identifier direct = firstIdentifier(object.get(field));
                if (direct != null) return direct;
            }
            for (var entry : object.entrySet()) {
                Identifier nested = findIdentifier(entry.getValue(), field);
                if (nested != null) return nested;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                Identifier nested = findIdentifier(child, field);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static @Nullable Identifier firstIdentifier(JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString();
            return value.startsWith("#") ? null : Identifier.tryParse(value);
        }
        if (element.isJsonArray() && element.getAsJsonArray().size() == 1) {
            return firstIdentifier(element.getAsJsonArray().get(0));
        }
        return null;
    }

    private static String lootItem(BuiltInConditions.LootItem condition) {
        String source = condition.entity().isPresent() ? " from a matching entity" : condition.block().isPresent() ? " from a matching block" : " from loot";
        String repetitions = condition.count() > 1 ? " " + condition.count() + " times" : "";
        return "Loot " + itemTarget(condition.item()) + source + repetitions;
    }
}
