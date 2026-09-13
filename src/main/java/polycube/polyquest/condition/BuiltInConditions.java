package polycube.polyquest.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.predicates.BlockPredicate;
import net.minecraft.advancements.predicates.DamageSourcePredicate;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.resources.Identifier;
import polycube.polyquest.PolyQuest;

/// Datapack definitions for PolyQuest's vanilla-predicate-backed atomic conditions.
/// Mutable player state is kept separately in {@link BuiltInConditionRuntime}.
public final class BuiltInConditions {
    public record ConsumeItems(ItemPredicate item, int count) implements ConditionApi.Definition {
        public static final MapCodec<ConsumeItems> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ItemPredicate.CODEC.fieldOf("item").forGetter(ConsumeItems::item),
                Codec.INT.fieldOf("count").forGetter(ConsumeItems::count)
        ).apply(instance, ConsumeItems::new));
        public static final ConditionApi.Type<ConsumeItems> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("consume_items"), CODEC, BuiltInConditionRuntime.ConsumeItemsInstance::new);

        @Override
        public ConditionApi.Type<ConsumeItems> type() {
            return TYPE;
        }
    }

    public record FishItem(ItemPredicate item, int count) implements ConditionApi.Definition {
        public static final MapCodec<FishItem> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ItemPredicate.CODEC.fieldOf("item").forGetter(FishItem::item),
                Codec.INT.optionalFieldOf("count", 1).forGetter(FishItem::count)
        ).apply(instance, FishItem::new));
        public static final ConditionApi.Type<FishItem> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("fish_item"), CODEC, BuiltInConditionRuntime.FishItemInstance::new);

        @Override
        public ConditionApi.Type<FishItem> type() {
            return TYPE;
        }
    }

    public record KillEntity(
            EntityPredicate victim,
            Optional<DamageSourcePredicate> damageSource,
            int count) implements ConditionApi.Definition {
        public static final MapCodec<KillEntity> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                EntityPredicate.CODEC.fieldOf("victim").forGetter(KillEntity::victim),
                DamageSourcePredicate.CODEC.optionalFieldOf("damage_source").forGetter(KillEntity::damageSource),
                Codec.INT.optionalFieldOf("count", 1).forGetter(KillEntity::count)
        ).apply(instance, KillEntity::new));
        public static final ConditionApi.Type<KillEntity> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("kill_entity"), CODEC, BuiltInConditionRuntime.KillEntityInstance::new);

        @Override
        public ConditionApi.Type<KillEntity> type() {
            return TYPE;
        }
    }

    public record BreakBlock(
            BlockPredicate block,
            Optional<ItemPredicate> tool,
            int count) implements ConditionApi.Definition {
        public static final MapCodec<BreakBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                BlockPredicate.CODEC.fieldOf("block").forGetter(BreakBlock::block),
                ItemPredicate.CODEC.optionalFieldOf("tool").forGetter(BreakBlock::tool),
                Codec.INT.optionalFieldOf("count", 1).forGetter(BreakBlock::count)
        ).apply(instance, BreakBlock::new));
        public static final ConditionApi.Type<BreakBlock> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("break_block"), CODEC, BuiltInConditionRuntime.BreakBlockInstance::new);

        @Override
        public ConditionApi.Type<BreakBlock> type() {
            return TYPE;
        }
    }

    public record VisitLocation(LocationPredicate location, int continuousTicks)
            implements ConditionApi.Definition {
        public static final MapCodec<VisitLocation> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                LocationPredicate.CODEC.fieldOf("location").forGetter(VisitLocation::location),
                Codec.INT.optionalFieldOf("continuous_ticks", 1).forGetter(VisitLocation::continuousTicks)
        ).apply(instance, VisitLocation::new));
        public static final ConditionApi.Type<VisitLocation> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("visit_location"), CODEC, BuiltInConditionRuntime.VisitLocationInstance::new);

        @Override
        public ConditionApi.Type<VisitLocation> type() {
            return TYPE;
        }
    }

    public record PlayerDeath(Optional<DamageSourcePredicate> damageSource)
            implements ConditionApi.Definition {
        public static final MapCodec<PlayerDeath> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                DamageSourcePredicate.CODEC.optionalFieldOf("damage_source").forGetter(PlayerDeath::damageSource)
        ).apply(instance, PlayerDeath::new));
        public static final ConditionApi.Type<PlayerDeath> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("player_death"), CODEC, BuiltInConditionRuntime.PlayerDeathInstance::new);

        @Override
        public ConditionApi.Type<PlayerDeath> type() {
            return TYPE;
        }
    }

    public record ObtainAdvancement(Identifier advancement) implements ConditionApi.Definition {
        public static final MapCodec<ObtainAdvancement> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Identifier.CODEC.fieldOf("advancement").forGetter(ObtainAdvancement::advancement)
        ).apply(instance, ObtainAdvancement::new));
        public static final ConditionApi.Type<ObtainAdvancement> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("obtain_advancement"), CODEC, BuiltInConditionRuntime.ObtainAdvancementInstance::new);

        @Override
        public ConditionApi.Type<ObtainAdvancement> type() {
            return TYPE;
        }
    }

    public record ExplicitSignal(Identifier signal, int count) implements ConditionApi.Definition {
        public static final MapCodec<ExplicitSignal> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Identifier.CODEC.fieldOf("signal").forGetter(ExplicitSignal::signal),
                Codec.INT.optionalFieldOf("count", 1).forGetter(ExplicitSignal::count)
        ).apply(instance, ExplicitSignal::new));
        public static final ConditionApi.Type<ExplicitSignal> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("explicit_signal"), CODEC, BuiltInConditionRuntime.ExplicitSignalInstance::new);

        @Override
        public ConditionApi.Type<ExplicitSignal> type() {
            return TYPE;
        }
    }

    public record UninterruptedFall(
            LocationPredicate start,
            LocationPredicate end,
            double minimumDistance,
            boolean requireSurvival,
            FallRules rules) implements ConditionApi.Definition {
        public static final MapCodec<UninterruptedFall> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                LocationPredicate.CODEC.fieldOf("start").forGetter(UninterruptedFall::start),
                LocationPredicate.CODEC.fieldOf("end").forGetter(UninterruptedFall::end),
                Codec.DOUBLE.optionalFieldOf("minimum_distance", 0.0).forGetter(UninterruptedFall::minimumDistance),
                Codec.BOOL.optionalFieldOf("require_survival", true).forGetter(UninterruptedFall::requireSurvival),
                FallRules.CODEC.optionalFieldOf("rules", FallRules.DEFAULT).forGetter(UninterruptedFall::rules)
        ).apply(instance, UninterruptedFall::new));
        public static final ConditionApi.Type<UninterruptedFall> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("uninterrupted_fall"), CODEC, BuiltInConditionRuntime.UninterruptedFallInstance::new);

        @Override
        public ConditionApi.Type<UninterruptedFall> type() {
            return TYPE;
        }
    }

    public record FallRules(
            boolean allowWater,
            boolean allowLava,
            boolean allowClimbing,
            boolean allowElytra,
            boolean allowVehicles,
            double teleportThreshold) {
        public static final FallRules DEFAULT = new FallRules(false, false, false, false, false, 16.0);
        public static final Codec<FallRules> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("allow_water", false).forGetter(FallRules::allowWater),
                Codec.BOOL.optionalFieldOf("allow_lava", false).forGetter(FallRules::allowLava),
                Codec.BOOL.optionalFieldOf("allow_climbing", false).forGetter(FallRules::allowClimbing),
                Codec.BOOL.optionalFieldOf("allow_elytra", false).forGetter(FallRules::allowElytra),
                Codec.BOOL.optionalFieldOf("allow_vehicles", false).forGetter(FallRules::allowVehicles),
                Codec.DOUBLE.optionalFieldOf("teleport_threshold", 16.0).forGetter(FallRules::teleportThreshold)
        ).apply(instance, FallRules::new));
    }

    public static void register() {
        ConditionApi.register(ConsumeItems.TYPE);
        ConditionApi.register(FishItem.TYPE);
        ConditionApi.register(KillEntity.TYPE);
        ConditionApi.register(BreakBlock.TYPE);
        ConditionApi.register(VisitLocation.TYPE);
        ConditionApi.register(PlayerDeath.TYPE);
        ConditionApi.register(ObtainAdvancement.TYPE);
        ConditionApi.register(ExplicitSignal.TYPE);
        ConditionApi.register(UninterruptedFall.TYPE);
    }

    private BuiltInConditions() {
    }
}
