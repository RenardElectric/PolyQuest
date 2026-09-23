package polycube.polyquest.resource;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.model.QuestModel;

/// Server-data reload listener that publishes valid resources and logs rejected ones.
public final class QuestReloadListener extends SimpleReloadListener<DataResult<QuestModel.Catalog>> {
    private final HolderLookup.Provider registries;
    private final QuestCatalogManager catalogs;

    public QuestReloadListener(HolderLookup.Provider registries, QuestCatalogManager catalogs) {
        this.registries = registries;
        this.catalogs = catalogs;
    }

    /// Reads and validates resources off-thread without touching live state.
    @Override
    protected DataResult<QuestModel.Catalog> prepare(PreparableReloadListener.SharedState state) {
        DynamicOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
        return new QuestResourceCompiler(registries).compile(state.resourceManager(), ops);
    }

    /// Publishes valid definitions even if other datapack files are invalid.
    @Override
    protected void apply(
            DataResult<QuestModel.Catalog> prepared,
            PreparableReloadListener.SharedState state
    ) {
        prepared.resultOrPartial(PolyQuest.LOGGER::error).ifPresent(catalog -> {
            catalogs.apply(catalog);
            PolyQuest.LOGGER.info(
                    "Loaded {} quests and {} reward profiles",
                    catalog.quests().size(),
                    catalog.rewardProfiles().size());
        });
    }
}
