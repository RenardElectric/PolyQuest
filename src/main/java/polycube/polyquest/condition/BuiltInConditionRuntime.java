package polycube.polyquest.condition;

import com.google.gson.JsonObject;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import polycube.polyquest.runtime.ConditionRuntime;
import polycube.polyquest.signal.QuestSignal;

import java.util.ArrayList;
import java.util.List;

/// Mutable implementations for the immutable definitions in {@link BuiltInConditions}.
final class BuiltInConditionRuntime {
    /// A base class for conditions that track a boolean completion state.
    private abstract static class BaseInstance<D extends ConditionApi.Definition> implements ConditionRuntime.Instance {
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

    /// A base class for conditions that track a numeric count of progress toward completion.
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

    /// A condition that completes when consuming a specific item from the player's inventory, optionally requiring a specific count.
    static final class ConsumeItemsInstance extends BaseInstance<BuiltInConditions.ConsumeItems> {
        ConsumeItemsInstance(BuiltInConditions.ConsumeItems definition, ConditionRuntime.CreationContext context) {
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
                    : ConditionRuntime.ClaimPreparation.blocked("Requires " + definition.count() + " matching item(s)");
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("item", String.valueOf(definition.item().items().map(item -> item.stream().map(Holder::getRegisteredName).toList()).orElse(null)));
            result.addProperty("count", definition.count());
            return result;
        }
    }

    /// A condition that completes when the player catches a specific item while fishing.
    static final class FishItemInstance extends CounterInstance<BuiltInConditions.FishItem> {
        FishItemInstance(BuiltInConditions.FishItem definition, ConditionRuntime.CreationContext context) {
            super(definition, context, definition.count());
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (!(signal instanceof QuestSignal.Fishing fishing)) {
                return ConditionRuntime.Update.NONE;
            }
            int matches = 0;
            for (ItemStack stack : fishing.caught()) {
                if (definition.item().test(stack)) {
                    matches += stack.getCount();
                }
            }
            return increment(matches);
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("item", String.valueOf(definition.item().items().map(item -> item.stream().map(Holder::getRegisteredName).toList()).orElse(null)));
            return result;
        }
    }

    /// A condition that completes when the player kills a specific entity, optionally filtered by damage source.
    static final class KillEntityInstance extends CounterInstance<BuiltInConditions.KillEntity> {
        KillEntityInstance(BuiltInConditions.KillEntity definition, ConditionRuntime.CreationContext context) {
            super(definition, context, definition.count());
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (!(signal instanceof QuestSignal.Kill kill)) {
                return ConditionRuntime.Update.NONE;
            }
            ServerLevel level = kill.player().level();
            Vec3 origin = kill.player().position();
            if (!definition.victim().matches(level, origin, kill.victim())) {
                return ConditionRuntime.Update.NONE;
            }
            if (definition.damageSource().isPresent() && !definition.damageSource().get().matches(level, origin, kill.damageSource())) {
                return ConditionRuntime.Update.NONE;
            }
            return increment(1);
        }
    }

    /// A condition that completes when the player breaks a specific block, optionally filtered by a tool.
    static final class BreakBlockInstance extends CounterInstance<BuiltInConditions.BreakBlock> {
        BreakBlockInstance(BuiltInConditions.BreakBlock definition, ConditionRuntime.CreationContext context) {
            super(definition, context, definition.count());
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (!(signal instanceof QuestSignal.BlockBroken broken)) {
                return ConditionRuntime.Update.NONE;
            }
            if (!definition.block().matchesState(broken.state()) || !definition.block().matchesBlockEntity(broken.level(), broken.blockEntity())) {
                return ConditionRuntime.Update.NONE;
            }
            if (definition.tool().isPresent() && !definition.tool().get().test(broken.tool())) {
                return ConditionRuntime.Update.NONE;
            }
            return increment(1);
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("block", String.valueOf(definition.block().blocks().map(blocks -> blocks.stream().map(Holder::getRegisteredName).toList()).orElse(null)));
            result.addProperty("tool", definition.tool().map(tool -> String.valueOf(tool.items().map(items -> items.stream().map(Holder::getRegisteredName).toList()).orElse(null))).orElse(null));
            return result;
        }
    }

    /// A condition that completes when the player visits a specific location, optionally requiring continuous presence for a number of ticks.
    static final class VisitLocationInstance extends CounterInstance<BuiltInConditions.VisitLocation> {
        VisitLocationInstance(BuiltInConditions.VisitLocation definition, ConditionRuntime.CreationContext context) {
            super(definition, context, Math.max(1, definition.continuousTicks()));
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (!(signal instanceof QuestSignal.PlayerTick tick)) {
                return ConditionRuntime.Update.NONE;
            }
            ServerPlayer player = tick.player();
            ServerLevel level = player.level();
            if (definition.location().matches(level, player.getX(), player.getY(), player.getZ())) {
                return increment(1);
            }
            if (current != 0) {
                current = 0;
                return new ConditionRuntime.Update(true, false, false);
            }
            return ConditionRuntime.Update.NONE;
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("continuous_ticks", definition.continuousTicks());
            return result;
        }
    }

