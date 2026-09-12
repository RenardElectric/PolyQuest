package polycube.polyquest.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.RewardApi;

/// Minimal durable world-owned ledger.
///
/// Attempt progress is intentionally absent. Only successful occurrence claims,
/// pending reward transactions, and manual reroll generations survive a restart.
public final class QuestLedger {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "polyquest-ledger.json";

    private final MinecraftServer server;
    private final Path path;
    private final Map<UUID, Set<String>> claims = new HashMap<>();
    private final Map<UUID, PendingTransaction> pending = new LinkedHashMap<>();
    private final EnumMap<QuestModel.Difficulty, Integer> rotationGenerations =
            new EnumMap<>(QuestModel.Difficulty.class);
    private LocalDate rotationDate = LocalDate.MIN;

    private QuestLedger(MinecraftServer server, Path path) {
        this.server = server;
        this.path = path;
        for (QuestModel.Difficulty difficulty : QuestModel.Difficulty.values()) {
            rotationGenerations.put(difficulty, 0);
        }
    }

    public static QuestLedger load(MinecraftServer server) {
        Path path = server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(FILE_NAME);
        QuestLedger ledger = new QuestLedger(server, path);
        ledger.read();
        return ledger;
    }

    public boolean isClaimed(UUID playerId, QuestModel.Key occurrence) {
        return claims.getOrDefault(playerId, Set.of()).contains(occurrence.persistentKey());
    }

    public PendingTransaction beginClaim(
            UUID playerId,
            QuestModel.Occurrence occurrence,
            List<RewardApi.Definition> rewards) {
        PendingTransaction transaction = new PendingTransaction(
                UUID.randomUUID(),
                playerId,
                occurrence.definition().id().toString(),
                occurrence.key().persistentKey(),
                List.copyOf(rewards),
                0,
                TransactionState.PREPARED,
                "");
        pending.put(transaction.id(), transaction);
        save();
        return transaction;
    }

    public void markCostsCommitted(PendingTransaction transaction) {
        transaction.state = TransactionState.COSTS_COMMITTED;
        save();
    }

    public void advanceReward(PendingTransaction transaction) {
        transaction.nextReward++;
        transaction.state = TransactionState.REWARD_PENDING;
        transaction.lastError = "";
        save();
    }

    public void markRetryable(PendingTransaction transaction, String message) {
        transaction.state = TransactionState.REWARD_PENDING;
        transaction.lastError = message;
        save();
    }

    public void markFailed(PendingTransaction transaction, String message) {
        transaction.state = TransactionState.FAILED;
        transaction.lastError = message;
        save();
    }

    public void cancel(PendingTransaction transaction) {
        pending.remove(transaction.id());
        save();
    }

    public void complete(PendingTransaction transaction) {
        claims.computeIfAbsent(transaction.playerId(), ignored -> new HashSet<>())
                .add(transaction.occurrenceKey());
        pending.remove(transaction.id());
        save();
    }

    public List<PendingTransaction> pendingFor(UUID playerId) {
        return pending.values().stream()
                .filter(transaction -> transaction.playerId().equals(playerId))
                .toList();
    }

    public List<PendingTransaction> pendingTransactions() {
        return List.copyOf(pending.values());
    }

    public void removeQuest(net.minecraft.resources.Identifier questId) {
        String prefix = questId + "|";
        claims.values().forEach(values -> values.removeIf(value -> value.startsWith(prefix)));
        pending.values().removeIf(transaction -> transaction.questId().equals(questId.toString()));
        save();
    }

    public void resetClaim(UUID playerId, QuestModel.Key occurrence) {
        Set<String> playerClaims = claims.get(playerId);
        if (playerClaims != null) {
            playerClaims.remove(occurrence.persistentKey());
            if (playerClaims.isEmpty()) {
                claims.remove(playerId);
            }
            save();
        }
    }

    public void beginRotation(LocalDate date) {
        if (date.equals(rotationDate)) {
            return;
        }
        rotationDate = date;
        rotationGenerations.replaceAll((difficulty, ignored) -> 0);
        save();
    }

    public int rotationGeneration(QuestModel.Difficulty difficulty) {
        return rotationGenerations.getOrDefault(difficulty, 0);
    }

