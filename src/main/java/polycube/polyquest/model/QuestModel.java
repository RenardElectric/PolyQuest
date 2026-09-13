package polycube.polyquest.model;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import polycube.polyquest.condition.ConditionApi;
import polycube.polyquest.reward.RewardApi;

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

    /// Decoded quest data before the resource path supplies its identifier and hashes.
    public record Body(
            Availability availability,
            Optional<Difficulty> difficulty,
            String title,
            List<String> description,
            ConditionApi.Definition condition,
            RewardApi.Plan rewards) {
        public static final Codec<Body> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Availability.CODEC.fieldOf("availability").forGetter(Body::availability),
                Difficulty.CODEC.optionalFieldOf("difficulty").forGetter(Body::difficulty),
                Codec.STRING.fieldOf("title").forGetter(Body::title),
                Codec.STRING.listOf().optionalFieldOf("description", List.of()).forGetter(Body::description),
                ConditionApi.codec().fieldOf("condition").forGetter(Body::condition),
                RewardApi.Plan.CODEC.fieldOf("rewards").forGetter(Body::rewards)
        ).apply(instance, Body::new));

        public Body {
            Objects.requireNonNull(availability, "availability");
            Objects.requireNonNull(difficulty, "difficulty");
            Objects.requireNonNull(title, "title");
            description = List.copyOf(description);
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(rewards, "rewards");
        }
    }

    public record Definition(
            Identifier id, Availability availability, Optional<Difficulty> difficulty,
            String title, List<String> description, ConditionApi.Definition condition,
            RewardApi.Plan rewards, String behaviorHash, String presentationHash
    ) {
        public Definition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(availability, "availability");
            Objects.requireNonNull(difficulty, "difficulty");
            Objects.requireNonNull(title, "title");
            description = List.copyOf(description);
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(rewards, "rewards");
            Objects.requireNonNull(behaviorHash, "behaviorHash");
            Objects.requireNonNull(presentationHash, "presentationHash");
        }

        public static Definition fromBody(Identifier id, Body body, String behaviorHash, String presentationHash) {
            return new Definition(
                    id, body.availability(), body.difficulty(),
                    body.title(), body.description(), body.condition(),
                    body.rewards(), behaviorHash, presentationHash
            );
        }
    }

    /// One appearance of a definition. Daily occurrences are scoped to a date and slot;
    /// unique occurrences are scoped only to their current behavior revision.
    public record Occurrence(Key key, Definition definition, Instant availableFrom, Optional<Instant> availableUntil) {
        public Occurrence {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(availableFrom, "availableFrom");
            Objects.requireNonNull(availableUntil, "availableUntil");
        }
    }

    public record Key(Identifier questId, String behaviorHash, Scope scope) {
        public Key {
            Objects.requireNonNull(questId, "questId");
            Objects.requireNonNull(behaviorHash, "behaviorHash");
            Objects.requireNonNull(scope, "scope");
        }

        public String persistentKey() {
            return questId + "|" + behaviorHash + "|" + scope.serialized();
        }
    }

    public sealed interface Scope permits DailyScope, UniqueScope {
        String serialized();
    }

    public record DailyScope(LocalDate date, Difficulty slot, int generation) implements Scope {
        public DailyScope {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(slot, "slot");
        }

        @Override
        public String serialized() {
            return "daily:" + date + ':' + slot.getSerializedName() + ':' + generation;
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

    public record DailyAssignment(LocalDate date, Map<Difficulty, Occurrence> slots) {
        public DailyAssignment {
            Objects.requireNonNull(date, "date");
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
