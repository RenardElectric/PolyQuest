package polycube.polyquest.runtime;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import polycube.polyquest.claim.QuestClaimService;
import polycube.polyquest.commands.QuestCommandText;
import polycube.polyquest.config.QuestConfig;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.persistence.QuestLedger;
import polycube.polyquest.presentation.QuestDisplay;
import polycube.polyquest.resource.QuestCatalogManager;
import polycube.polyquest.reward.RewardApi;
import polycube.polyquest.rotation.DailyRotationService;
import polycube.polyquest.signal.QuestSignal;

import java.util.*;

/// Server-scoped facade used by Fabric callbacks, commands, and future UI adapters.
public final class QuestManager {
    private final MinecraftServer server;
    private final QuestCatalogManager catalogs;
    private final QuestLedger ledger;
    private final DailyRotationService rotation;
    private final AdvancementCriterionTracker advancementCriteria;
    private final QuestEngine engine;
    private final QuestClaimService claims;
    private final QuestChangeNotifier questChanges = new QuestChangeNotifier();
    private final QuestCatalogManager.Subscription catalogSubscription;

    public QuestManager(MinecraftServer server, QuestConfig config, QuestCatalogManager catalogs) {
        this.server = server;
        this.catalogs = catalogs;
        this.ledger = QuestLedger.load(server);
        this.rotation = new DailyRotationService(config);
        this.advancementCriteria = new AdvancementCriterionTracker(server);
        this.engine = new QuestEngine(server, catalogs, rotation, ledger, advancementCriteria);
        this.claims = new QuestClaimService(server, engine, ledger, catalogs, questChanges::changed);
        this.catalogSubscription = this.catalogs.addListener(this::onCatalogChanged);
        refreshRotationAndEngine();
    }

    public MinecraftServer server() {
        return server;
    }

    /// Registers a server-thread callback for every GUI-visible quest state change.
    /// The listener is not called immediately; callers should render their initial state first.
    public Subscription addListener(Runnable listener) {
        return questChanges.addListener(listener);
    }

    /// Runs rotation checks, timed conditions, and scheduled reward retries.
    public void tick() {
        long tick = server.getTickCount();
        if (tick % 20L == 0L) {
            refreshRotationAndEngine();
        }
        publishProgress(engine.tick(tick));
    }

    public void signal(QuestSignal signal) {
        publishProgress(engine.onSignal(signal));
    }

    public Set<QuestModel.Difficulty> reroll(List<QuestModel.Difficulty> difficulties) {
        var scheduled = refreshRotation().resetSlots();
        var changed = EnumSet.noneOf(QuestModel.Difficulty.class);
        for (var difficulty : difficulties) {
            if (rotation.reroll(difficulty, Optional.empty(), catalogs.current(), ledger) == DailyRotationService.RerollResult.CHANGED) {
                changed.add(difficulty);
            }
        }
        var allChanges = EnumSet.noneOf(QuestModel.Difficulty.class);
        allChanges.addAll(scheduled);
        allChanges.addAll(changed);
        publishRotation(allChanges);
        return Set.copyOf(changed);
    }

    public DailyRotationService.RerollResult reroll(QuestModel.Difficulty difficulty, Identifier questId) {
        var changed = EnumSet.noneOf(QuestModel.Difficulty.class);
        changed.addAll(refreshRotation().resetSlots());
        var result = rotation.reroll(difficulty, Optional.of(questId), catalogs.current(), ledger);
        if (result == DailyRotationService.RerollResult.CHANGED) changed.add(difficulty);
        publishRotation(changed);
        return result;
    }

    public List<Identifier> dailyQuestIds(QuestModel.Difficulty difficulty) {
        return catalogs.current().daily(difficulty).stream().map(QuestModel.Definition::id).toList();
    }

    public void onPlayerJoin(ServerPlayer player) {
        refreshRotationAndEngine();
        engine.playerJoined(player);
        // Fabric JOIN runs before PlayerList indexes the player, so bind newly created criteria directly.
        advancementCriteria.rebind(player);
        claims.retryPending(player, false);
        notifyQuestChange(player);
        sendUnclaimedSummary(player);
    }

    public void onPlayerDisconnect(ServerPlayer player) {
        advancementCriteria.disconnect(player);
    }

    /// Reattaches fake criteria after Minecraft rebuilds every player's advancement state.
    public void onDataPackReload() {
        server.getPlayerList().getPlayers().forEach(advancementCriteria::rebind);
    }

    public void shutdown() {
        catalogSubscription.close();
        engine.close();
        advancementCriteria.close();
        questChanges.clear();
    }

    /// Returns the list of quests currently available to the given player.
    public List<QuestModel.Occurrence> available() {
        return engine.available();
    }

    /// Returns the current attempt state for the given player and quest occurrence.
    public QuestAttempt attempt(NameAndId player, QuestModel.Occurrence occurrence) {
        return engine.attempt(player.id(), occurrence);
    }

    public Optional<QuestModel.Occurrence> findOccurrence(Identifier questId) {
        return engine.findOccurrence(questId);
    }

