package polycube.polyquest.runtime;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import polycube.polyquest.model.QuestModel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/// In-memory, restart-discarded quest attempts for one player UUID.
public final class PlayerQuestSession {
    private final Map<String, QuestAttempt> attempts = new LinkedHashMap<>();

    public QuestAttempt getOrCreate(QuestModel.Occurrence occurrence, MinecraftServer server) {
        return attempts.computeIfAbsent(
                occurrence.key().persistentKey(),
                ignored -> new QuestAttempt(occurrence, server));
    }

    public Optional<QuestAttempt> get(QuestModel.Key key) {
        return Optional.ofNullable(attempts.get(key.persistentKey()));
    }

    public Iterable<QuestAttempt> attempts() {
        return attempts.values();
    }

    public void removeDailyExcept(java.util.Set<String> activeOccurrenceKeys) {
        attempts.values().removeIf(attempt ->
                attempt.occurrence().key().scope() instanceof QuestModel.DailyScope
                        && !activeOccurrenceKeys.contains(attempt.occurrence().key().persistentKey()));
    }

    public void removeQuest(Identifier questId) {
        attempts.values().removeIf(attempt ->
                attempt.occurrence().definition().id().equals(questId));
    }

    public void removeOccurrence(QuestModel.Key key) {
        attempts.remove(key.persistentKey());
    }

    /// Rebinds attempts only when the quest's behavior revision is unchanged.
    public void updatePresentation(Map<Identifier, QuestModel.Definition> definitions) {
        for (QuestAttempt attempt : attempts.values()) {
            Optional.ofNullable(definitions.get(attempt.occurrence().definition().id()))
                    .filter(definition -> definition.behaviorHash().equals(attempt.occurrence().definition().behaviorHash()))
                    .ifPresent(definition ->
                            attempt.updatePresentation(new QuestModel.Occurrence(
                                    attempt.occurrence().key(),
                                    definition,
                                    attempt.occurrence().availableFrom(),
                                    attempt.occurrence().availableUntil())));
        }
    }
}
