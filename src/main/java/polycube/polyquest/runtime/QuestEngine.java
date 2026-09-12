package polycube.polyquest.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.persistence.QuestLedger;
import polycube.polyquest.resource.QuestCatalogManager;
import polycube.polyquest.rotation.DailyRotationService;
import polycube.polyquest.signal.QuestSignal;

/// Routes normalized events to the active per-player condition trees.
public final class QuestEngine {
    private final MinecraftServer server;
    private final QuestCatalogManager catalogs;
    private final DailyRotationService rotation;
    private final QuestLedger ledger;
    private final Map<UUID, PlayerQuestSession> sessions = new HashMap<>();

    public QuestEngine(MinecraftServer server, QuestCatalogManager catalogs, DailyRotationService rotation, QuestLedger ledger) {
        this.server = server;
        this.catalogs = catalogs;
        this.rotation = rotation;
        this.ledger = ledger;
        catalogs.addListener(this::onCatalogChanged);
    }

    public void onSignal(QuestSignal signal) {
        PlayerQuestSession session = sessions.computeIfAbsent(signal.player().getUUID(), ignored -> new PlayerQuestSession());
        for (QuestModel.Occurrence occurrence : available(signal.player().getUUID())) {
            if (ledger.isClaimed(signal.player().getUUID(), occurrence.key())) {
                continue;
            }
            session.getOrCreate(occurrence, server).onSignal(signal, server);
        }
    }

    /// Advances deadline-only nodes for online and offline in-memory sessions.
    public void tick(long serverTick) {
        for (PlayerQuestSession session : sessions.values()) {
            for (QuestAttempt attempt : session.attempts()) {
                attempt.tick(server, serverTick);
            }
        }
    }

    public List<QuestModel.Occurrence> available(UUID playerId) {
        List<QuestModel.Occurrence> result = new ArrayList<>(rotation.current().slots().values());
        for (QuestModel.Definition definition : catalogs.current().unique()) {
            QuestModel.Key key = new QuestModel.Key(definition.id(), definition.behaviorHash(), new QuestModel.UniqueScope());
            result.add(new QuestModel.Occurrence(key, definition, Instant.EPOCH, Optional.empty()));
        }
        return List.copyOf(result);
    }

    public Optional<QuestModel.Occurrence> findOccurrence(UUID playerId, Identifier questId) {
        return available(playerId).stream()
                .filter(occurrence -> occurrence.definition().id().equals(questId))
                .findFirst();
    }

    public QuestAttempt attempt(UUID playerId, QuestModel.Occurrence occurrence) {
        return sessions.computeIfAbsent(playerId, ignored -> new PlayerQuestSession())
                .getOrCreate(occurrence, server);
    }

    public Optional<QuestAttempt> existingAttempt(UUID playerId, QuestModel.Key key) {
        PlayerQuestSession session = sessions.get(playerId);
        return session == null ? Optional.empty() : Optional.ofNullable(session.get(key));
    }

    public void rotationChanged() {
        java.util.Set<String> activeKeys = rotation.current().slots().values().stream()
                .map(occurrence -> occurrence.key().persistentKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        sessions.values().forEach(session -> session.removeDailyExcept(activeKeys));
    }

    public void reset(UUID playerId, QuestModel.Occurrence occurrence) {
        PlayerQuestSession session = sessions.get(playerId);
        if (session != null) session.removeOccurrence(occurrence.key());
        ledger.resetClaim(playerId, occurrence.key());
    }

    public void playerDisconnected(UUID playerId) {
        // Deliberately retained until shutdown: timed quests continue while the server runs.
    }

    private void onCatalogChanged(QuestCatalogManager.Update update) {
        for (Identifier id : update.diff().removed()) {
            sessions.values().forEach(session -> session.removeQuest(id));
            ledger.removeQuest(id);
        }
        for (Identifier id : update.diff().behaviorChanged()) {
            sessions.values().forEach(session -> session.removeQuest(id));
        }
        sessions.values().forEach(session -> session.updatePresentation(update.current().quests()));
    }
}