    /// Returns a reward profile from the currently active datapack catalog.
    public Optional<RewardApi.Profile> rewardProfile(Identifier profileId) {
        return Optional.ofNullable(catalogs.current().rewardProfiles().get(profileId));
    }

    public QuestModel.DailyAssignment dailyAssignment() {
        return rotation.current();
    }

    public QuestClaimService.ClaimResult claim(ServerPlayer player, Identifier questId) {
        refreshRotationAndEngine();
        return claims.claim(player, questId);
    }

    public QuestClaimService.ClaimResult forceClaim(ServerPlayer player, Identifier questId) {
        refreshRotationAndEngine();
        return claims.forceClaim(player, questId);
    }

    public boolean reset(NameAndId player, QuestModel.Occurrence occurrence) {
        boolean changed = engine.reset(player.id(), occurrence);
        if (changed) questChanges.changed();
        return changed;
    }

    private void onCatalogChanged(QuestCatalogManager.Update update) {
        DailyRotationService.RefreshResult rotationResult = refreshRotation();
        if (rotationResult.assignmentChanged() || !rotationResult.resetSlots().isEmpty()) {
            engine.rotationChanged(rotationResult.resetSlots());
        }
        var resetPlayers = engine.onCatalogChanged(update);
        if (!resetPlayers.isEmpty() && rotationResult.resetSlots().isEmpty()) ledger.clearRotationNotifications();
        if (!rotationResult.resetSlots().isEmpty() || !resetPlayers.isEmpty()) notifyOnlinePlayers();
        if (update.diff().hasChanges() || rotationResult.assignmentChanged() || !rotationResult.resetSlots().isEmpty()) {
            questChanges.changed();
        }
    }

    /// Converts a fake vanilla advancement award into the corresponding quest signal.
    public boolean interceptAdvancementCriterion(
            ServerPlayer player,
            AdvancementHolder holder,
            String criterionName
    ) {
        var award = advancementCriteria.intercept(player, holder, criterionName);
        award.registrationId().ifPresent(id -> signal(new QuestSignal.CriteriaMatched(player, server.getTickCount(), Set.of(id))));
        return award.intercepted();
    }

    public void beginAdvancementCriterionBatch(ServerPlayer player) {
        advancementCriteria.beginMatchBatch(player);
    }

    public void endAdvancementCriterionBatch(ServerPlayer player) {
        var matches = advancementCriteria.endMatchBatch(player);
        if (!matches.isEmpty()) {
            signal(new QuestSignal.CriteriaMatched(player, server.getTickCount(), matches));
        }
    }

    public boolean isClaimed(NameAndId player, QuestModel.Key key) {
        return ledger.isClaimed(player.id(), key);
    }

    private DailyRotationService.RefreshResult refreshRotation() {
        return rotation.refresh(catalogs.current(), ledger);
    }

    private void refreshRotationAndEngine() {
        var result = refreshRotation();
        if (result.assignmentChanged() || !result.resetSlots().isEmpty()) {
            engine.rotationChanged(result.resetSlots());
            if (!result.resetSlots().isEmpty()) notifyOnlinePlayers();
            questChanges.changed();
        }
    }

    private void publishRotation(Set<QuestModel.Difficulty> resetSlots) {
        if (resetSlots.isEmpty()) return;
        engine.rotationChanged(resetSlots);
        notifyOnlinePlayers();
        questChanges.changed();
    }

    private void notifyOnlinePlayers() {
        server.getPlayerList().getPlayers().forEach(this::notifyQuestChange);
    }

    private void notifyQuestChange(ServerPlayer player) {
        if (ledger.markRotationNotified(player.getUUID())) {
            player.sendSystemMessage(QuestCommandText.questChanged());
        }
    }

    /// Announces only fresh ready transitions; reconnects get a current summary instead.
    private void publishProgress(QuestEngine.ProgressResult progress) {
        if (progress.changed()) questChanges.changed();
        var notifiedPlayers = new HashSet<UUID>();
        for (var completed : progress.completed()) {
            var player = server.getPlayerList().getPlayer(completed.playerId());
            if (player == null) continue;
            player.sendSystemMessage(QuestCommandText.questCompleted(completed.occurrence(), questHover(player, completed.occurrence())));
            notifiedPlayers.add(completed.playerId());
        }
        for (var playerId : notifiedPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) sendUnclaimedSummary(player);
        }
    }

    private void sendUnclaimedSummary(ServerPlayer player) {
        var ready = engine.available().stream()
                .filter(occurrence -> engine.existingAttempt(player.getUUID(), occurrence.key())
                        .map(attempt -> attempt.status() == QuestModel.AttemptStatus.READY_TO_CLAIM)
                        .orElse(false))
                .toList();
        QuestCommandText.unclaimedSummary(ready, occurrence -> questHover(player, occurrence)).ifPresent(player::sendSystemMessage);
    }

    private Component questHover(ServerPlayer player, QuestModel.Occurrence occurrence) {
        return QuestDisplay.format(this, server, player.nameAndId(), occurrence).hoverText();
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
