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

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;

/// Durable world-owned claim and reward state stored through Minecraft's SavedData system.
///
/// Attempt progress is intentionally absent. Only successful occurrence claims,
/// pending rewards, rotation metadata, and notification receipts survive a restart.
public final class QuestLedger extends SavedData {
    private static final Codec<LocalDate> DATE_CODEC = Codec.STRING.comapFlatMap(
            value -> {
                try {
                    return DataResult.success(LocalDate.parse(value));
                } catch (DateTimeParseException exception) {
                    return DataResult.error(() -> "Invalid date '" + value + "'");
                }
            },
            LocalDate::toString);
    private static final Codec<Integer> NON_NEGATIVE_INT = Codec.INT.comapFlatMap(
            value -> value >= 0
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Value must be non-negative"),
            Function.identity());
    private static final Codec<Set<String>> CLAIM_SET_CODEC = Codec.STRING.listOf().xmap(HashSet::new, values -> values.stream().sorted().toList());
    private static final Codec<Map<UUID, Set<String>>> CLAIMS_CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, CLAIM_SET_CODEC);
    private static final Codec<Map<QuestModel.Difficulty, Integer>> ROTATION_GENERATIONS_CODEC = Codec.unboundedMap(QuestModel.Difficulty.CODEC, NON_NEGATIVE_INT);
    private static final Codec<Set<UUID>> UUID_SET_CODEC = UUIDUtil.STRING_CODEC.listOf().xmap(HashSet::new, values -> values.stream().sorted().toList());
    private static final Codec<List<RewardApi.Definition>> REWARDS_CODEC = RewardApi.codec().listOf();

    static final Codec<QuestLedger> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            DATE_CODEC.optionalFieldOf("rotation_date", LocalDate.MIN).forGetter(ledger -> ledger.rotationDate),
            ROTATION_GENERATIONS_CODEC.optionalFieldOf("rotation_generations", Map.of()).forGetter(ledger -> ledger.rotationGenerations),
            UUID_SET_CODEC.optionalFieldOf("rotation_notified_players", Set.of()).forGetter(ledger -> ledger.rotationNotifiedPlayers),
            CLAIMS_CODEC.optionalFieldOf("claims", Map.of()).forGetter(ledger -> ledger.claims),
            PendingTransaction.CODEC.listOf().optionalFieldOf("pending", List.of()).forGetter(ledger -> List.copyOf(ledger.pending.values()))
    ).apply(instance, QuestLedger::new));

    private static final SavedDataType<QuestLedger> TYPE = new SavedDataType<>(
            PolyQuest.id("ledger"),
            QuestLedger::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Set<String>> claims = new HashMap<>();
    private final Map<UUID, PendingTransaction> pending = new LinkedHashMap<>();
    private final EnumMap<QuestModel.Difficulty, Integer> rotationGenerations = new EnumMap<>(QuestModel.Difficulty.class);
    private final Set<UUID> rotationNotifiedPlayers = new HashSet<>();
    private LocalDate rotationDate = LocalDate.MIN;

    QuestLedger() {
        for (QuestModel.Difficulty difficulty : QuestModel.Difficulty.values()) {
            rotationGenerations.put(difficulty, 0);
        }
    }

    private QuestLedger(
            LocalDate rotationDate, Map<QuestModel.Difficulty, Integer> rotationGenerations,
            Set<UUID> rotationNotifiedPlayers, Map<UUID, Set<String>> claims,
            List<PendingTransaction> pendingTransactions
    ) {
        this();
        this.rotationDate = rotationDate;
        this.rotationGenerations.putAll(rotationGenerations);
        this.rotationNotifiedPlayers.addAll(rotationNotifiedPlayers);
        claims.forEach((playerId, values) -> this.claims.put(playerId, new HashSet<>(values)));
        pendingTransactions.forEach(transaction -> pending.put(transaction.id(), transaction));
    }

    public static QuestLedger load(MinecraftServer server) {
        SavedDataStorage storage = server.getDataStorage();
        return storage.computeIfAbsent(TYPE);
    }

    public boolean isClaimed(UUID playerId, QuestModel.Key occurrence) {
        return claims.getOrDefault(playerId, Set.of()).contains(occurrence.persistentKey());
    }

    public PendingTransaction beginClaim(UUID playerId, QuestModel.Occurrence occurrence, List<RewardApi.Definition> rewards) {
        PendingTransaction transaction = new PendingTransaction(
                UUID.randomUUID(), playerId, occurrence.definition().id(),
                occurrence.key().persistentKey(), List.copyOf(rewards),
                0, TransactionState.PREPARED, ""
        );
        pending.put(transaction.id(), transaction);
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
        pending.remove(transaction.id());
        setDirty();
    }

    public void complete(PendingTransaction transaction) {
        claims.computeIfAbsent(transaction.playerId(), ignored -> new HashSet<>()).add(transaction.occurrenceKey());
        pending.remove(transaction.id());
        setDirty();
    }

    public List<PendingTransaction> pendingFor(UUID playerId) {
        return pending.values().stream()
                .filter(transaction -> transaction.playerId().equals(playerId))
                .toList();
    }

    public boolean hasPending(UUID playerId, QuestModel.Key occurrence) {
        String occurrenceKey = occurrence.persistentKey();
        return pending.values().stream().anyMatch(transaction ->
                transaction.playerId().equals(playerId)
                        && transaction.occurrenceKey().equals(occurrenceKey));
    }

    public List<PendingTransaction> pendingTransactions() {
        return List.copyOf(pending.values());
    }

    public boolean resetClaim(UUID playerId, QuestModel.Key occurrence) {
        Set<String> playerClaims = claims.get(playerId);
        if (playerClaims != null && playerClaims.remove(occurrence.persistentKey())) {
            if (playerClaims.isEmpty()) {
                claims.remove(playerId);
            }
            setDirty();
            return true;
        }
        return false;
    }

    public void beginRotation(LocalDate date) {
        if (date.equals(rotationDate)) {
            return;
        }
        rotationDate = date;
        rotationGenerations.replaceAll((_, _) -> 0);
        rotationNotifiedPlayers.clear();
        setDirty();
    }

    /// Records delivery once per player for the current rotation, including across restarts.
    public boolean markRotationNotified(UUID playerId) {
        if (!rotationNotifiedPlayers.add(playerId)) {
            return false;
        }
        setDirty();
        return true;
    }

    public int rotationGeneration(QuestModel.Difficulty difficulty) {
        return rotationGenerations.getOrDefault(difficulty, 0);
    }

    public void incrementRotationGeneration(QuestModel.Difficulty difficulty) {
        rotationGenerations.merge(difficulty, 1, Integer::sum);
        setDirty();
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
