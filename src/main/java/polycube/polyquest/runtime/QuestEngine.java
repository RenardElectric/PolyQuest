package polycube.polyquest.runtime;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.persistence.QuestLedger;
import polycube.polyquest.resource.QuestCatalogManager;
import polycube.polyquest.rotation.DailyRotationService;
import polycube.polyquest.signal.QuestSignal;

import java.time.Instant;
import java.util.*;

/// Routes normalized events to the active per-player condition trees.
public final class QuestEngine implements AutoCloseable {
    private final MinecraftServer server;
    private final QuestCatalogManager catalogs;
    private final DailyRotationService rotation;
    private final QuestLedger ledger;
    private final Map<UUID, PlayerQuestSession> sessions = new HashMap<>();
    private List<QuestModel.Occurrence> availableOccurrences = List.of();

    public QuestEngine(MinecraftServer server, QuestCatalogManager catalogs, DailyRotationService rotation, QuestLedger ledger) {
        this.server = server;
        this.catalogs = catalogs;
        this.rotation = rotation;
        this.ledger = ledger;
        rebuildAvailableOccurrences();
    }

    /// Fans a player signal into every available, unclaimed occurrence, creating attempts lazily.
    boolean onSignal(QuestSignal signal) {
        UUID playerId = signal.player().getUUID();
        PlayerQuestSession session = sessions.get(playerId);
        boolean changed = false;
        for (QuestModel.Occurrence occurrence : available(playerId)) {
            if (ledger.isClaimed(playerId, occurrence.key())
                    || ledger.hasPending(playerId, occurrence.key())) {
                continue;
            }
            if (session == null) {
                session = new PlayerQuestSession();
                sessions.put(playerId, session);
            }
            changed |= session.getOrCreate(occurrence, server).onSignal(signal, server).changed();
        }
        return changed;
    }

    /// Advances deadline-only nodes for online and offline in-memory sessions.
    boolean tick(long serverTick) {
        boolean changed = false;
        for (PlayerQuestSession session : sessions.values()) {
            for (QuestAttempt attempt : session.attempts()) {
                changed |= attempt.tick(server, serverTick).changed();
            }
        }
        return changed;
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
    void rotationChanged() {
        rebuildAvailableOccurrences();
        Set<QuestModel.Key> activeKeys = rotation.current().slots().values().stream()
                .map(QuestModel.Occurrence::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        sessions.values().forEach(session -> session.removeIf(attempt ->
                attempt.occurrence().key().scope() instanceof QuestModel.DailyScope
                        && !activeKeys.contains(attempt.occurrence().key())));
    }

    boolean reset(UUID playerId, QuestModel.Occurrence occurrence) {
        boolean attemptRemoved = Optional.ofNullable(sessions.get(playerId))
                .map(session -> session.removeOccurrence(occurrence.key()))
                .orElse(false);
        return ledger.resetClaim(playerId, occurrence.key()) || attemptRemoved;
    }

    /// Reconciles live attempts with a reloaded catalog and reports players whose progress was reset due to a behavior change.
    List<UUID> onCatalogChanged(QuestCatalogManager.Update update) {
        rebuildAvailableOccurrences();
        Map<QuestModel.Key, QuestModel.Occurrence> currentOccurrences = new LinkedHashMap<>();
        for (QuestModel.Occurrence occurrence : availableOccurrences) {
            currentOccurrences.put(occurrence.key(), occurrence);
        }
        Set<QuestModel.Key> currentKeys = currentOccurrences.keySet();
        Set<Identifier> behaviorChanged = update.diff().behaviorChanged();
        List<UUID> resets = new ArrayList<>();

        for (Map.Entry<UUID, PlayerQuestSession> entry : sessions.entrySet()) {
            UUID playerId = entry.getKey();
            List<QuestAttempt> removed = entry.getValue().removeIf(attempt -> {
                QuestModel.Key key = attempt.occurrence().key();
                boolean available = currentKeys.contains(key);
                boolean changed = behaviorChanged.contains(key.questId());
                return !available || changed && !durablyCompleted(playerId, key);
            });
            for (QuestAttempt attempt : removed) {
                Identifier questId = attempt.occurrence().definition().id();
                if (behaviorChanged.contains(questId)
                        && !durablyCompleted(playerId, attempt.occurrence().key())
                        && attempt.hasProgress()) {
                    resets.add(playerId);
                }
            }
            entry.getValue().updateOccurrences(currentOccurrences);
        }
        return List.copyOf(resets);
    }

    /// Combines the current daily slots with one occurrence for every unique quest definition.
    private void rebuildAvailableOccurrences() {
        List<QuestModel.Occurrence> occurrences = new ArrayList<>(rotation.current().slots().values());
        for (QuestModel.Definition definition : catalogs.current().unique()) {
            QuestModel.Key key = new QuestModel.Key(definition.id(), new QuestModel.UniqueScope());
            occurrences.add(new QuestModel.Occurrence(key, definition, Instant.EPOCH, Optional.empty()));
        }
        availableOccurrences = List.copyOf(occurrences);
    }

    private boolean durablyCompleted(UUID playerId, QuestModel.Key key) {
        return ledger.isClaimed(playerId, key) || ledger.hasPending(playerId, key);
    }

    @Override
    public void close() {
        sessions.clear();
    }
}
