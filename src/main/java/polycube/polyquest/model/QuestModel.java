package polycube.polyquest.model;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import polycube.polyquest.condition.ConditionApi;
import polycube.polyquest.reward.RewardApi;

import java.time.Instant;
import java.util.*;

/// Immutable quest definitions and occurrence identities.
public final class QuestModel {
    public enum Availability implements StringRepresentable {
        DAILY("daily"),
        UNIQUE("unique");

        public static final Codec<Availability> CODEC = StringRepresentable.fromEnum(Availability::values);
        private final String serializedName;

        Availability(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }

    public enum Difficulty implements StringRepresentable {
        EASY("easy"),
        MEDIUM("medium"),
        HARD("hard");

        public static final Codec<Difficulty> CODEC = StringRepresentable.fromEnum(Difficulty::values);
        private final String serializedName;

        Difficulty(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }

        public static Optional<Difficulty> byName(String name) {
            for (Difficulty value : values()) {
                if (value.serializedName.equalsIgnoreCase(name)) {
                    return Optional.of(value);
                }
            }
            return Optional.empty();
        }
    }

    /// Decoded quest data before the resource path supplies its identifier and functional fingerprint.
    public record Body(
            Availability availability, Optional<Difficulty> difficulty, String title,
            List<String> description, Holder<Item> icon, ConditionApi.Definition condition, RewardApi.Plan rewards
    ) {
        public static final Codec<Body> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Availability.CODEC.fieldOf("availability").forGetter(Body::availability),
                Difficulty.CODEC.optionalFieldOf("difficulty").forGetter(Body::difficulty),
                Codec.STRING.fieldOf("title").forGetter(Body::title),
                Codec.STRING.listOf().optionalFieldOf("description", List.of()).forGetter(Body::description),
                Item.CODEC.fieldOf("icon").forGetter(Body::icon),
                ConditionApi.codec().fieldOf("condition").forGetter(Body::condition),
                RewardApi.Plan.CODEC.fieldOf("rewards").forGetter(Body::rewards)
        ).apply(instance, Body::new));

        public Body {
            description = List.copyOf(description);
        }
    }

    public record Definition(
            Identifier id, Availability availability, Optional<Difficulty> difficulty,
            String title, List<String> description, Item icon, ConditionApi.Definition condition,
            RewardApi.Plan rewards, String behaviorHash
    ) {
        public Definition {
            description = List.copyOf(description);
        }

        public static Definition fromBody(Identifier id, Body body, String behaviorHash) {
            return new Definition(
                    id, body.availability(), body.difficulty(),
                    body.title(), body.description(), body.icon().value(), body.condition(),
                    body.rewards(), behaviorHash
            );
        }
    }

    /// One appearance of a definition. Its durable identity is independent of definition edits.
    public record Occurrence(Key key, Definition definition, Instant availableFrom, Optional<Instant> availableUntil) {}

    public record Key(Identifier questId, Scope scope) {
        public String persistentKey() {
            return questId + "|" + scope.serialized();
        }
    }

    public sealed interface Scope permits DailyScope, UniqueScope {
        String serialized();
    }

    public record DailyScope(Difficulty slot, Instant nextRoll) implements Scope {
        @Override
        public String serialized() {
            return "daily:" + slot.getSerializedName() + ':' + nextRoll.toEpochMilli();
        }
    }

    public record UniqueScope() implements Scope {
        @Override
        public String serialized() {
            return "unique";
        }
    }

    public record Catalog(Map<Identifier, Definition> quests, Map<Identifier, RewardApi.Profile> rewardProfiles) {
        public static final Catalog EMPTY = new Catalog(Map.of(), Map.of());

        public Catalog {
            quests = Map.copyOf(new LinkedHashMap<>(quests));
            rewardProfiles = Map.copyOf(new LinkedHashMap<>(rewardProfiles));
        }

        public List<Definition> daily(Difficulty difficulty) {
            return quests.values().stream()
                    .filter(quest -> quest.availability() == Availability.DAILY)
                    .filter(quest -> quest.difficulty().filter(difficulty::equals).isPresent())
                    .sorted(java.util.Comparator.comparing(quest -> quest.id().toString()))
                    .toList();
        }

        public List<Definition> unique() {
            return quests.values().stream()
                    .filter(quest -> quest.availability() == Availability.UNIQUE)
                    .sorted(java.util.Comparator.comparing(quest -> quest.id().toString()))
                    .toList();
        }
    }

    public record DailyAssignment(Map<Difficulty, Occurrence> slots) {
        public DailyAssignment {
            EnumMap<Difficulty, Occurrence> copy = new EnumMap<>(Difficulty.class);
            copy.putAll(slots);
            slots = Map.copyOf(copy);
        }
    }

    public enum AttemptStatus {
        ACTIVE,
        READY_TO_CLAIM,
        CLAIM_PENDING,
        CLAIMED,
        EXHAUSTED
    }

    private QuestModel() {}
}
