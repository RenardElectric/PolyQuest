package polycube.polyquest.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import polycube.polyquest.condition.BuiltInConditions;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.RewardApi;

final class QuestCatalogManagerTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void diffSeparatesBehaviorAndPresentationChanges() {
        Identifier behaviorId = id("behavior");
        Identifier presentationId = id("presentation");
        QuestModel.Catalog before = catalog(
                definition(behaviorId, "before", "same"),
                definition(presentationId, "same", "before"));
        QuestModel.Catalog after = catalog(
                definition(behaviorId, "after", "same"),
                definition(presentationId, "same", "after"));

        QuestCatalogManager.Diff diff = QuestCatalogManager.Diff.between(before, after);

        assertEquals(java.util.Set.of(behaviorId), diff.behaviorChanged());
        assertEquals(java.util.Set.of(presentationId), diff.presentationChanged());
    }

    @Test
    void closedSubscriptionIsNotCalledOnLaterReloads() {
        QuestCatalogManager manager = new QuestCatalogManager();
        AtomicInteger calls = new AtomicInteger();
        QuestCatalogManager.Subscription subscription = manager.addListener(ignored -> calls.incrementAndGet());

        manager.apply(QuestModel.Catalog.EMPTY);
        subscription.close();
        manager.apply(QuestModel.Catalog.EMPTY);

        assertEquals(1, calls.get());
        assertTrue(manager.current().quests().isEmpty());
    }

    private static QuestModel.Catalog catalog(QuestModel.Definition... definitions) {
        return new QuestModel.Catalog(
                java.util.Arrays.stream(definitions)
                        .collect(java.util.stream.Collectors.toMap(QuestModel.Definition::id, value -> value)),
                Map.of());
    }

    private static QuestModel.Definition definition(
            Identifier id,
            String behaviorHash,
            String presentationHash) {
        return new QuestModel.Definition(
                id,
                QuestModel.Availability.UNIQUE,
                Optional.empty(),
                "Test",
                List.of(),
                Items.SUNFLOWER,
                new BuiltInConditions.ExplicitSignal(id, 1),
                new RewardApi.Plan(Optional.empty(), List.of()),
                behaviorHash,
                presentationHash);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("polyquest_test", path);
    }
}
