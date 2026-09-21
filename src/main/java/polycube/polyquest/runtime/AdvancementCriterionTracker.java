package polycube.polyquest.runtime;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import polycube.polyquest.PolyQuest;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/// Owns transient fake advancement listeners while leaving vanilla progress storage untouched.
final class AdvancementCriterionTracker implements ConditionRuntime.CriterionRegistrar, AutoCloseable {
    static final String CRITERION_NAME = "trigger";
    private static final String PATH_PREFIX = "runtime/criterion/";

    private final MinecraftServer server;
    private final Map<Identifier, Entry> entries = new HashMap<>();
    private final Map<UUID, Set<Entry>> entriesByPlayer = new HashMap<>();
    private final Map<UUID, MatchBatch> matchBatches = new HashMap<>();
    private long nextRegistrationId;

    AdvancementCriterionTracker(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public ConditionRuntime.CriterionRegistration register(UUID playerId, Criterion<?> criterion) {
        var id = PolyQuest.id(PATH_PREFIX + Long.toUnsignedString(nextRegistrationId++));

        var entry = new Entry(playerId, id, criterion);
        entries.put(id, entry);
        entriesByPlayer.computeIfAbsent(playerId, ignored -> new LinkedHashSet<>()).add(entry);
        entry.activate();
        return entry;
    }

    /// Reattaches retained listeners after login or a vanilla advancement-manager reload.
    void rebind(ServerPlayer player) {
        for (var entry : List.copyOf(entriesByPlayer.getOrDefault(player.getUUID(), Set.of()))) {
            entry.unbind();
            entry.bind(player);
        }
    }

    void disconnect(ServerPlayer player) {
        for (var entry : List.copyOf(entriesByPlayer.getOrDefault(player.getUUID(), Set.of()))) {
            entry.unbind();
        }
        matchBatches.remove(player.getUUID());
    }

    /// Groups the fake awards produced by one vanilla trigger evaluation.
    void beginMatchBatch(ServerPlayer player) {
        var playerId = player.getUUID();
        if (!entriesByPlayer.containsKey(playerId)) {
            return;
        }
        var batch = matchBatches.computeIfAbsent(playerId, _ -> new MatchBatch());
        batch.matches.clear();
        batch.active = true;
    }

    Set<Identifier> endMatchBatch(ServerPlayer player) {
        var batch = matchBatches.get(player.getUUID());
        if (batch == null || !batch.active) {
            return Set.of();
        }
        batch.active = false;
        if (batch.matches.isEmpty()) {
            return Set.of();
        }
        var matches = Set.copyOf(batch.matches);
        batch.matches.clear();
        return matches;
    }

    /// Identifies a fake award and returns its signal token when the registration is active.
    Award intercept(ServerPlayer player, AdvancementHolder holder, String criterionName) {
        if (!isFake(holder.id())) {
            return Award.PASS;
        }
        var entry = entries.get(holder.id());
        if (entry == null) {
            return Award.INTERCEPTED;
        }
        if (!CRITERION_NAME.equals(criterionName)
                || !entry.playerId.equals(player.getUUID())
                || !entry.enabled) {
            return Award.INTERCEPTED;
        }
        var batch = matchBatches.get(player.getUUID());
        if (batch != null && batch.active) {
            batch.matches.add(entry.id);
            return Award.INTERCEPTED;
        }
        return new Award(true, Optional.of(entry.id));
    }

    @Override
    public void close() {
        List.copyOf(entries.values()).forEach(Entry::close);
        matchBatches.clear();
    }

    private static boolean isFake(Identifier id) {
        return id.getNamespace().equals(PolyQuest.MOD_ID) && id.getPath().startsWith(PATH_PREFIX);
    }

    private static <T extends CriterionTriggerInstance> void addListener(
            PlayerAdvancements advancements,
            Criterion<T> criterion,
            PlayerAdvancements.TriggerInstanceKey key
    ) {
        advancements.addListener(criterion, key);
    }

    private static <T extends CriterionTriggerInstance> void removeListener(
            PlayerAdvancements advancements,
            Criterion<T> criterion,
            PlayerAdvancements.TriggerInstanceKey key
    ) {
        advancements.removeListener(criterion.trigger(), key);
    }

    record Award(boolean intercepted, Optional<Identifier> registrationId) {
        private static final Award PASS = new Award(false, Optional.empty());
        private static final Award INTERCEPTED = new Award(true, Optional.empty());
    }

    private static final class MatchBatch {
        private final Set<Identifier> matches = new LinkedHashSet<>();
        private boolean active;
    }

    private final class Entry implements ConditionRuntime.CriterionRegistration {
        private final UUID playerId;
        private final Identifier id;
        private final Criterion<?> criterion;
        private final PlayerAdvancements.TriggerInstanceKey key;
        private boolean enabled;
        private boolean closed;
        private @Nullable PlayerAdvancements boundAdvancements;

        private Entry(UUID playerId, Identifier id, Criterion<?> criterion) {
            this.playerId = playerId;
            this.id = id;
            this.criterion = criterion;
            var holder = new Advancement.Builder()
                    .addCriterion(CRITERION_NAME, criterion)
                    .build(id);
            this.key = new PlayerAdvancements.TriggerInstanceKey(holder, CRITERION_NAME);
        }

        @Override
        public Identifier id() {
            return id;
        }

        @Override
        public void activate() {
            if (closed || enabled) {
                return;
            }
            enabled = true;
            var player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                bind(player);
            }
        }

        @Override
        public void deactivate() {
            enabled = false;
            unbind();
        }

        private void bind(ServerPlayer player) {
            if (!enabled || closed || boundAdvancements != null) {
                return;
            }
            var advancements = player.getAdvancements();
            addListener(advancements, criterion, key);
            boundAdvancements = advancements;
        }

        private void unbind() {
            if (boundAdvancements == null) {
                return;
            }
            removeListener(boundAdvancements, criterion, key);
            boundAdvancements = null;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            enabled = false;
            unbind();
            entries.remove(id);
            var playerEntries = entriesByPlayer.get(playerId);
            if (playerEntries != null) {
                playerEntries.remove(this);
                if (playerEntries.isEmpty()) {
                    entriesByPlayer.remove(playerId);
                    var batch = matchBatches.get(playerId);
                    if (batch == null || !batch.active) {
                        matchBatches.remove(playerId);
                    }
                }
            }
        }
    }
}
