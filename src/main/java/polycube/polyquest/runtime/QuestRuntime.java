package polycube.polyquest.runtime;

import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.config.QuestConfig;

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

    public static void stop(MinecraftServer server) {
        if (manager != null && manager.server() == server) {
            manager.shutdown();
            manager = null;
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
