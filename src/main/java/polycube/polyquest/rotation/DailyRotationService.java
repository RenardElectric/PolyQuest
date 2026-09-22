package polycube.polyquest.rotation;

import polycube.polyquest.config.QuestConfig;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.persistence.QuestLedger;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/// Deterministically selects one global quest per difficulty and calendar date.
public final class DailyRotationService {
    private final QuestConfig config;
    private QuestModel.DailyAssignment assignment = new QuestModel.DailyAssignment(LocalDate.MIN, Map.of());
    private QuestModel.@Nullable Catalog lastCatalog;

    public DailyRotationService(QuestConfig config) {
        this.config = config;
    }

    public QuestModel.DailyAssignment current() {
        return assignment;
    }

    /// Rebuilds today's slots while preserving valid same-generation selections across reloads.
    public RefreshResult refresh(QuestModel.Catalog catalog, QuestLedger ledger) {
        ZoneId zone = config.timeZone();
        LocalDate today = LocalDate.now(zone);
        boolean dateChanged = !today.equals(assignment.date());
        if (!dateChanged && catalog == lastCatalog && generationsMatch(ledger)) {
            return new RefreshResult(false, false);
        }
        if (dateChanged) {
            ledger.beginRotation(today);
        }

        EnumMap<QuestModel.Difficulty, QuestModel.Occurrence> slots = new EnumMap<>(QuestModel.Difficulty.class);
        ZonedDateTime start = today.atStartOfDay(zone);
        Instant availableFrom = start.toInstant();
        Instant availableUntil = start.plusDays(1).toInstant();

        for (QuestModel.Difficulty difficulty : QuestModel.Difficulty.values()) {
            List<QuestModel.Definition> candidates = catalog.daily(difficulty);
            if (candidates.isEmpty()) {
                continue;
            }
            int generation = ledger.rotationGeneration(difficulty);
            QuestModel.Definition selected = preservedSelection(today, difficulty, generation, catalog)
                    .orElseGet(() -> {
                        long seed = mixedSeed(today, difficulty, generation);
                        return candidates.get(new SplittableRandom(seed).nextInt(candidates.size()));
                    });
            QuestModel.DailyScope scope = new QuestModel.DailyScope(today, difficulty, generation);
            QuestModel.Key key = new QuestModel.Key(selected.id(), scope);
            slots.put(difficulty, new QuestModel.Occurrence(key, selected, availableFrom, java.util.Optional.of(availableUntil)));
        }

        QuestModel.DailyAssignment next = new QuestModel.DailyAssignment(today, slots);
        boolean changed = !sameOccurrences(assignment, next);
        assignment = next;
        lastCatalog = catalog;
        return new RefreshResult(changed, dateChanged);
    }

    /// Skips rebuilding selections when the date, catalog, and reroll generations are unchanged.
    private boolean generationsMatch(QuestLedger ledger) {
        for (var entry : assignment.slots().entrySet()) {
            var scope = (QuestModel.DailyScope) entry.getValue().key().scope();
            if (ledger.rotationGeneration(entry.getKey()) != scope.generation()) {
                return false;
            }
        }
        return true;
    }

    public boolean reroll(QuestModel.Difficulty difficulty, QuestModel.Catalog catalog, QuestLedger ledger) {
        if (catalog.daily(difficulty).isEmpty()) {
            return false;
        }
        ledger.incrementRotationGeneration(difficulty);
        return refresh(catalog, ledger).assignmentChanged();
    }

    /// Reuses a selection when it remains eligible for the same date and reroll generation.
    private Optional<QuestModel.Definition> preservedSelection(
            LocalDate date, QuestModel.Difficulty difficulty,
            int generation, QuestModel.Catalog catalog
    ) {
        Optional<QuestModel.Occurrence> previous = Optional.ofNullable(assignment.slots().get(difficulty));
        if (previous.isEmpty()
                || !(previous.get().key().scope() instanceof QuestModel.DailyScope previousScope)
                || !previousScope.date().equals(date)
                || previousScope.generation() != generation) {
            return Optional.empty();
        }
        return Optional.ofNullable(catalog.quests().get(previous.get().definition().id()))
                .filter(definition -> definition.availability() == QuestModel.Availability.DAILY)
                .filter(definition -> definition.difficulty().filter(difficulty::equals).isPresent());
    }

    /// Derives a stable independent seed for one date, difficulty, and reroll generation.
    private long mixedSeed(LocalDate date, QuestModel.Difficulty difficulty, int generation) {
        long value = config.dailySeed();
        value ^= date.toEpochDay() * 0x9E3779B97F4A7C15L;
        value = Long.rotateLeft(value, 21) ^ (difficulty.ordinal() * 0xC2B2AE3D27D4EB4FL);
        value = Long.rotateLeft(value, 17) ^ (generation * 0x165667B19E3779F9L);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return value;
    }

    private static boolean sameOccurrences(QuestModel.DailyAssignment first, QuestModel.DailyAssignment second) {
        if (!first.date().equals(second.date()) || first.slots().size() != second.slots().size()) {
            return false;
        }
        for (QuestModel.Difficulty difficulty : QuestModel.Difficulty.values()) {
            Optional<QuestModel.Key> firstKey = Optional.ofNullable(first.slots().get(difficulty)).map(QuestModel.Occurrence::key);
            Optional<QuestModel.Key> secondKey = Optional.ofNullable(second.slots().get(difficulty)).map(QuestModel.Occurrence::key);
            if (!firstKey.equals(secondKey)) {
                return false;
            }
        }
        return true;
    }

    /// Separates any assignment update from the actual calendar-day transition.
    public record RefreshResult(boolean assignmentChanged, boolean dateChanged) {}
}
