package polycube.polyquest.runtime;

import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.config.QuestConfig;
import polycube.polyquest.model.QuestModel;

/// Process-local holder for the one integrated server's quest manager.
public final class QuestRuntime {
    private static @Nullable QuestConfig config;
    private static @Nullable QuestManager manager;

    public static void configure(QuestConfig value) {
        config = value;
    }

    public static void start(MinecraftServer server) {
        if (manager != null) {
            throw new IllegalStateException("PolyQuest server runtime is already active");
        }
        if (config == null) {
            throw new IllegalStateException("PolyQuest config was not initialized");
        }
        manager = new QuestManager(server, config, PolyQuest.catalogs());
        PolyQuest.LOGGER.info("PolyQuest runtime started with {} quest definitions", PolyQuest.catalogs().current().quests().size());
    }

    /// Shuts down only the matching server runtime and clears its published catalog.
    public static void stop(MinecraftServer server) {
        if (manager != null && manager.server() == server) {
            manager.shutdown();
            manager = null;
            PolyQuest.catalogs().apply(QuestModel.Catalog.EMPTY);
        }
    }

    public static Optional<QuestManager> manager() {
        return Optional.ofNullable(manager);
    }

    public static void ifPresent(Consumer<QuestManager> action) {
        if (manager != null) {
            action.accept(manager);
        }
    }

    private QuestRuntime() {}
}
