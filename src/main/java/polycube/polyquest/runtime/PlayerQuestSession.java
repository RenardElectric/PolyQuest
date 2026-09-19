package polycube.polyquest.runtime;

import net.minecraft.server.MinecraftServer;
import polycube.polyquest.model.QuestModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/// In-memory, restart-discarded quest attempts for one player UUID.
public final class PlayerQuestSession {
    private final Map<QuestModel.Key, QuestAttempt> attempts = new LinkedHashMap<>();

    public QuestAttempt getOrCreate(QuestModel.Occurrence occurrence, MinecraftServer server) {
        return attempts.computeIfAbsent(
                occurrence.key(),
                ignored -> new QuestAttempt(occurrence, server));
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
            removed.add(attempt);
            return true;
        });
        return List.copyOf(removed);
    }

    public boolean removeOccurrence(QuestModel.Key key) {
        return attempts.remove(key) != null;
    }

    /// Rebinds retained attempts to the catalog's current definition and availability data.
    public void updateOccurrences(Map<QuestModel.Key, QuestModel.Occurrence> occurrences) {
        for (QuestAttempt attempt : attempts.values()) {
            Optional.ofNullable(occurrences.get(attempt.occurrence().key()))
                    .ifPresent(attempt::updateOccurrence);
        }
    }
}
