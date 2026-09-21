package polycube.polyquest.resource;

import com.google.common.hash.Hashing;
import com.google.gson.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.StrictJsonParser;
import org.jspecify.annotations.Nullable;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.RewardApi;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Loads and compiles the complete PolyQuest datapack module behind one interface.
final class QuestResourceCompiler {
    private static final FileToIdConverter QUEST_FILES = FileToIdConverter.json("quests");
    private static final FileToIdConverter TEMPLATE_FILES = FileToIdConverter.json("quest_templates");
    private static final FileToIdConverter PROFILE_FILES = FileToIdConverter.json("reward_profiles");
    private static final Pattern TEMPLATE_PARAMETER = Pattern.compile("\\$\\{([^{}]*)}");

    private static final Codec<JsonObject> JSON_OBJECT_CODEC = ExtraCodecs.JSON.comapFlatMap(
            element -> element.isJsonObject()
                    ? DataResult.success(element.getAsJsonObject())
                    : DataResult.error(() -> "Expected an object root"),
            object -> object);
    private static final Codec<List<String>> TEMPLATE_PARAMETERS_CODEC = Codec.STRING.listOf()
            .validate(QuestResourceCompiler::validateTemplateParameters);
    private static final Codec<Template> TEMPLATE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            TEMPLATE_PARAMETERS_CODEC.optionalFieldOf("parameters", List.of()).forGetter(Template::parameters),
            JSON_OBJECT_CODEC.fieldOf("prototype").forGetter(Template::prototype)
    ).apply(instance, Template::new));
    private static final Codec<List<RewardApi.Definition>> PROFILE_CODEC = RewardApi.codec().listOf().fieldOf("rewards").codec();
    private static final Codec<TemplateInvocation> TEMPLATE_INVOCATION_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("template").forGetter(TemplateInvocation::template),
            Codec.unboundedMap(Codec.STRING, ExtraCodecs.JSON).optionalFieldOf("arguments", Map.of()).forGetter(TemplateInvocation::arguments)
    ).apply(instance, TemplateInvocation::new));

    private final QuestDefinitionValidator validator;

    QuestResourceCompiler() {
        validator = new QuestDefinitionValidator();
    }

    QuestResourceCompiler(HolderLookup.Provider registries) {
        validator = new QuestDefinitionValidator(registries);
    }

    /// Uses Mojang's effective resource view, then compiles one transactional catalog candidate.
    DataResult<QuestModel.Catalog> compile(ResourceManager manager, DynamicOps<JsonElement> ops) {
        List<String> errors = new ArrayList<>();
        ResourceSet resources = new ResourceSet(
                loadDirectory(manager, QUEST_FILES, "quest", errors),
                loadDirectory(manager, TEMPLATE_FILES, "quest template", errors),
                loadDirectory(manager, PROFILE_FILES, "reward profile", errors)
        );
        return compile(resources, ops, errors);
    }

    /// In-memory seam used by focused compiler tests.
    DataResult<QuestModel.Catalog> compile(ResourceSet resources, DynamicOps<JsonElement> ops) {
        return compile(resources, ops, new ArrayList<>());
    }

    private DataResult<QuestModel.Catalog> compile(
            ResourceSet resources, DynamicOps<JsonElement> ops, List<String> errors
    ) {
        Map<Identifier, Template> templates = decodeResources(
                resources.templates(), TEMPLATE_CODEC,
                ops, "Quest template", errors
        );
        Map<Identifier, List<RewardApi.Definition>> profileResources = decodeResources(
                resources.rewardProfiles(), PROFILE_CODEC,
                ops, "Reward profile", errors
        );
        Map<Identifier, RewardApi.Profile> profiles = new TreeMap<>();
        profileResources.forEach((id, rewards) -> {
            RewardApi.Profile profile = new RewardApi.Profile(id, rewards);
            errors.addAll(validator.validate(profile));
            profiles.put(id, profile);
        });

        Map<Identifier, QuestModel.Definition> quests = new TreeMap<>();
        for (var entry : new TreeMap<>(resources.quests()).entrySet()) {
            Identifier id = entry.getKey();
            JsonObject expanded = expandQuest(id, entry.getValue(), templates, new ArrayDeque<>(), errors);
            if (expanded == null) continue;

            QuestModel.@Nullable Body body = decode(QuestModel.Body.CODEC.parse(ops, expanded), "Quest '" + id + "'", errors);
            if (body == null) continue;

            JsonObject behavior = expanded.deepCopy();
            behavior.remove("title");
            behavior.remove("description");
            behavior.remove("icon");
            body.rewards().profile().ifPresent(profileId -> {
                JsonElement profileJson = resources.rewardProfiles().get(profileId);
                if (profileJson != null) {
                    behavior.add("_resolved_reward_profile", profileJson.deepCopy());
                }
            });

            QuestModel.Definition definition = QuestModel.Definition.fromBody(id, body, hash(behavior));
            errors.addAll(validator.validate(definition, profiles));
            quests.put(id, definition);
        }

        return errors.isEmpty()
                ? DataResult.success(new QuestModel.Catalog(quests, profiles))
                : DataResult.error(() -> "PolyQuest catalog compilation failed:\n - " + String.join("\n - ", errors));
    }

    /// Mirrors Mojang's JSON resource scan while retaining errors for all-or-nothing publication.
    private static Map<Identifier, JsonElement> loadDirectory(
            ResourceManager manager, FileToIdConverter files, String kind, List<String> errors
    ) {
        Map<Identifier, JsonElement> result = new TreeMap<>();
        for (var entry : new TreeMap<>(files.listMatchingResources(manager)).entrySet()) {
            Identifier fileId = entry.getKey();
            Identifier id = files.fileToId(fileId);
            Resource resource = entry.getValue();
            String source = kind + " '" + id + "' from '" + fileId + "' in pack '" + resource.sourcePackId() + "'";

            try (Reader reader = resource.openAsReader()) {
                JsonObject json = GsonHelper.convertToJsonObject(StrictJsonParser.parse(reader), "root");
                if (result.putIfAbsent(id, json) != null) errors.add("Duplicate " + source);
            } catch (JsonParseException | IllegalArgumentException | IOException exception) {
                errors.add("Could not read " + source + ": " + exception.getMessage());
            }
        }
        return result;
    }

    private static <T> Map<Identifier, T> decodeResources(
            Map<Identifier, JsonElement> resources, Codec<T> codec,
            DynamicOps<JsonElement> ops, String kind, List<String> errors
    ) {
        Map<Identifier, T> result = new TreeMap<>();
        for (var entry : new TreeMap<>(resources).entrySet()) {
            T decoded = decode(codec.parse(ops, entry.getValue()), kind + " '" + entry.getKey() + "'", errors);
            if (decoded != null) result.put(entry.getKey(), decoded);
        }
        return result;
    }

    /// Recursively expands template invocations, applies top-level overrides, and rejects cycles.
    private static @Nullable JsonObject expandQuest(
            Identifier questId, JsonElement raw, Map<Identifier, Template> templates,
            Deque<Identifier> expansionStack, List<String> errors
    ) {
        if (!raw.isJsonObject()) {
            errors.add("Quest '" + questId + "' must have an object root");
            return null;
        }
        JsonObject object = raw.getAsJsonObject();
        if (!object.has("template")) {
            return object.deepCopy();
        }
        TemplateInvocation invocation = decode(
                TEMPLATE_INVOCATION_CODEC.parse(JsonOps.INSTANCE, object),
                "Quest '" + questId + "' template invocation", errors
        );
        if (invocation == null) {
            return null;
        }
        Identifier templateId = invocation.template();
        Template template = templates.get(templateId);
        if (template == null) {
            errors.add("Quest '" + questId + "' references unknown template '" + templateId + "'");
            return null;
        }
        if (expansionStack.contains(templateId)) {
            errors.add("Quest '" + questId + "' has a template cycle: " + expansionStack + " -> " + templateId);
            return null;
        }

        Map<String, JsonElement> arguments = invocation.arguments();
        for (String parameter : template.parameters()) {
            if (!arguments.containsKey(parameter)) {
                errors.add("Quest '" + questId + "' is missing template argument '" + parameter + "'");
            }
        }
        for (String argument : arguments.keySet()) {
            if (!template.parameters().contains(argument)) {
                errors.add("Quest '" + questId + "' supplies unknown template argument '" + argument + "'");
            }
        }
        if (template.parameters().stream().anyMatch(parameter -> !arguments.containsKey(parameter))) {
            return null;
        }

        expansionStack.addLast(templateId);
        try {
            JsonElement substituted = substitute(template.prototype(), arguments, questId, errors);
            if (substituted == null || !substituted.isJsonObject()) return null;

            JsonObject expanded = substituted.getAsJsonObject();
            object.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals("template") && !entry.getKey().equals("arguments"))
                    .forEach(entry -> expanded.add(entry.getKey(), entry.getValue().deepCopy()));
            return expanded.has("template")
                    ? expandQuest(questId, expanded, templates, expansionStack, errors)
                    : expanded;
        } finally {
            expansionStack.removeLast();
        }
    }

    /// Preserves JSON types for whole markers and permits only primitive interpolation inside text.
    private static @Nullable JsonElement substitute(
            JsonElement value, Map<String, JsonElement> arguments,
            Identifier questId, List<String> errors
    ) {
        if (value.isJsonObject()) {
            JsonObject output = new JsonObject();
            value.getAsJsonObject().entrySet().forEach(entry -> {
                JsonElement replacement = substitute(entry.getValue(), arguments, questId, errors);
                if (replacement != null) output.add(entry.getKey(), replacement);

            });
            return output;
        }
        if (value.isJsonArray()) {
            JsonArray output = new JsonArray();
            for (JsonElement child : value.getAsJsonArray()) {
                JsonElement replacement = substitute(child, arguments, questId, errors);
                if (replacement != null) output.add(replacement);
            }
            return output;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return value.deepCopy();
        }

        String text = value.getAsString();
        Matcher matcher = TEMPLATE_PARAMETER.matcher(text);
        if (matcher.matches()) {
            JsonElement argument = arguments.get(matcher.group(1));
            if (argument != null) return argument.deepCopy();
            errors.add("Quest '" + questId + "' references unknown template parameter '" + matcher.group(1) + "'");
            return null;
        }

        matcher.reset();
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String parameter = matcher.group(1);
            JsonElement argument = arguments.get(parameter);
            if (argument == null) {
                errors.add("Quest '" + questId + "' references unknown template parameter '" + parameter + "'");
                return null;
            }
            if (!argument.isJsonPrimitive()) {
                errors.add("Quest '" + questId + "' can only interpolate primitive argument '" + parameter + "' inside text");
                return null;
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(argument.getAsString()));
        }
        matcher.appendTail(expanded);
        return new JsonPrimitive(expanded.toString());
    }

    private static DataResult<List<String>> validateTemplateParameters(List<String> parameters) {
        Set<String> names = new HashSet<>();
        for (String parameter : parameters) {
            if (parameter.isBlank()) return DataResult.error(() -> "Template parameter names cannot be blank");
            if (!names.add(parameter)) return DataResult.error(() -> "Repeated template parameter '" + parameter + "'");
        }
        return DataResult.success(parameters);
    }

    private static <T> @Nullable T decode(DataResult<T> result, String source, List<String> errors) {
        result.ifError(error -> errors.add(source + ": " + error.message()));
        return result.result().orElse(null);
    }

    private static String hash(JsonElement element) {
        return Hashing.sha256().hashString(GsonHelper.toStableString(element), StandardCharsets.UTF_8).toString();
    }

    record ResourceSet(
            Map<Identifier, JsonElement> quests,
            Map<Identifier, JsonElement> templates,
            Map<Identifier, JsonElement> rewardProfiles
    ) {
        ResourceSet {
            quests = Map.copyOf(quests);
            templates = Map.copyOf(templates);
            rewardProfiles = Map.copyOf(rewardProfiles);
        }
    }

    private record Template(List<String> parameters, JsonObject prototype) {
        private Template {
            parameters = List.copyOf(parameters);
            prototype = prototype.deepCopy();
        }
    }

    private record TemplateInvocation(Identifier template, Map<String, JsonElement> arguments) {
        private TemplateInvocation {
            arguments = Map.copyOf(arguments);
        }
    }
}
