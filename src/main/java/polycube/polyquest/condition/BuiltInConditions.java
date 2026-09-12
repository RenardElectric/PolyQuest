package polycube.polyquest.condition;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.advancements.predicates.BlockPredicate;
import net.minecraft.advancements.predicates.DamageSourcePredicate;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.integration.VanillaPredicateMatcher;
import polycube.polyquest.runtime.ConditionRuntime;
import polycube.polyquest.signal.QuestSignal;

/// Vanilla-predicate-backed atomic conditions bundled with PolyQuest.
public final class BuiltInConditions {
    public record ConsumeItems(ItemPredicate item, int count) implements ConditionApi.Definition {
        public static final MapCodec<ConsumeItems> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ItemPredicate.CODEC.fieldOf("item").forGetter(ConsumeItems::item),
                Codec.INT.fieldOf("count").forGetter(ConsumeItems::count)
        ).apply(instance, ConsumeItems::new));

        public static final ConditionApi.Type<ConsumeItems> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("consume_items"), CODEC, ConsumeItemsInstance::new);

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
                PolyQuest.id("fish_item"), CODEC, FishItemInstance::new);

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
                PolyQuest.id("kill_entity"), CODEC, KillEntityInstance::new);

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
                PolyQuest.id("break_block"), CODEC, BreakBlockInstance::new);

        @Override
        public ConditionApi.Type<BreakBlock> type() {
            return TYPE;
        }
    }

    public record VisitLocation(LocationPredicate location, int continuousTicks) implements ConditionApi.Definition {
        public static final MapCodec<VisitLocation> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                LocationPredicate.CODEC.fieldOf("location").forGetter(VisitLocation::location),
                Codec.INT.optionalFieldOf("continuous_ticks", 1).forGetter(VisitLocation::continuousTicks)
        ).apply(instance, VisitLocation::new));

        public static final ConditionApi.Type<VisitLocation> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("visit_location"), CODEC, VisitLocationInstance::new);

        @Override
        public ConditionApi.Type<VisitLocation> type() {
            return TYPE;
        }
    }

    public record PlayerDeath(Optional<DamageSourcePredicate> damageSource) implements ConditionApi.Definition {
        public static final MapCodec<PlayerDeath> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                DamageSourcePredicate.CODEC.optionalFieldOf("damage_source").forGetter(PlayerDeath::damageSource)
        ).apply(instance, PlayerDeath::new));

        public static final ConditionApi.Type<PlayerDeath> TYPE = new ConditionApi.Type<>(
                PolyQuest.id("player_death"), CODEC, PlayerDeathInstance::new);

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
                PolyQuest.id("obtain_advancement"), CODEC, ObtainAdvancementInstance::new);

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
                PolyQuest.id("explicit_signal"), CODEC, ExplicitSignalInstance::new);

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
                PolyQuest.id("uninterrupted_fall"), CODEC, UninterruptedFallInstance::new);

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

    private abstract static class BaseInstance<D extends ConditionApi.Definition>
            implements ConditionRuntime.Instance {
        protected final D definition;
        protected boolean completed;

        protected BaseInstance(D definition, ConditionRuntime.CreationContext ignored) {
            this.definition = definition;
        }

        @Override
        public D definition() {
            return definition;
        }

        @Override
        public boolean completed() {
            return completed;
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            return completed
                    ? ConditionRuntime.ClaimPreparation.readyPrep()
                    : ConditionRuntime.ClaimPreparation.blocked("Condition is not complete");
        }

        @Override
        public void reset() {
            completed = false;
        }
    }

    private abstract static class CounterInstance<D extends ConditionApi.Definition> extends BaseInstance<D> {
        protected int current;
        private final int target;

        protected CounterInstance(D definition, ConditionRuntime.CreationContext context, int target) {
            super(definition, context);
            if (target <= 0) {
                throw new IllegalArgumentException("Condition count must be positive");
            }
            this.target = target;
        }

        protected ConditionRuntime.Update increment(int amount) {
            if (completed || amount <= 0) {
                return ConditionRuntime.Update.NONE;
            }
            current = Math.min(target, current + amount);
            completed = current >= target;
            return ConditionRuntime.Update.changed(completed);
        }

        @Override
        public void reset() {
            super.reset();
            current = 0;
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("current", current);
            result.addProperty("target", target);
            return result;
        }
    }

    private static final class ConsumeItemsInstance extends BaseInstance<ConsumeItems> {
        private ConsumeItemsInstance(ConsumeItems definition, ConditionRuntime.CreationContext context) {
            super(definition, context);
            if (definition.count() <= 0) {
                throw new IllegalArgumentException("Item count must be positive");
            }
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            return ConditionRuntime.Update.NONE;
        }

        @Override
        public ConditionRuntime.ClaimPreparation prepareClaim(ConditionRuntime.ClaimContext context) {
            ItemConsumption operation = new ItemConsumption(definition.item(), definition.count());
            return operation.revalidate(context)
                    ? ConditionRuntime.ClaimPreparation.readyPrep(List.of(operation))
                    : ConditionRuntime.ClaimPreparation.blocked(
                            "Requires " + definition.count() + " matching item(s)");
        }
    }

    private static final class FishItemInstance extends CounterInstance<FishItem> {
        private FishItemInstance(FishItem definition, ConditionRuntime.CreationContext context) {
            super(definition, context, definition.count());
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (!(signal instanceof QuestSignal.Fishing fishing)) {
                return ConditionRuntime.Update.NONE;
            }
            int matches = 0;
            for (ItemStack stack : fishing.caught()) {
                if (VanillaPredicateMatcher.item(definition.item(), stack)) {
                    matches += stack.getCount();
                }
            }
            return increment(matches);
        }
    }

    private static final class KillEntityInstance extends CounterInstance<KillEntity> {
        private KillEntityInstance(KillEntity definition, ConditionRuntime.CreationContext context) {
            super(definition, context, definition.count());
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (!(signal instanceof QuestSignal.Kill kill)) {
                return ConditionRuntime.Update.NONE;
            }
            ServerLevel level = kill.player().level();
            Vec3 origin = kill.player().position();
            if (!VanillaPredicateMatcher.entity(definition.victim(), level, origin, kill.victim())) {
                return ConditionRuntime.Update.NONE;
            }
            if (definition.damageSource().isPresent()
                    && !VanillaPredicateMatcher.damageSource(definition.damageSource().get(), level, origin, kill.damageSource())) {
                return ConditionRuntime.Update.NONE;
            }
            return increment(1);
        }
    }

    private static final class BreakBlockInstance extends CounterInstance<BreakBlock> {
        private BreakBlockInstance(BreakBlock definition, ConditionRuntime.CreationContext context) {
            super(definition, context, definition.count());
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (!(signal instanceof QuestSignal.BlockBroken broken)) {
                return ConditionRuntime.Update.NONE;
            }
            if (!VanillaPredicateMatcher.block(definition.block(), broken.level(), broken.position())) {
                return ConditionRuntime.Update.NONE;
            }
            if (definition.tool().isPresent() && !VanillaPredicateMatcher.item(definition.tool().get(), broken.tool())) {
                return ConditionRuntime.Update.NONE;
            }
            return increment(1);
        }
    }

    private static final class VisitLocationInstance extends CounterInstance<VisitLocation> {
        private VisitLocationInstance(VisitLocation definition, ConditionRuntime.CreationContext context) {
            super(definition, context, Math.max(1, definition.continuousTicks()));
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (!(signal instanceof QuestSignal.PlayerTick tick)) {
                return ConditionRuntime.Update.NONE;
            }
            ServerPlayer player = tick.player();
            ServerLevel level = player.level();
            if (VanillaPredicateMatcher.location(definition.location(), level, player.getX(), player.getY(), player.getZ())) {
                return increment(1);
            }
            if (current != 0) {
                current = 0;
                return new ConditionRuntime.Update(true, false, false);
            }
            return ConditionRuntime.Update.NONE;
        }
    }

    private static final class PlayerDeathInstance extends BaseInstance<PlayerDeath> {
        private PlayerDeathInstance(PlayerDeath definition, ConditionRuntime.CreationContext context) {
            super(definition, context);
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (!(signal instanceof QuestSignal.PlayerDeath death) || completed) {
                return ConditionRuntime.Update.NONE;
            }
            ServerLevel level = death.player().level();
            if (definition.damageSource().isPresent()
                    && !VanillaPredicateMatcher.damageSource(
                            definition.damageSource().get(), level, death.player().position(), death.damageSource())) {
                return ConditionRuntime.Update.NONE;
            }
            completed = true;
            return ConditionRuntime.Update.changed(true);
        }
    }

    private static final class ObtainAdvancementInstance extends BaseInstance<ObtainAdvancement> {
        private ObtainAdvancementInstance(ObtainAdvancement definition, ConditionRuntime.CreationContext context) {
            super(definition, context);
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (!(signal instanceof QuestSignal.Advancement advancement) || completed) {
                return ConditionRuntime.Update.NONE;
            }
            if (!advancement.advancement().id().equals(definition.advancement())) {
                return ConditionRuntime.Update.NONE;
            }
            completed = true;
            return ConditionRuntime.Update.changed(true);
        }
    }

    private static final class ExplicitSignalInstance extends CounterInstance<ExplicitSignal> {
        private ExplicitSignalInstance(ExplicitSignal definition, ConditionRuntime.CreationContext context) {
            super(definition, context, definition.count());
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            return signal instanceof QuestSignal.Explicit explicit
                    && explicit.signalId().equals(definition.signal())
                    ? increment(1)
                    : ConditionRuntime.Update.NONE;
        }
    }

    private static final class UninterruptedFallInstance extends BaseInstance<UninterruptedFall> {
        private Vec3 previousPosition;
        private Vec3 startPosition;
        private boolean previousOnGround = true;
        private boolean active;

        private UninterruptedFallInstance(UninterruptedFall definition, ConditionRuntime.CreationContext context) {
            super(definition, context);
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (!(signal instanceof QuestSignal.PlayerTick tick) || completed) {
                return ConditionRuntime.Update.NONE;
            }

            ServerPlayer player = tick.player();
            ServerLevel level = player.level();
            Vec3 position = player.position();
            boolean onGround = player.onGround();

            if (previousPosition != null && active
                    && previousPosition.distanceTo(position) > definition.rules().teleportThreshold()) {
                clearFall();
            }

            if (interrupted(player)) {
                clearFall();
            } else if (!active && previousOnGround && !onGround && player.getDeltaMovement().y < 0.0) {
                Vec3 candidate = previousPosition == null ? position : previousPosition;
                if (VanillaPredicateMatcher.location(definition.start(), level, candidate.x, candidate.y, candidate.z)) {
                    active = true;
                    startPosition = candidate;
                }
            } else if (active && onGround) {
                double distance = startPosition.y - position.y;
                boolean survived = !definition.requireSurvival() || player.isAlive();
                if (distance >= definition.minimumDistance()
                        && survived
                        && VanillaPredicateMatcher.location(definition.end(), level, position.x, position.y, position.z)) {
                    completed = true;
                    active = false;
                    previousPosition = position;
                    previousOnGround = true;
                    return ConditionRuntime.Update.changed(true);
                }
                clearFall();
            }

            previousPosition = position;
            previousOnGround = onGround;
            return ConditionRuntime.Update.NONE;
        }

        private boolean interrupted(ServerPlayer player) {
            FallRules rules = definition.rules();
            return (!rules.allowWater() && player.isUnderWater())
                    || (!rules.allowLava() && player.isInLava())
                    || (!rules.allowClimbing() && player.onClimbable())
                    || (!rules.allowElytra() && player.isFallFlying())
                    || (!rules.allowVehicles() && player.isPassenger());
        }

        private void clearFall() {
            active = false;
            startPosition = null;
        }

        @Override
        public void reset() {
            super.reset();
            previousPosition = null;
            startPosition = null;
            previousOnGround = true;
            active = false;
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("active_fall", active);
            if (startPosition != null) {
                result.addProperty("start_y", startPosition.y);
            }
            return result;
        }
    }

    /// Claim operation that rescans the inventory at commit time and can restore
    /// every consumed stack when a later operation or reward fails immediately.
    private static final class ItemConsumption implements ConditionRuntime.ClaimOperation {
        private final ItemPredicate predicate;
        private final int required;

        private ItemConsumption(ItemPredicate predicate, int required) {
            this.predicate = predicate;
            this.required = required;
        }

        @Override
        public String describe() {
            return "Consume " + required + " matching item(s)";
        }

        @Override
        public boolean revalidate(ConditionRuntime.ClaimContext context) {
            return matchingCount(context.player().getInventory()) >= required;
        }

        @Override
        public ConditionRuntime.CommitResult commit(ConditionRuntime.ClaimContext context) {
            Container inventory = context.player().getInventory();
            if (matchingCount(inventory) < required) {
                return ConditionRuntime.CommitResult.failure("Required items are no longer present");
            }

            List<ItemStack> removed = new ArrayList<>();
            int remaining = required;
            for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty() || !VanillaPredicateMatcher.item(predicate, stack)) {
                    continue;
                }

                int amount = Math.min(remaining, stack.getCount());
                ItemStack taken = stack.copyWithCount(amount);
                stack.shrink(amount);
                removed.add(taken);
                remaining -= amount;
            }
            inventory.setChanged();

            return ConditionRuntime.CommitResult.success(() -> {
                ServerPlayer player = context.player();
                for (ItemStack stack : removed) {
                    ItemStack remainder = stack.copy();
                    player.getInventory().add(remainder);
                    if (!remainder.isEmpty()) {
                        player.drop(remainder, false, Prediction.SERVER_ONLY);
                    }
                }
            });
        }

        private int matchingCount(Container inventory) {
            int count = 0;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && VanillaPredicateMatcher.item(predicate, stack)) {
                    count += stack.getCount();
                    if (count >= required) {
                        return count;
                    }
                }
            }
            return count;
        }
    }

    private BuiltInConditions() {
    }
}
