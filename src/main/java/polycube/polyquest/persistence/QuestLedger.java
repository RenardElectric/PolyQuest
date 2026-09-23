package polycube.polyquest.persistence;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.RewardApi;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/// Durable world-owned quest state stored through Minecraft's SavedData system.
/// Partial attempt progress is intentionally absent; unclaimed completions are retained.
public final class QuestLedger extends SavedData {
    private static final Codec<Integer> NON_NEGATIVE_INT = Codec.INT.comapFlatMap(
            value -> value >= 0
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Value must be non-negative"),
            Function.identity());
    private static final Codec<Set<String>> CLAIM_SET_CODEC = Codec.STRING.listOf().xmap(HashSet::new, values -> values.stream().sorted().toList());
    private static final Codec<Map<UUID, Set<String>>> CLAIMS_CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, CLAIM_SET_CODEC);
    private static final Codec<Map<UUID, Map<Identifier, String>>> READY_CODEC = Codec.unboundedMap(
            UUIDUtil.STRING_CODEC, Codec.unboundedMap(Identifier.CODEC, Codec.STRING));
    private static final Codec<Map<QuestModel.Difficulty, RotationSlot>> ROTATION_CODEC =
            Codec.unboundedMap(QuestModel.Difficulty.CODEC, RotationSlot.CODEC);
    private static final Codec<Set<UUID>> UUID_SET_CODEC = UUIDUtil.STRING_CODEC.listOf().xmap(HashSet::new, values -> values.stream().sorted().toList());
    private static final Codec<List<RewardApi.Definition>> REWARDS_CODEC = RewardApi.codec().listOf();
    static final Codec<QuestLedger> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ROTATION_CODEC.optionalFieldOf("rotation", Map.of()).forGetter(ledger -> ledger.rotationSlots),
            UUID_SET_CODEC.optionalFieldOf("rotation_notified_players").forGetter(ledger -> ledger.rotationNotifiedPlayers),
            CLAIMS_CODEC.optionalFieldOf("claims", Map.of()).forGetter(ledger -> ledger.claims),
            PendingTransaction.CODEC.listOf().optionalFieldOf("pending", List.of()).forGetter(ledger -> List.copyOf(ledger.pending.values())),
            READY_CODEC.optionalFieldOf("ready", Map.of()).forGetter(QuestLedger::readyCompletions)
    ).apply(instance, QuestLedger::new));

    private static final SavedDataType<QuestLedger> TYPE = new SavedDataType<>(
            PolyQuest.id("ledger"),
            QuestLedger::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Set<String>> claims = new HashMap<>();
    private final Map<UUID, PendingTransaction> pending = new LinkedHashMap<>();
    private final Map<UUID, List<PendingTransaction>> pendingByPlayer = new HashMap<>();
    private final Map<UUID, Map<Identifier, ReadyCompletion>> ready = new HashMap<>();
    private final EnumMap<QuestModel.Difficulty, RotationSlot> rotationSlots = new EnumMap<>(QuestModel.Difficulty.class);
    // An absent list means no change has occurred yet; a present empty list means everyone needs the notice.
    private Optional<Set<UUID>> rotationNotifiedPlayers = Optional.empty();

    QuestLedger() {}

    private QuestLedger(
            Map<QuestModel.Difficulty, RotationSlot> rotationSlots,
            Optional<Set<UUID>> rotationNotifiedPlayers, Map<UUID, Set<String>> claims,
            List<PendingTransaction> pendingTransactions, Map<UUID, Map<Identifier, String>> readyCompletions
    ) {
        this();
        this.rotationSlots.putAll(rotationSlots);
        this.rotationNotifiedPlayers = rotationNotifiedPlayers.map(HashSet::new);
        claims.forEach((playerId, values) -> this.claims.put(playerId, new HashSet<>(values)));
        pendingTransactions.forEach(this::putPending);
        readyCompletions.forEach((playerId, completions) -> completions.forEach((questId, hash) ->
                putReady(new ReadyCompletion(playerId, questId, hash))));
    }

    public static QuestLedger load(MinecraftServer server) {
        SavedDataStorage storage = server.getDataStorage();
        return storage.computeIfAbsent(TYPE);
    }

    public boolean isClaimed(UUID playerId, QuestModel.Key occurrence) {
        return claims.getOrDefault(playerId, Set.of()).contains(occurrence.persistentKey());
    }

    /// Records an unclaimed completion until the occurrence is claimed or reset.
    public void markReady(UUID playerId, QuestModel.Occurrence occurrence) {
        var completion = new ReadyCompletion(playerId, occurrence.definition().id(), occurrence.definition().behaviorHash());
        putReady(completion);
        setDirty();
    }

    public boolean isReady(UUID playerId, QuestModel.Occurrence occurrence) {
        var completion = ready.getOrDefault(playerId, Map.of()).get(occurrence.definition().id());
        return completion != null && completion.behaviorHash().equals(occurrence.definition().behaviorHash());
    }

    /// Invalidates stale completed attempts after a rotation or functional catalog change.
    public List<ReadyCompletion> retainReadyCompletions(Map<Identifier, String> activeBehaviorHashes) {
        var removedCompletions = new ArrayList<ReadyCompletion>();
        var removedAny = false;
        for (var playerEntry : ready.entrySet()) {
            removedAny |= playerEntry.getValue().values().removeIf(completion -> {
                boolean stale = !completion.behaviorHash().equals(activeBehaviorHashes.get(completion.questId()));
                // A pending transaction already owns the outcome; do not report it as lost progress.
                if (stale && !isClaimedOrPending(completion.playerId(), completion.questId())) {
                    removedCompletions.add(completion);
                }
                return stale;
            });
        }
        ready.values().removeIf(Map::isEmpty);
        if (removedAny) setDirty();
        return List.copyOf(removedCompletions);
    }

    private Map<UUID, Map<Identifier, String>> readyCompletions() {
        Map<UUID, Map<Identifier, String>> saved = new TreeMap<>();
        ready.forEach((playerId, playerReady) -> {
            Map<Identifier, String> entries = new TreeMap<>();
            playerReady.values().forEach(completion -> entries.put(completion.questId(), completion.behaviorHash()));
            saved.put(playerId, entries);
        });
        return saved;
    }

    private void putReady(ReadyCompletion completion) {
        ready.computeIfAbsent(completion.playerId(), ignored -> new HashMap<>())
                .put(completion.questId(), completion);
    }

    private boolean clearReady(UUID playerId, Identifier questId) {
        var playerReady = ready.get(playerId);
        if (playerReady == null || playerReady.remove(questId) == null) return false;
        if (playerReady.isEmpty()) ready.remove(playerId);
        return true;
    }

    private boolean isClaimedOrPending(UUID playerId, Identifier questId) {
        return claims.getOrDefault(playerId, Set.of()).stream().anyMatch(key -> key.startsWith(questId + "|"))
                || pendingByPlayer.getOrDefault(playerId, List.of()).stream()
                .anyMatch(transaction -> transaction.questId().equals(questId));
    }

    public PendingTransaction beginClaim(UUID playerId, QuestModel.Occurrence occurrence, List<RewardApi.Definition> rewards) {
        PendingTransaction transaction = new PendingTransaction(
                UUID.randomUUID(), playerId, occurrence.definition().id(),
                occurrence.key().persistentKey(), List.copyOf(rewards),
                0, TransactionState.PREPARED, ""
        );
        putPending(transaction);
        setDirty();
        return transaction;
    }

    public void markCostsCommitted(PendingTransaction transaction) {
        transaction.state = TransactionState.COSTS_COMMITTED;
        setDirty();
    }

    public void advanceReward(PendingTransaction transaction) {
        transaction.nextReward++;
        transaction.state = TransactionState.REWARD_PENDING;
        transaction.lastError = "";
        setDirty();
    }

    public void markRetryable(PendingTransaction transaction, String message) {
        transaction.state = TransactionState.REWARD_PENDING;
        transaction.lastError = message;
        setDirty();
    }

    public void markFailed(PendingTransaction transaction, String message) {
        transaction.state = TransactionState.FAILED;
        transaction.lastError = message;
        setDirty();
    }

    public void cancel(PendingTransaction transaction) {
        var removed = pending.remove(transaction.id());
        if (removed != null) {
            unindexPending(removed);
            setDirty();
        }
    }

    public void complete(PendingTransaction transaction) {
        var removed = pending.remove(transaction.id());
        if (removed == null) return;
        claims.computeIfAbsent(removed.playerId(), ignored -> new HashSet<>()).add(removed.occurrenceKey());
        clearReady(removed.playerId(), removed.questId());
        unindexPending(removed);
        setDirty();
    }

    public List<PendingTransaction> pendingFor(UUID playerId) {
        return List.copyOf(pendingByPlayer.getOrDefault(playerId, List.of()));
    }

    public boolean hasPending(UUID playerId, QuestModel.Key occurrence) {
        return findPending(playerId, occurrence).isPresent();
    }

    public Optional<PendingTransaction> findPending(UUID playerId, QuestModel.Key occurrence) {
        var occurrenceKey = occurrence.persistentKey();
        return pendingByPlayer.getOrDefault(playerId, List.of()).stream()
                .filter(transaction -> transaction.occurrenceKey().equals(occurrenceKey))
                .findFirst();
    }

    /// Keeps the persisted transaction order and the player lookup index in sync.
    private void putPending(PendingTransaction transaction) {
        var replaced = pending.put(transaction.id(), transaction);
        if (replaced != null) unindexPending(replaced);
        pendingByPlayer.computeIfAbsent(transaction.playerId(), ignored -> new ArrayList<>()).add(transaction);
    }

    private void unindexPending(PendingTransaction transaction) {
        var playerTransactions = pendingByPlayer.get(transaction.playerId());
        if (playerTransactions != null) {
            playerTransactions.remove(transaction);
            if (playerTransactions.isEmpty()) {
                pendingByPlayer.remove(transaction.playerId());
            }
        }
    }

    public List<PendingTransaction> pendingTransactions() {
        return List.copyOf(pending.values());
    }

    public boolean resetClaim(UUID playerId, QuestModel.Key occurrence) {
        var changed = clearReady(playerId, occurrence.questId());
        Set<String> playerClaims = claims.get(playerId);
        if (playerClaims != null && playerClaims.remove(occurrence.persistentKey())) {
            if (playerClaims.isEmpty()) {
                claims.remove(playerId);
            }
            changed = true;
        }
        if (changed) setDirty();
        return changed;
    }

    /// Keeps only the current assignment and deadline for a difficulty; a replacement discards its old player state.
    public void setRotationSlot(QuestModel.Difficulty difficulty, Optional<Identifier> questId,
                                Instant nextRoll, boolean reset) {
        Optional<RotationSlot> previous = Optional.ofNullable(
                rotationSlots.put(difficulty, RotationSlot.of(questId, nextRoll)));
        if (reset) {
            clearDailyState(difficulty, previous, questId);
            clearRotationNotifications();
        }
        setDirty();
    }

    public Optional<RotationSlot> rotationSlot(QuestModel.Difficulty difficulty) {
        return Optional.ofNullable(rotationSlots.get(difficulty));
    }

    /// Allows one generic catch-up notice per player after any quest change.
    public void clearRotationNotifications() {
        rotationNotifiedPlayers = Optional.of(new HashSet<>());
        setDirty();
    }

    private void clearDailyState(QuestModel.Difficulty difficulty, Optional<RotationSlot> previous, Optional<Identifier> nextQuestId) {
        var scope = "|daily:" + difficulty.getSerializedName() + ':';
        claims.values().forEach(playerClaims -> playerClaims.removeIf(key -> key.contains(scope)));
        claims.values().removeIf(Set::isEmpty);
        var oldQuestId = previous.flatMap(RotationSlot::quest).map(Identifier::tryParse);
        ready.values().forEach(playerReady -> {
            oldQuestId.ifPresent(playerReady::remove);
            nextQuestId.ifPresent(playerReady::remove);
        });
        ready.values().removeIf(Map::isEmpty);
        for (PendingTransaction transaction : List.copyOf(pending.values())) {
            if (transaction.occurrenceKey().contains(scope)) {
                PolyQuest.LOGGER.warn("Cancelling unfinished daily quest transaction {} after {} rotation; {} rewards were already granted",
                        transaction.id(), difficulty.getSerializedName(), transaction.nextReward());
                cancel(transaction);
            }
        }
    }

    public record ReadyCompletion(UUID playerId, Identifier questId, String behaviorHash) {}

    /// Records delivery once per player for the current rotation, including across restarts.
    public boolean markRotationNotified(UUID playerId) {
        if (rotationNotifiedPlayers.isEmpty() || !rotationNotifiedPlayers.get().add(playerId)) {
            return false;
        }
        setDirty();
        return true;
    }

    public record RotationSlot(Optional<String> quest, Optional<String> nextRoll) {
        private static final Codec<RotationSlot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("quest").forGetter(RotationSlot::quest),
                Codec.STRING.optionalFieldOf("next_roll").forGetter(RotationSlot::nextRoll)
        ).apply(instance, RotationSlot::new));

        private static RotationSlot of(Optional<Identifier> questId, Instant nextRoll) {
            return new RotationSlot(questId.map(Identifier::toString), Optional.of(nextRoll.toString()));
        }
    }

    public enum TransactionState implements StringRepresentable {
        PREPARED("prepared"),
        COSTS_COMMITTED("costs_committed"),
        REWARD_PENDING("reward_pending"),
        FAILED("failed");

        private static final Codec<TransactionState> CODEC = StringRepresentable.fromEnum(TransactionState::values);
        private final String serializedName;

        TransactionState(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }

    public static final class PendingTransaction {
        private static final Codec<PendingTransaction> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(PendingTransaction::id),
                UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(PendingTransaction::playerId),
                Identifier.CODEC.fieldOf("quest").forGetter(PendingTransaction::questId),
                Codec.STRING.fieldOf("occurrence").forGetter(PendingTransaction::occurrenceKey),
                RewardProgress.CODEC.fieldOf("reward_progress").forGetter(PendingTransaction::rewardProgress)
        ).apply(instance, PendingTransaction::new));

        private final UUID id;
        private final UUID playerId;
        private final Identifier questId;
        private final String occurrenceKey;
        private final List<RewardApi.Definition> rewards;
        private int nextReward;
        private TransactionState state;
        private String lastError;

        private PendingTransaction(
                UUID id, UUID playerId, Identifier questId,
                String occurrenceKey, RewardProgress rewardProgress
        ) {
            this(
                    id, playerId, questId, occurrenceKey,
                    rewardProgress.rewards(), rewardProgress.nextReward(),
                    rewardProgress.state(), rewardProgress.lastError()
            );
        }

        private PendingTransaction(
                UUID id, UUID playerId, Identifier questId,
                String occurrenceKey, List<RewardApi.Definition> rewards,
                int nextReward, TransactionState state, String lastError
        ) {
            this.id = id;
            this.playerId = playerId;
            this.questId = questId;
            this.occurrenceKey = occurrenceKey;
            this.rewards = List.copyOf(rewards);
            this.nextReward = nextReward;
            this.state = state;
            this.lastError = lastError;
        }

        private RewardProgress rewardProgress() {
            return new RewardProgress(rewards, nextReward, state, lastError);
        }

        public UUID id() {
            return id;
        }

        public UUID playerId() {
            return playerId;
        }

        public Identifier questId() {
            return questId;
        }

        public String occurrenceKey() {
            return occurrenceKey;
        }

        public List<RewardApi.Definition> rewards() {
            return rewards;
        }

        public int nextReward() {
            return nextReward;
        }

        public TransactionState state() {
            return state;
        }

        public String lastError() {
            return lastError;
        }
    }

    private record RewardProgress(
            List<RewardApi.Definition> rewards,
            int nextReward,
            TransactionState state,
            String lastError) {
        private static final Codec<RewardProgress> CODEC = RecordCodecBuilder.<RewardProgress>create(instance -> instance.group(
                REWARDS_CODEC.fieldOf("rewards").forGetter(RewardProgress::rewards),
                NON_NEGATIVE_INT.fieldOf("next_reward").forGetter(RewardProgress::nextReward),
                TransactionState.CODEC.fieldOf("state").forGetter(RewardProgress::state),
                Codec.STRING.optionalFieldOf("last_error", "").forGetter(RewardProgress::lastError)
        ).apply(instance, RewardProgress::new)).validate(RewardProgress::validate);

        private RewardProgress {
            rewards = List.copyOf(rewards);
        }

        private static DataResult<RewardProgress> validate(RewardProgress progress) {
            return progress.nextReward <= progress.rewards.size()
                    ? DataResult.success(progress)
                    : DataResult.error(() -> "next_reward exceeds the reward count");
        }
    }
}
