package polycube.polyquest.resource;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.jspecify.annotations.Nullable;
import polycube.polyquest.PolyQuest;
import polycube.polyquest.model.QuestModel;

/// Transactional server-data reload listener for quests, templates, and profiles.
public final class QuestReloadListener extends SimpleReloadListener<QuestReloadListener.Prepared> {
    private final HolderLookup.Provider registries;
    private final QuestResourceLoader loader;
    private final QuestResourceCompiler compiler;
    private final QuestCatalogManager catalogs;

    public QuestReloadListener(HolderLookup.Provider registries, QuestResourceLoader loader, QuestResourceCompiler compiler, QuestCatalogManager catalogs) {
        this.registries = registries;
        this.loader = loader;
        this.compiler = compiler;
        this.catalogs = catalogs;
    }

    /// Reads and fully validates a candidate off-thread without touching live state.
    @Override
    protected Prepared prepare(PreparableReloadListener.SharedState state) {
        DynamicOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
        DataResult<QuestModel.Catalog> result = loader.load(state.resourceManager()).flatMap(resources -> compiler.compile(resources, ops));
        return Prepared.from(result);
    }

    /// Publishes only complete candidates. A failed reload leaves the old catalog active.
    @Override
    protected void apply(Prepared prepared, PreparableReloadListener.SharedState state) {
        if (prepared.catalog() != null) {
            catalogs.apply(prepared.catalog());
            PolyQuest.LOGGER.info(
                    "Loaded {} quests and {} reward profiles",
                    prepared.catalog().quests().size(),
                    prepared.catalog().rewardProfiles().size());
        } else {
            PolyQuest.LOGGER.error(
                    "PolyQuest datapack reload failed; keeping the previous catalog:\n{}",
                    prepared.error());
        }
    }

    public record Prepared(QuestModel.@Nullable Catalog catalog, String error) {
        private static Prepared from(DataResult<QuestModel.Catalog> result) {
            AtomicReference<QuestModel.@Nullable Catalog> catalog = new AtomicReference<>();
            AtomicReference<String> error = new AtomicReference<>("");
            result.ifSuccess(catalog::set);
            result.ifError(problem -> error.set(problem.message()));
            return new Prepared(catalog.get(), error.get());
        }
    }
}
