package polycube.polyquest.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.advancements.triggers.PlayerTrigger;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import polycube.polyquest.runtime.ConditionRuntime;

final class AdvancementCriterionConditionTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void ownsRegistrationForItsEntireRuntimeLifecycle() {
        UUID playerId = UUID.randomUUID();
        Criterion<?> criterion = PlayerTrigger.TriggerInstance.tick();
        TestRegistration registration = new TestRegistration();
        ConditionRuntime.CriterionRegistrar registrar = (registeredPlayer, registeredCriterion) -> {
            assertEquals(playerId, registeredPlayer);
            assertSame(criterion, registeredCriterion);
            return registration;
        };

        ConditionRuntime.Instance instance = ConditionRuntime.create(
                new BuiltInConditions.AdvancementCriterion(criterion),
                new ConditionRuntime.CreationContext(() -> 0L, playerId, registrar));
        instance.reset();
        instance.close();

        assertEquals(1, registration.activations);
        assertEquals(1, registration.closes);
    }

    private static final class TestRegistration implements ConditionRuntime.CriterionRegistration {
        private static final Identifier ID = Identifier.fromNamespaceAndPath("test", "criterion");
        private int activations;
        private int closes;

        @Override
        public Identifier id() {
            return ID;
        }

        @Override
        public void activate() {
            activations++;
        }

        @Override
        public void deactivate() {
        }

        @Override
        public void close() {
            closes++;
        }
    }
}
