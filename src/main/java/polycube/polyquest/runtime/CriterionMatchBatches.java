package polycube.polyquest.runtime;

import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/// Accumulates fake criterion matches until the outermost vanilla trigger returns.
final class CriterionMatchBatches {
    private final Map<UUID, Deque<Set<Identifier>>> byPlayer = new HashMap<>();

    void begin(UUID playerId) {
        byPlayer.computeIfAbsent(playerId, ignored -> new ArrayDeque<>()).push(new LinkedHashSet<>());
    }

    boolean add(UUID playerId, Identifier registrationId) {
        Deque<Set<Identifier>> batches = byPlayer.get(playerId);
        if (batches == null || batches.isEmpty()) {
            return false;
        }
        batches.peek().add(registrationId);
        return true;
    }

    Set<Identifier> end(UUID playerId) {
        Deque<Set<Identifier>> batches = byPlayer.get(playerId);
        if (batches == null || batches.isEmpty()) {
            return Set.of();
        }
        Set<Identifier> matches = batches.pop();
        if (!batches.isEmpty()) {
            batches.peek().addAll(matches);
            return Set.of();
        }
        byPlayer.remove(playerId);
        return Set.copyOf(matches);
    }

    boolean isActive(UUID playerId) {
        Deque<Set<Identifier>> batches = byPlayer.get(playerId);
        return batches != null && !batches.isEmpty();
    }

    void discard(UUID playerId) {
        byPlayer.remove(playerId);
    }

    void clear() {
        byPlayer.clear();
    }
}
