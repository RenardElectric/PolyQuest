package polycube.polyquest;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.resource.v1.DataResourceLoader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import polycube.polycore.commands.PolyCommands;
import polycube.polyquest.commands.*;
import polycube.polyquest.condition.BuiltInConditions;
import polycube.polyquest.condition.CompositeConditions;
import polycube.polyquest.config.QuestConfig;
import polycube.polyquest.integration.FabricQuestEvents;
import polycube.polyquest.resource.QuestCatalogManager;
import polycube.polyquest.resource.QuestReloadListener;
import polycube.polyquest.reward.BuiltInRewards;
import polycube.polyquest.runtime.QuestRuntime;

/// PolyQuest's common entry point.
///
/// Initialization only registers codecs and reload listeners. Runtime services are
/// attached to a server later, through Fabric lifecycle callbacks.
public final class PolyQuest implements ModInitializer {
    public static final String MOD_ID = "polyquest";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final QuestCatalogManager CATALOGS = new QuestCatalogManager();

    @Override
    public void onInitialize() {
        BuiltInConditions.register();
        CompositeConditions.register();
        BuiltInRewards.register();

        var config = QuestConfig.load(FabricLoader.getInstance().getConfigDir().resolve("polyquest.json"));
        QuestRuntime.configure(config);

        DataResourceLoader.get().registerReloadListener(
                id("quests"),
                registries -> new QuestReloadListener(registries, CATALOGS)
        );

        FabricQuestEvents.register();
        PolyCommands.registerCommands(
                MOD_ID,
                "PolyQuest",
                LOGGER,
                new ListCommand(),
                new ClaimCommand(),
                new InspectCommand(),
                new SignalCommand(),
                new RerollCommand(),
                new ResetCommand(),
                new GuiCommand(),
                new PnjCommand()
        );

        LOGGER.info("PolyQuest initialized");
    }

    public static QuestCatalogManager catalogs() {
        return CATALOGS;
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
