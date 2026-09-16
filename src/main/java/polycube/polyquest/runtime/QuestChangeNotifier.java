package polycube.polyquest.runtime;

import polycube.polyquest.PolyQuest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Dispatches coherent server-side quest state changes without coupling the runtime to a GUI.
final class QuestChangeNotifier {
    private final List<Runnable> listeners = new ArrayList<>();

    QuestManager.Subscription addListener(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    void changed() {
        for (Runnable listener : List.copyOf(listeners)) {
            try {
                listener.run();
            } catch (RuntimeException exception) {
                PolyQuest.LOGGER.error("Quest change listener failed", exception);
            }
        }
    }

    void clear() {
        listeners.clear();
    }
}
