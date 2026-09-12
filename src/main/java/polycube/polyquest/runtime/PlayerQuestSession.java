package polycube.polyquest.runtime;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import polycube.polyquest.model.QuestModel;

/// In-memory, restart-discarded quest attempts for one player UUID.
public final class PlayerQuestSession {
    private final Map<String, QuestAttempt> attempts = new LinkedHashMap<>();

    public QuestAttempt getOrCreate(QuestModel.Occurrence occurrence, MinecraftServer server) {
        return attempts.computeIfAbsent(
                occurrence.key().persistentKey(),
                ignored -> new QuestAttempt(occurrence, server));
    }

    public @Nullable QuestAttempt get(QuestModel.Key key) {
        return attempts.get(key.persistentKey());
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

    public void updatePresentation(Map<Identifier, QuestModel.Definition> definitions) {
        for (QuestAttempt attempt : attempts.values()) {
            QuestModel.Definition definition = definitions.get(attempt.occurrence().definition().id());
            if (definition != null
                    && definition.behaviorHash().equals(attempt.occurrence().definition().behaviorHash())) {
                attempt.updatePresentation(new QuestModel.Occurrence(
                        attempt.occurrence().key(),
                        definition,
                        attempt.occurrence().availableFrom(),
                        attempt.occurrence().availableUntil()));
            }
        }
    }
}
