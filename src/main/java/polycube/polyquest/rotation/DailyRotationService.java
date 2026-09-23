package polycube.polyquest.rotation;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.config.QuestConfig;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.persistence.QuestLedger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/// Owns the three persisted daily slots and their aligned, timezone-local rotation schedule.
public final class DailyRotationService {
    private static final LocalDateTime MONDAY_ANCHOR = LocalDateTime.of(1970, 1, 5, 0, 0);

    private final QuestConfig config;
    private final Clock clock;
    private QuestModel.DailyAssignment assignment = new QuestModel.DailyAssignment(Map.of());

    public DailyRotationService(QuestConfig config) {
        this(config, Clock.systemUTC());
    }

    public DailyRotationService(QuestConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    public QuestModel.DailyAssignment current() {
        return assignment;
    }

    /// Keeps valid saved selections, replacing expired, malformed, or no-longer-eligible slots immediately.
    public RefreshResult refresh(QuestModel.Catalog catalog, QuestLedger ledger) {
        var now = clock.instant();
        var slots = new EnumMap<QuestModel.Difficulty, QuestModel.Occurrence>(QuestModel.Difficulty.class);
        var resets = java.util.EnumSet.noneOf(QuestModel.Difficulty.class);

        for (var difficulty : QuestModel.Difficulty.values()) {
            var frame = frameAt(now, config.timeZone(), config.rotationHours(difficulty));
            var saved = ledger.rotationSlot(difficulty);
            var previousId = saved.flatMap(slot -> parseQuestId(slot, difficulty));
            var selected = previousId.map(catalog.quests()::get).filter(quest -> eligible(quest, difficulty)).orElse(null);
            var deadlineValid = saved.flatMap(slot -> parseDeadline(slot, difficulty)).filter(frame.end()::equals).isPresent();
            var needsReplacement = !deadlineValid || saved.get().quest().isPresent() && selected == null || selected == null && !catalog.daily(difficulty).isEmpty();

            if (needsReplacement) {
                selected = select(catalog.daily(difficulty), previousId.orElse(null), new SplittableRandom(mixedSeed(frame.end(), difficulty)));
                var visibleChange = saved.flatMap(QuestLedger.RotationSlot::quest).isPresent() || selected != null;
                ledger.setRotationSlot(difficulty, Optional.ofNullable(selected).map(QuestModel.Definition::id), frame.end(), visibleChange);
                if (visibleChange) resets.add(difficulty);
            }
            if (selected != null) slots.put(difficulty, occurrence(selected, difficulty, frame));
        }

        var next = new QuestModel.DailyAssignment(slots);
        boolean assignmentChanged = !assignment.equals(next);
        assignment = next;
        return new RefreshResult(resets, assignmentChanged);
    }

    /// Replaces one slot without changing its scheduled deadline. An explicit current ID is a no-op.
    public RerollResult reroll(
            QuestModel.Difficulty difficulty, Optional<Identifier> requested,
            QuestModel.Catalog catalog, QuestLedger ledger
    ) {
        var candidates = catalog.daily(difficulty);
        if (candidates.isEmpty()) return RerollResult.NO_CANDIDATES;

        var current = assignment.slots().get(difficulty);
        var previousId = current == null ? null : current.definition().id();
        QuestModel.Definition selected;
        if (requested.isPresent()) {
            selected = candidates.stream().filter(quest -> quest.id().equals(requested.get())).findFirst().orElse(null);
            if (selected == null) return RerollResult.INVALID_QUEST;
            if (selected.id().equals(previousId)) return RerollResult.ALREADY_SELECTED;
        } else {
            selected = select(candidates, previousId, ThreadLocalRandom.current());
        }

        var frame = frameAt(clock.instant(), config.timeZone(), config.rotationHours(difficulty));
        ledger.setRotationSlot(difficulty, Optional.ofNullable(selected).map(QuestModel.Definition::id), frame.end(), true);
        var slots = new EnumMap<QuestModel.Difficulty, QuestModel.Occurrence>(QuestModel.Difficulty.class);
        slots.putAll(assignment.slots());
        if (selected != null) slots.put(difficulty, occurrence(selected, difficulty, frame));
        assignment = new QuestModel.DailyAssignment(slots);
        return RerollResult.CHANGED;
    }

    private static boolean eligible(QuestModel.Definition quest, QuestModel.Difficulty difficulty) {
        return quest.availability() == QuestModel.Availability.DAILY
                && quest.difficulty().filter(difficulty::equals).isPresent();
    }

    private static QuestModel.@Nullable Definition select(List<QuestModel.Definition> candidates, @Nullable Identifier previousId, RandomGenerator random) {
        if (candidates.isEmpty()) return null;
        var choices = candidates;
        if (previousId != null && candidates.size() > 1) {
            choices = new ArrayList<>(candidates);
            choices.removeIf(quest -> quest.id().equals(previousId));
        }
        return choices.get(random.nextInt(choices.size()));
    }

    private static QuestModel.Occurrence occurrence(QuestModel.Definition quest, QuestModel.Difficulty difficulty, Frame frame) {
        QuestModel.Key key = new QuestModel.Key(quest.id(), new QuestModel.DailyScope(difficulty, frame.end()));
        return new QuestModel.Occurrence(key, quest, frame.start(), Optional.of(frame.end()));
    }

    private static Optional<Identifier> parseQuestId(QuestLedger.RotationSlot slot, QuestModel.Difficulty difficulty) {
        return slot.quest().flatMap(raw -> {
            var id = Identifier.tryParse(raw);
            if (id == null) PolyQuest.LOGGER.warn("Invalid saved {} daily quest ID '{}'; selecting a replacement", difficulty.getSerializedName(), raw);
            return Optional.ofNullable(id);
        });
    }

    private static Optional<Instant> parseDeadline(QuestLedger.RotationSlot slot, QuestModel.Difficulty difficulty) {
        return slot.nextRoll().flatMap(raw -> {
            try {
                return Optional.of(Instant.parse(raw));
            } catch (DateTimeParseException exception) {
                PolyQuest.LOGGER.warn("Invalid saved {} next-roll time '{}'; rotating now",
                        difficulty.getSerializedName(), raw);
                return Optional.empty();
            }
        });
    }

    /// Uses local calendar hours so midnight and Monday stay aligned across daylight-saving changes.
    private static Frame frameAt(Instant now, ZoneId zone, int intervalHours) {
        var localNow = LocalDateTime.ofInstant(now, zone);
        var elapsedHours = ChronoUnit.HOURS.between(MONDAY_ANCHOR, localNow);
        var frameIndex = Math.floorDiv(elapsedHours, intervalHours);
        var start = MONDAY_ANCHOR.plusHours(frameIndex * intervalHours);
        var end = start.plusHours(intervalHours);
        return new Frame(start.atZone(zone).toInstant(), end.atZone(zone).toInstant());
    }

    private long mixedSeed(Instant deadline, QuestModel.Difficulty difficulty) {
        var value = config.dailySeed() ^ deadline.toEpochMilli();
        value = Long.rotateLeft(value, 21) ^ (difficulty.ordinal() * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        return value ^ value >>> 33;
    }

    private record Frame(Instant start, Instant end) {}

    public record RefreshResult(Set<QuestModel.Difficulty> resetSlots, boolean assignmentChanged) {
        public RefreshResult {
            resetSlots = Set.copyOf(resetSlots);
        }
    }

    public enum RerollResult {
        CHANGED,
        ALREADY_SELECTED,
        INVALID_QUEST,
        NO_CANDIDATES
    }
}
