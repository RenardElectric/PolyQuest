package polycube.polyquest.runtime;

import net.minecraft.server.MinecraftServer;
import polycube.polyquest.model.QuestModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/// In-memory, restart-discarded quest attempts for one player UUID.
public final class PlayerQuestSession {
    private final UUID playerId;
    private final ConditionRuntime.CriterionRegistrar criteria;
    private final Map<QuestModel.Key, QuestAttempt> attempts = new LinkedHashMap<>();

    public PlayerQuestSession(UUID playerId, ConditionRuntime.CriterionRegistrar criteria) {
        this.playerId = playerId;
        this.criteria = criteria;
    }

    public QuestAttempt getOrCreate(QuestModel.Occurrence occurrence, MinecraftServer server) {
        return attempts.computeIfAbsent(
                occurrence.key(),
                ignored -> new QuestAttempt(playerId, occurrence, server, criteria));
    }

    public Optional<QuestAttempt> get(QuestModel.Key key) {
        return Optional.ofNullable(attempts.get(key));
    }

    public Iterable<QuestAttempt> attempts() {
        return attempts.values();
    }

    /// Removes matching attempts and returns them for reload notification decisions.
    public List<QuestAttempt> removeIf(Predicate<QuestAttempt> predicate) {
        List<QuestAttempt> removed = new ArrayList<>();
        attempts.values().removeIf(attempt -> {
            if (!predicate.test(attempt)) {
                return false;
            }
            attempt.close();
            removed.add(attempt);
            return true;
        });
        return List.copyOf(removed);
    }

    public boolean removeOccurrence(QuestModel.Key key) {
        QuestAttempt removed = attempts.remove(key);
        if (removed == null) {
            return false;
        }
        removed.close();
        return true;
    }

    /// Rebinds retained attempts to the catalog's current definition and availability data.
    public void updateOccurrences(Map<QuestModel.Key, QuestModel.Occurrence> occurrences) {
        for (QuestAttempt attempt : attempts.values()) {
            Optional.ofNullable(occurrences.get(attempt.occurrence().key()))
                    .ifPresent(attempt::updateOccurrence);
        }
    }

    public void close() {
        attempts.values().forEach(QuestAttempt::close);
        attempts.clear();
    }
}
