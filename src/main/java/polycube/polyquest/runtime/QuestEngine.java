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
import org.jspecify.annotations.Nullable;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.persistence.QuestLedger;
import polycube.polyquest.resource.QuestCatalogManager;
import polycube.polyquest.rotation.DailyRotationService;
import polycube.polyquest.signal.QuestSignal;

/// Routes normalized events to the active per-player condition trees.
public final class QuestEngine implements AutoCloseable {
    private final MinecraftServer server;
    private final QuestCatalogManager catalogs;
    private final DailyRotationService rotation;
    private final QuestLedger ledger;
    private final QuestCatalogManager.Subscription catalogSubscription;
    private final Map<UUID, PlayerQuestSession> sessions = new HashMap<>();
    private List<QuestModel.Occurrence> availableOccurrences = List.of();

    public QuestEngine(MinecraftServer server, QuestCatalogManager catalogs, DailyRotationService rotation, QuestLedger ledger) {
        this.server = server;
        this.catalogs = catalogs;
        this.rotation = rotation;
        this.ledger = ledger;
        catalogSubscription = catalogs.addListener(this::onCatalogChanged);
        rebuildAvailableOccurrences();
    }

    /// Fans a player signal into every available, unclaimed occurrence, creating attempts lazily.
    public void onSignal(QuestSignal signal) {
        UUID playerId = signal.player().getUUID();
        PlayerQuestSession session = sessions.get(playerId);
        for (QuestModel.Occurrence occurrence : available(playerId)) {
            if (ledger.isClaimed(playerId, occurrence.key())
                    || ledger.hasPending(playerId, occurrence.key())) {
                continue;
            }
            if (session == null) {
                session = new PlayerQuestSession();
                sessions.put(playerId, session);
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

    /// Returns globally selected occurrences; claimed and pending state is filtered by callers.
    public List<QuestModel.Occurrence> available(UUID playerId) {
        return availableOccurrences;
    }

    public Optional<QuestModel.Occurrence> findOccurrence(UUID playerId, Identifier questId) {
        return available(playerId).stream()
                .filter(occurrence -> occurrence.definition().id().equals(questId))
                .findFirst();
    }

    /// Gets the mutable attempt and projects any durable claimed or pending state onto it.
    public QuestAttempt attempt(UUID playerId, QuestModel.Occurrence occurrence) {
        QuestAttempt attempt = sessions.computeIfAbsent(playerId, ignored -> new PlayerQuestSession()).getOrCreate(occurrence, server);
        if (ledger.isClaimed(playerId, occurrence.key())) {
            attempt.markClaimed();
        } else if (ledger.hasPending(playerId, occurrence.key())) {
            attempt.markPending();
        }
        return attempt;
    }

    public Optional<QuestAttempt> existingAttempt(UUID playerId, QuestModel.Key key) {
        PlayerQuestSession session = sessions.get(playerId);
        return session == null ? Optional.empty() : session.get(key);
    }

    /// Rebuilds availability and discards daily attempts whose occurrence is no longer active.
    public void rotationChanged() {
        rebuildAvailableOccurrences();
        java.util.Set<String> activeKeys = rotation.current().slots().values().stream()
                .map(occurrence -> occurrence.key().persistentKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        sessions.values().forEach(session -> session.removeDailyExcept(activeKeys));
    }

    public void reset(UUID playerId, QuestModel.Occurrence occurrence) {
        Optional.ofNullable(sessions.get(playerId)).ifPresent(session -> session.removeOccurrence(occurrence.key()));
        ledger.resetClaim(playerId, occurrence.key());
    }

    public void playerDisconnected(UUID playerId) {
        // Deliberately retained until shutdown: timed quests continue while the server runs.
    }

    /// Invalidates behavior changes while preserving attempts for presentation-only updates.
    private void onCatalogChanged(QuestCatalogManager.Update update) {
        rebuildAvailableOccurrences();
        for (Identifier id : update.diff().removed()) {
            sessions.values().forEach(session -> session.removeQuest(id));
        }
        for (Identifier id : update.diff().behaviorChanged()) {
            sessions.values().forEach(session -> session.removeQuest(id));
        }
        sessions.values().forEach(session -> session.updatePresentation(update.current().quests()));
    }

    /// Combines the current daily slots with one occurrence for every unique quest definition.
    private void rebuildAvailableOccurrences() {
        List<QuestModel.Occurrence> occurrences = new ArrayList<>(rotation.current().slots().values());
        for (QuestModel.Definition definition : catalogs.current().unique()) {
            QuestModel.Key key = new QuestModel.Key(
                    definition.id(), definition.behaviorHash(), new QuestModel.UniqueScope());
            occurrences.add(new QuestModel.Occurrence(
                    key, definition, Instant.EPOCH, Optional.empty()));
        }
        availableOccurrences = List.copyOf(occurrences);
    }

    @Override
    public void close() {
        catalogSubscription.close();
        sessions.clear();
    }
}