    /// A condition that completes when the player dies, optionally filtered by damage source.
    static final class PlayerDeathInstance extends BaseInstance<BuiltInConditions.PlayerDeath> {
        PlayerDeathInstance(BuiltInConditions.PlayerDeath definition, ConditionRuntime.CreationContext context) {
            super(definition, context);
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (!(signal instanceof QuestSignal.PlayerDeath death) || completed) {
                return ConditionRuntime.Update.NONE;
            }
            ServerLevel level = death.player().level();
            if (definition.damageSource().isPresent() && !definition.damageSource().get().matches(level, death.player().position(), death.damageSource())) {
                return ConditionRuntime.Update.NONE;
            }
            completed = true;
            return ConditionRuntime.Update.changed(true);
        }
    }

    /// A condition that completes when the player obtains a specific advancement.
    static final class ObtainAdvancementInstance extends BaseInstance<BuiltInConditions.ObtainAdvancement> {
        ObtainAdvancementInstance(BuiltInConditions.ObtainAdvancement definition, ConditionRuntime.CreationContext context) {
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

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("advancement", definition.advancement().toString());
            return result;
        }
    }

    /// A condition that completes when the player receives a specific explicit signal, optionally requiring a specific count.
    static final class ExplicitSignalInstance extends CounterInstance<BuiltInConditions.ExplicitSignal> {
        ExplicitSignalInstance(BuiltInConditions.ExplicitSignal definition, ConditionRuntime.CreationContext context) {
            super(definition, context, definition.count());
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            return signal instanceof QuestSignal.Explicit explicit
                    && explicit.signalId().equals(definition.signal())
                    ? increment(1)
                    : ConditionRuntime.Update.NONE;
        }

        @Override
        public JsonObject diagnostic() {
            JsonObject result = super.diagnostic();
            result.addProperty("signal", definition.signal().toString());
            return result;
        }
    }

    /// A condition that completes when the player falls a minimum distance without interruption, optionally requiring survival and specific start/end locations.
    static final class UninterruptedFallInstance extends BaseInstance<BuiltInConditions.UninterruptedFall> {
        private @Nullable Vec3 previousPosition;
        private @Nullable Vec3 airborneOrigin;
        private @Nullable Vec3 startPosition;
        private @Nullable ResourceKey<Level> previousDimension;
        private boolean active;

        UninterruptedFallInstance(BuiltInConditions.UninterruptedFall definition, ConditionRuntime.CreationContext context) {
            super(definition, context);
        }

        @Override
        public ConditionRuntime.Update onSignal(QuestSignal signal, ConditionRuntime.EvaluationContext context) {
            if (!(signal instanceof QuestSignal.PlayerTick tick) || completed) {
                return ConditionRuntime.Update.NONE;
            }

            ServerPlayer player = tick.player();
            ServerLevel level = player.level();
            ResourceKey<Level> dimension = level.dimension();
            Vec3 position = player.position();
            boolean onGround = player.onGround();

            if (previousDimension != null && !previousDimension.equals(dimension)) {
                clearFall();
                airborneOrigin = onGround ? position : null;
                previousPosition = position;
                previousDimension = dimension;
                return ConditionRuntime.Update.NONE;
            }
            if (previousPosition != null && previousPosition.distanceTo(position) > definition.rules().teleportThreshold()) {
                clearFall();
                airborneOrigin = onGround ? position : null;
                previousPosition = position;
                previousDimension = dimension;
                return ConditionRuntime.Update.NONE;
            }

            boolean upwardReversal = active && player.getDeltaMovement().y > 0.0;
            if (interrupted(player) || upwardReversal) {
                clearFall();
                airborneOrigin = null;
            } else if (onGround) {
                if (active && startPosition != null) {
                    double distance = startPosition.y - position.y;
                    boolean survived = !definition.requireSurvival() || player.isAlive();
                    if (distance >= definition.minimumDistance()
                            && survived
                            && definition.end().matches(level, position.x, position.y, position.z)) {
                        completed = true;
                        active = false;
                        airborneOrigin = position;
                        previousPosition = position;
                        previousDimension = dimension;
                        return ConditionRuntime.Update.changed(true);
                    }
                    clearFall();
                }
                airborneOrigin = position;
            } else {
                if (airborneOrigin == null) {
                    airborneOrigin = previousPosition == null ? position : previousPosition;
                }
                if (!active && player.getDeltaMovement().y < 0.0
                        && definition.start().matches(
                        level, airborneOrigin.x, airborneOrigin.y, airborneOrigin.z)) {
                    active = true;
                    startPosition = airborneOrigin;
                }
            }

            previousPosition = position;
            previousDimension = dimension;
            return ConditionRuntime.Update.NONE;
        }

        private boolean interrupted(ServerPlayer player) {
            BuiltInConditions.FallRules rules = definition.rules();
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
            airborneOrigin = null;
            startPosition = null;
            previousDimension = null;
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

    /// A claim operation that consumes a specific item from the player's inventory, optionally requiring a specific count.
    private record ItemConsumption(ItemPredicate predicate, int required) implements ConditionRuntime.ClaimOperation {
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
            for (var stack : inventory) {
                if (stack.isEmpty() || !predicate.test(stack)) continue;
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
            for (var stack : inventory) {
                if (!stack.isEmpty() && predicate.test(stack)) {
                    count += stack.getCount();
                    if (count >= required) {
                        return count;
                    }
                }
            }
            return count;
        }
    }

    private BuiltInConditionRuntime() {}
}
