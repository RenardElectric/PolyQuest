package polycube.polyquest.resource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import polycube.polyquest.model.QuestModel;

/// Owns the currently published immutable catalog and computes reload differences.
public final class QuestCatalogManager {
    private volatile QuestModel.Catalog current = QuestModel.Catalog.EMPTY;
    private final List<Consumer<Update>> listeners = new ArrayList<>();

    public QuestModel.Catalog current() {
        return current;
    }

    public void addListener(Consumer<Update> listener) {
        listeners.add(listener);
    }

    /// Must be called on the server thread from the reload listener's apply phase.
    public void apply(QuestModel.Catalog candidate) {
        QuestModel.Catalog previous = current;
        Diff diff = Diff.between(previous, candidate);
        current = candidate;
        Update update = new Update(previous, candidate, diff);
        listeners.forEach(listener -> listener.accept(update));
    }

    public record Update(QuestModel.Catalog previous, QuestModel.Catalog current, Diff diff) {}

    public record Diff(
            Set<Identifier> added,
            Set<Identifier> removed,
            Set<Identifier> behaviorChanged,
            Set<Identifier> presentationChanged,
            Set<Identifier> unchanged) {
        public Diff {
            added = Set.copyOf(added);
            removed = Set.copyOf(removed);
            behaviorChanged = Set.copyOf(behaviorChanged);
            presentationChanged = Set.copyOf(presentationChanged);
            unchanged = Set.copyOf(unchanged);
        }

        public static Diff between(QuestModel.Catalog oldCatalog, QuestModel.Catalog newCatalog) {
            Set<Identifier> oldIds = oldCatalog.quests().keySet();
            Set<Identifier> newIds = newCatalog.quests().keySet();
            Set<Identifier> added = new LinkedHashSet<>(newIds);
            added.removeAll(oldIds);
            Set<Identifier> removed = new LinkedHashSet<>(oldIds);
            removed.removeAll(newIds);
            Set<Identifier> behaviorChanged = new LinkedHashSet<>();
            Set<Identifier> presentationChanged = new LinkedHashSet<>();
            Set<Identifier> unchanged = new LinkedHashSet<>();

            oldIds.stream().filter(newIds::contains).sorted().forEach(id -> {
                QuestModel.Definition oldQuest = oldCatalog.quests().get(id);
                QuestModel.Definition newQuest = newCatalog.quests().get(id);
                if (!oldQuest.behaviorHash().equals(newQuest.behaviorHash())) {
                    behaviorChanged.add(id);
                } else if (!oldQuest.presentationHash().equals(newQuest.presentationHash())) {
                    presentationChanged.add(id);
                } else {
                    unchanged.add(id);
                }
            });
            return new Diff(added, removed, behaviorChanged, presentationChanged, unchanged);
        }
    }
}