    public void incrementRotationGeneration(QuestModel.Difficulty difficulty) {
        rotationGenerations.merge(difficulty, 1, Integer::sum);
        save();
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("rotation_date", rotationDate.toString());
        JsonObject generations = new JsonObject();
        rotationGenerations.forEach((difficulty, generation) ->
                generations.addProperty(difficulty.getSerializedName(), generation));
        root.add("rotation_generations", generations);

        JsonObject claimObject = new JsonObject();
        claims.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    JsonArray values = new JsonArray();
                    entry.getValue().stream().sorted().forEach(values::add);
                    claimObject.add(entry.getKey().toString(), values);
                });
        root.add("claims", claimObject);

        JsonArray transactions = new JsonArray();
        pending.values().stream()
                .sorted(java.util.Comparator.comparing(transaction -> transaction.id().toString()))
                .map(this::encodeTransaction)
                .forEach(transactions::add);
        root.add("pending", transactions);

        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(temporary, path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            PolyQuest.LOGGER.error("Could not persist PolyQuest ledger {}", path, exception);
        }
    }

    private void read() {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return;
            }
            readRotation(root);
            readClaims(root);
            readTransactions(root);
        } catch (IOException | RuntimeException exception) {
            PolyQuest.LOGGER.error("Could not load PolyQuest ledger {}; starting empty", path, exception);
            claims.clear();
            pending.clear();
        }
    }

    private void readRotation(JsonObject root) {
        try {
            if (root.has("rotation_date")) {
                rotationDate = LocalDate.parse(root.get("rotation_date").getAsString());
            }
        } catch (DateTimeParseException | IllegalStateException exception) {
            rotationDate = LocalDate.MIN;
        }
        if (root.has("rotation_generations") && root.get("rotation_generations").isJsonObject()) {
            JsonObject generations = root.getAsJsonObject("rotation_generations");
            for (QuestModel.Difficulty difficulty : QuestModel.Difficulty.values()) {
                if (generations.has(difficulty.getSerializedName())) {
                    rotationGenerations.put(
                            difficulty,
                            Math.max(0, generations.get(difficulty.getSerializedName()).getAsInt()));
                }
            }
        }
    }

    private void readClaims(JsonObject root) {
        if (!root.has("claims") || !root.get("claims").isJsonObject()) {
            return;
        }
        root.getAsJsonObject("claims").entrySet().forEach(entry -> {
            try {
                UUID playerId = UUID.fromString(entry.getKey());
                Set<String> values = new HashSet<>();
                entry.getValue().getAsJsonArray().forEach(value -> values.add(value.getAsString()));
                claims.put(playerId, values);
            } catch (RuntimeException exception) {
                PolyQuest.LOGGER.warn("Ignoring malformed PolyQuest claim entry for {}", entry.getKey());
            }
        });
    }

    private void readTransactions(JsonObject root) {
        if (!root.has("pending") || !root.get("pending").isJsonArray()) {
            return;
        }
        for (JsonElement element : root.getAsJsonArray("pending")) {
            try {
                PendingTransaction transaction = decodeTransaction(element.getAsJsonObject());
                if (transaction != null) {
                    pending.put(transaction.id(), transaction);
                }
            } catch (RuntimeException exception) {
                PolyQuest.LOGGER.error("Ignoring malformed pending PolyQuest transaction", exception);
            }
        }
    }

    private JsonObject encodeTransaction(PendingTransaction transaction) {
        JsonObject json = new JsonObject();
        json.addProperty("id", transaction.id().toString());
        json.addProperty("player", transaction.playerId().toString());
        json.addProperty("quest", transaction.questId());
        json.addProperty("occurrence", transaction.occurrenceKey());
        json.addProperty("next_reward", transaction.nextReward());
        json.addProperty("state", transaction.state().name());
        json.addProperty("last_error", transaction.lastError());

        DynamicOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        JsonElement rewards = extract(
                RewardApi.codec().listOf().encodeStart(ops, transaction.rewards()),
                "Could not encode pending rewards for " + transaction.id());
        json.add("rewards", rewards == null ? new JsonArray() : rewards);
        return json;
    }

    private PendingTransaction decodeTransaction(JsonObject json) {
        DynamicOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        List<RewardApi.Definition> rewards = extract(
                RewardApi.codec().listOf().parse(ops, json.get("rewards")),
                "Could not decode pending rewards");
        if (rewards == null) {
            return null;
        }
        return new PendingTransaction(
                UUID.fromString(json.get("id").getAsString()),
                UUID.fromString(json.get("player").getAsString()),
                json.get("quest").getAsString(),
                json.get("occurrence").getAsString(),
                rewards,
                json.get("next_reward").getAsInt(),
                TransactionState.valueOf(json.get("state").getAsString()),
                json.has("last_error") ? json.get("last_error").getAsString() : "");
    }

    private static <T> T extract(DataResult<T> result, String context) {
        AtomicReference<T> value = new AtomicReference<>();
        result.ifSuccess(value::set);
        result.ifError(error -> PolyQuest.LOGGER.error("{}: {}", context, error.message()));
        return value.get();
    }

    public enum TransactionState {
        PREPARED,
        COSTS_COMMITTED,
        REWARD_PENDING,
        FAILED
    }

    public static final class PendingTransaction {
        private final UUID id;
        private final UUID playerId;
        private final String questId;
        private final String occurrenceKey;
        private final List<RewardApi.Definition> rewards;
        private int nextReward;
        private TransactionState state;
        private String lastError;

        private PendingTransaction(
                UUID id,
                UUID playerId,
                String questId,
                String occurrenceKey,
                List<RewardApi.Definition> rewards,
                int nextReward,
                TransactionState state,
                String lastError) {
            this.id = id;
            this.playerId = playerId;
            this.questId = questId;
            this.occurrenceKey = occurrenceKey;
            this.rewards = List.copyOf(rewards);
            this.nextReward = nextReward;
            this.state = state;
            this.lastError = lastError == null ? "" : lastError;
        }

        public UUID id() {
            return id;
        }

        public UUID playerId() {
            return playerId;
        }

        public String questId() {
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
}
