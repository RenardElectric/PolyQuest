package polycube.polyquest.runtime;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
    private final ConditionRuntime.CriterionRegistrar criteria;
    private final Map<UUID, PlayerQuestSession> sessions = new HashMap<>();
    private List<QuestModel.Occurrence> availableOccurrences = List.of();

    public QuestEngine(
            MinecraftServer server,
            QuestCatalogManager catalogs,
            DailyRotationService rotation,
            QuestLedger ledger,
            ConditionRuntime.CriterionRegistrar criteria
    ) {
        this.server = server;
        this.catalogs = catalogs;
        this.rotation = rotation;
        this.ledger = ledger;
        this.criteria = criteria;
        rebuildAvailableOccurrences();
    }

    /// Routes a signal through the player's reconciled attempts, materializing them if it arrives before join setup.
    ProgressResult onSignal(QuestSignal signal) {
        var playerId = signal.player().getUUID();
        var session = sessions.get(playerId);
        var activation = new ProgressResult(false, List.of());
        if (session == null) {
            activation = activatePlayer(signal.player());
            session = sessions.get(playerId);
        }
        if (session == null) return activation;
        var changed = activation.changed();
        var completed = new ArrayList<>(activation.completed());
        for (QuestAttempt attempt : session.attempts()) {
            QuestModel.AttemptStatus before = attempt.status();
            changed |= attempt.onSignal(signal, server).changed();
            if (becameReady(before, attempt)) {
                ledger.markReady(playerId, attempt.occurrence());
                completed.add(new CompletedQuest(playerId, attempt.occurrence()));
                changed = true;
            }
        }
        return new ProgressResult(changed, completed);
    }

    /// Advances deadline-only nodes for online and offline in-memory sessions.
    ProgressResult tick(long serverTick) {
        var changed = false;
        var completed = new ArrayList<CompletedQuest>();
        for (var entry : sessions.entrySet()) {
            for (QuestAttempt attempt : entry.getValue().attempts()) {
                QuestModel.AttemptStatus before = attempt.status();
                changed |= attempt.tick(server, serverTick).changed();
                if (becameReady(before, attempt)) {
                    ledger.markReady(entry.getKey(), attempt.occurrence());
                    completed.add(new CompletedQuest(entry.getKey(), attempt.occurrence()));
                    changed = true;
                }
            }
        }
        return new ProgressResult(changed, completed);
    }

    private static boolean becameReady(QuestModel.AttemptStatus before, QuestAttempt attempt) {
        return before != QuestModel.AttemptStatus.READY_TO_CLAIM && attempt.status() == QuestModel.AttemptStatus.READY_TO_CLAIM;
    }

    record CompletedQuest(UUID playerId, QuestModel.Occurrence occurrence) {}

    record ProgressResult(boolean changed, List<CompletedQuest> completed) {
        ProgressResult {
            completed = List.copyOf(completed);
        }
    }

    /// Returns globally selected occurrences.
    public List<QuestModel.Occurrence> available() {
        return availableOccurrences;
    }

    public Optional<QuestModel.Occurrence> findOccurrence(Identifier questId) {
        return available().stream()
                .filter(occurrence -> occurrence.definition().id().equals(questId))
                .findFirst();
    }

    /// Gets the mutable attempt and projects any durable claimed or pending state onto it.
    public QuestAttempt attempt(UUID playerId, QuestModel.Occurrence occurrence) {
        var attempt = sessions
                .computeIfAbsent(playerId, id -> new PlayerQuestSession(id, criteria))
                .getOrCreate(occurrence, server);
        if (ledger.isClaimed(playerId, occurrence.key())) {
            attempt.markClaimed();
        } else if (ledger.hasPending(playerId, occurrence.key())) {
            attempt.markPending();
        } else if (ledger.isReady(playerId, occurrence)) {
            attempt.restoreReady();
        }
        return attempt;
    }

    public Optional<QuestAttempt> existingAttempt(UUID playerId, QuestModel.Key key) {
        var session = sessions.get(playerId);
        return session == null ? Optional.empty() : session.get(key);
    }

    /// Rebuilds availability and discards daily attempts whose occurrence is no longer active.
    void rotationChanged(Set<QuestModel.Difficulty> resetSlots) {
        rebuildAvailableOccurrences();
        ledger.retainReadyCompletions(activeBehaviorHashes());
        Set<QuestModel.Key> activeKeys = rotation.current().slots().values().stream()
                .map(QuestModel.Occurrence::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        sessions.values().forEach(session -> session.removeIf(attempt ->
                attempt.occurrence().key().scope() instanceof QuestModel.DailyScope scope
                        && (resetSlots.contains(scope.slot()) || !activeKeys.contains(attempt.occurrence().key()))));
        activateOnlinePlayers();
    }

    boolean reset(UUID playerId, QuestModel.Occurrence occurrence) {
        boolean attemptRemoved = Optional.ofNullable(sessions.get(playerId))
                .map(session -> session.removeOccurrence(occurrence.key()))
                .orElse(false);
        boolean changed = ledger.resetClaim(playerId, occurrence.key()) || attemptRemoved;
        if (changed) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                // A reset quest needs its fake criteria listening before the next gameplay event.
                activatePlayer(player);
            }
        }
        return changed;
    }

    /// Materializes current attempts and applies achievements the player has already unlocked.
    ProgressResult activatePlayer(ServerPlayer player) {
        var playerId = player.getUUID();
        for (var occurrence : available()) {
            if (!durablyCompleted(playerId, occurrence.key())) {
                attempt(playerId, occurrence);
            }
        }
        return reconcileCompletedAdvancements(player);
    }

    /// Replays already-unlocked vanilla advancements only into attempts that have not counted them.
    private ProgressResult reconcileCompletedAdvancements(ServerPlayer player) {
        var session = sessions.get(player.getUUID());
        if (session == null) return new ProgressResult(false, List.of());

        var tick = server.getTickCount();
        var unlocked = server.getAdvancements().getAllAdvancements().stream()
                .filter(holder -> player.getAdvancements().getOrStartProgress(holder).isDone())
                .map(holder -> new QuestSignal.Advancement(player, tick, holder))
                .toList();
        var completed = new ArrayList<CompletedQuest>();
        var changed = false;
        for (var attempt : session.attempts()) {
            var before = attempt.status();
            changed |= attempt.reconcileAdvancements(unlocked, server).changed();
            if (becameReady(before, attempt)) {
                ledger.markReady(player.getUUID(), attempt.occurrence());
                completed.add(new CompletedQuest(player.getUUID(), attempt.occurrence()));
            }
        }
        return new ProgressResult(changed, completed);
    }

    /// Reconciles live attempts with a reloaded catalog and reports players whose progress was reset due to a behavior change.
    List<UUID> onCatalogChanged(QuestCatalogManager.Update update) {
        rebuildAvailableOccurrences();
        Map<QuestModel.Key, QuestModel.Occurrence> currentOccurrences = new LinkedHashMap<>();
        for (QuestModel.Occurrence occurrence : availableOccurrences) {
            currentOccurrences.put(occurrence.key(), occurrence);
        }
        var currentKeys = currentOccurrences.keySet();
        var behaviorChanged = update.diff().behaviorChanged();
        Set<UUID> resets = new LinkedHashSet<>();

        for (QuestLedger.ReadyCompletion removed : ledger.retainReadyCompletions(activeBehaviorHashes())) {
            if (behaviorChanged.contains(removed.questId())) {
                resets.add(removed.playerId());
            }
        }

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
        activateOnlinePlayers();
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

    private Map<Identifier, String> activeBehaviorHashes() {
        var active = new HashMap<Identifier, String>();
        for (var occurrence : availableOccurrences) {
            active.put(occurrence.definition().id(), occurrence.definition().behaviorHash());
        }
        return active;
    }

    private void activateOnlinePlayers() {
        server.getPlayerList().getPlayers().forEach(this::activatePlayer);
    }

    @Override
    public void close() {
        sessions.values().forEach(PlayerQuestSession::close);
        sessions.clear();
    }
}
