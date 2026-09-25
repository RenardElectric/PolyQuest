package polycube.polyquest.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.predicates.BlockPredicate;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.resources.Identifier;
import polycube.polyquest.PolyQuest;

import java.util.Optional;

import static polycube.polyquest.condition.ConditionApi.Capabilities.CLAIM_TIME_COST;
import static polycube.polyquest.condition.ConditionApi.Capabilities.SIGNAL_DRIVEN;
import static polycube.polyquest.condition.ConditionApi.Semantics.constant;

/// Datapack definitions for PolyQuest's vanilla-predicate-backed atomic conditions.
/// Mutable player state is kept separately in {@link BuiltInConditionRuntime}.
public final class BuiltInConditions {
    private static final Codec<String> DISPLAY_NAME_CODEC = Codec.STRING.validate(name ->
            name.isBlank() ? DataResult.error(() -> "Condition display_name cannot be blank") : DataResult.success(name));

    /// Uses any criterion trigger registered in Minecraft's advancement system.
    public record AdvancementCriterion(Criterion<?> criterion, Optional<String> displayName) implements ConditionApi.Definition {
        public AdvancementCriterion(Criterion<?> criterion) {
            this(criterion, Optional.empty());
        }

        public static final MapCodec<AdvancementCriterion> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                MapCodec.assumeMapUnsafe(Criterion.CODEC).forGetter(AdvancementCriterion::criterion),
                DISPLAY_NAME_CODEC.optionalFieldOf("display_name").forGetter(AdvancementCriterion::displayName)
        ).apply(instance, AdvancementCriterion::new));
        public static final ConditionApi.Type<AdvancementCriterion> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("advancement_criterion"), CODEC,
                BuiltInConditionRuntime.AdvancementCriterionInstance::new, constant(SIGNAL_DRIVEN));

        @Override
        public ConditionApi.Type<AdvancementCriterion> type() {
            return TYPE;
        }
    }

    /// Consumes a specified number of items from the player's inventory.
    public record ConsumeItems(ItemPredicate item, int count, Optional<String> displayName) implements ConditionApi.Definition {
        public ConsumeItems(ItemPredicate item, int count) {
            this(item, count, Optional.empty());
        }

        public static final MapCodec<ConsumeItems> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ItemPredicate.CODEC.fieldOf("item").forGetter(ConsumeItems::item),
                Codec.INT.fieldOf("count").forGetter(ConsumeItems::count),
                DISPLAY_NAME_CODEC.optionalFieldOf("display_name").forGetter(ConsumeItems::displayName)
        ).apply(instance, ConsumeItems::new));
        public static final ConditionApi.Type<ConsumeItems> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("consume_items"), CODEC,
                BuiltInConditionRuntime.ConsumeItemsInstance::new, constant(CLAIM_TIME_COST));

        @Override
        public ConditionApi.Type<ConsumeItems> type() {
            return TYPE;
        }
    }

    /// Counts matching generated loot from an entity, fishing, a broken block, or a block container.
    public record LootItem(ItemPredicate item, Optional<EntityPredicate> entity, Optional<BlockPredicate> block, int count, Optional<String> displayName) implements ConditionApi.Definition {
        public LootItem(ItemPredicate item, Optional<EntityPredicate> entity, int count) {
            this(item, entity, Optional.empty(), count, Optional.empty());
        }

        public static final MapCodec<LootItem> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ItemPredicate.CODEC.fieldOf("item").forGetter(LootItem::item),
                EntityPredicate.CODEC.optionalFieldOf("entity").forGetter(LootItem::entity),
                BlockPredicate.CODEC.optionalFieldOf("block").forGetter(LootItem::block),
                Codec.INT.optionalFieldOf("count", 1).forGetter(LootItem::count),
                DISPLAY_NAME_CODEC.optionalFieldOf("display_name").forGetter(LootItem::displayName)
        ).apply(instance, LootItem::new));
        public static final ConditionApi.Type<LootItem> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("loot_item"), CODEC,
                BuiltInConditionRuntime.LootItemInstance::new, constant(SIGNAL_DRIVEN));

        @Override
        public ConditionApi.Type<LootItem> type() {
            return TYPE;
        }
    }

    /// Triggers when the player obtains a specified advancement.
    public record ObtainAdvancement(Identifier advancement) implements ConditionApi.Definition {
        public static final MapCodec<ObtainAdvancement> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Identifier.CODEC.fieldOf("advancement").forGetter(ObtainAdvancement::advancement)
        ).apply(instance, ObtainAdvancement::new));
        public static final ConditionApi.Type<ObtainAdvancement> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("obtain_advancement"), CODEC,
                BuiltInConditionRuntime.ObtainAdvancementInstance::new, constant(SIGNAL_DRIVEN));

        @Override
        public ConditionApi.Type<ObtainAdvancement> type() {
            return TYPE;
        }
    }

    /// Counts explicit signals emitted through the PolyQuest API or administrator command.
    public record ExplicitSignal(Identifier signal, int count) implements ConditionApi.Definition {
        public static final MapCodec<ExplicitSignal> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Identifier.CODEC.fieldOf("signal").forGetter(ExplicitSignal::signal),
                Codec.INT.optionalFieldOf("count", 1).forGetter(ExplicitSignal::count)
        ).apply(instance, ExplicitSignal::new));
        public static final ConditionApi.Type<ExplicitSignal> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("explicit_signal"), CODEC,
                BuiltInConditionRuntime.ExplicitSignalInstance::new, constant(SIGNAL_DRIVEN));

        @Override
        public ConditionApi.Type<ExplicitSignal> type() {
            return TYPE;
        }
    }

    public static void register() {
        ConditionApi.register(AdvancementCriterion.TYPE);
        ConditionApi.register(ConsumeItems.TYPE);
        ConditionApi.register(LootItem.TYPE);
        ConditionApi.register(ObtainAdvancement.TYPE);
        ConditionApi.register(ExplicitSignal.TYPE);
    }

    private BuiltInConditions() {}
}
