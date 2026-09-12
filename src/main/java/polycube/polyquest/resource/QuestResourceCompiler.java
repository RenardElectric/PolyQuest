package polycube.polyquest.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.Identifier;
import polycube.polyquest.model.QuestModel;
import polycube.polyquest.reward.RewardApi;

/// Expands templates, decodes registry-aware predicates, validates references,
/// and produces one immutable candidate catalog.
public final class QuestResourceCompiler {
    private final QuestDefinitionValidator validator = new QuestDefinitionValidator();

    public DataResult<QuestModel.Catalog> compile(
            QuestResourceLoader.ResourceSet resources,
            DynamicOps<JsonElement> ops) {
        List<String> errors = new ArrayList<>();
        Map<Identifier, Template> templates = decodeTemplates(resources.templates(), errors);
        Map<Identifier, RewardApi.Profile> profiles = decodeProfiles(resources.rewardProfiles(), ops, errors);
        Map<Identifier, QuestModel.Definition> quests = new TreeMap<>();

        resources.quests().forEach((id, raw) -> {
            JsonElement expanded = expandQuest(id, raw, templates, new ArrayDeque<>(), errors);
            if (expanded == null) {
                return;
            }

            QuestModel.Body body = decode(
                    QuestModel.Body.CODEC.parse(ops, expanded),
                    "Quest '" + id + "'",
                    errors);
            if (body == null) {
                return;
            }

            JsonObject normalized = expanded.getAsJsonObject().deepCopy();
            JsonObject behavior = normalized.deepCopy();
            behavior.remove("title");
            behavior.remove("description");
            body.rewards().profile().ifPresent(profileId -> {
                JsonElement profileJson = resources.rewardProfiles().get(profileId);
                if (profileJson != null) {
                    behavior.add("_resolved_reward_profile", profileJson.deepCopy());
                }
            });

            JsonObject presentation = new JsonObject();
            if (normalized.has("title")) {
                presentation.add("title", normalized.get("title"));
            }
            if (normalized.has("description")) {
                presentation.add("description", normalized.get("description"));
            }

            QuestModel.Definition definition = QuestModel.Definition.fromBody(
                    id,
                    body,
                    hash(behavior),
                    hash(presentation));
            errors.addAll(validator.validate(definition, profiles));
            quests.put(id, definition);
        });

        for (QuestModel.Difficulty difficulty : QuestModel.Difficulty.values()) {
            boolean present = quests.values().stream().anyMatch(quest ->
                    quest.availability() == QuestModel.Availability.DAILY
                            && quest.difficulty().orElse(null) == difficulty);
            if (!present) {
                errors.add("No daily quest is defined for difficulty '"
                        + difficulty.getSerializedName() + "'");
            }
        }

        if (!errors.isEmpty()) {
            return DataResult.error(() -> "PolyQuest catalog compilation failed:\n - "
                    + String.join("\n - ", errors));
        }
        return DataResult.success(new QuestModel.Catalog(quests, profiles));
    }

    private static Map<Identifier, Template> decodeTemplates(
            Map<Identifier, JsonElement> rawTemplates,
            List<String> errors) {
        Map<Identifier, Template> result = new TreeMap<>();
        rawTemplates.forEach((id, raw) -> {
            JsonObject object = raw.getAsJsonObject();
            if (!object.has("prototype") || !object.get("prototype").isJsonObject()) {
                errors.add("Quest template '" + id + "' requires an object field named 'prototype'");
                return;
            }

            Set<String> parameters = new TreeSet<>();
            JsonElement parameterElement = object.get("parameters");
            if (parameterElement != null) {
                if (!parameterElement.isJsonArray()) {
                    errors.add("Quest template '" + id + "'.parameters must be an array of strings");
                    return;
                }
                for (JsonElement entry : parameterElement.getAsJsonArray()) {
                    if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                        errors.add("Quest template '" + id + "'.parameters must contain only strings");
                        return;
                    }
                    parameters.add(entry.getAsString());
                }
            }
            result.put(id, new Template(parameters, object.getAsJsonObject("prototype").deepCopy()));
        });
        return result;
    }

    private static Map<Identifier, RewardApi.Profile> decodeProfiles(
            Map<Identifier, JsonElement> rawProfiles,
            DynamicOps<JsonElement> ops,
            List<String> errors) {
        Map<Identifier, RewardApi.Profile> result = new TreeMap<>();
        rawProfiles.forEach((id, raw) -> {
            JsonObject object = raw.getAsJsonObject();
            JsonElement rewards = object.get("rewards");
            if (rewards == null) {
                errors.add("Reward profile '" + id + "' requires a rewards field");
                return;
            }
            List<RewardApi.Definition> definitions = decode(
                    RewardApi.codec().listOf().parse(ops, rewards),
                    "Reward profile '" + id + "'",
                    errors);
            if (definitions != null) {
                if (definitions.isEmpty()) {
                    errors.add("Reward profile '" + id + "' cannot be empty");
                } else {
                    result.put(id, new RewardApi.Profile(id, definitions));
                }
            }
        });
        return result;
    }

    private static JsonElement expandQuest(
            Identifier questId,
            JsonElement raw,
            Map<Identifier, Template> templates,
            Deque<Identifier> expansionStack,
            List<String> errors) {
        JsonObject object = raw.getAsJsonObject();
        if (!object.has("template")) {
            return object.deepCopy();
        }
        if (!object.get("template").isJsonPrimitive()) {
            errors.add("Quest '" + questId + "'.template must be an identifier string");
            return null;
        }

        Identifier templateId;
        try {
            templateId = Identifier.parse(object.get("template").getAsString());
        } catch (IllegalArgumentException exception) {
            errors.add("Quest '" + questId + "' has invalid template identifier: " + exception.getMessage());
            return null;
        }
        Template template = templates.get(templateId);
        if (template == null) {
            errors.add("Quest '" + questId + "' references unknown template '" + templateId + "'");
            return null;
        }
        if (expansionStack.contains(templateId)) {
            errors.add("Quest '" + questId + "' has a template cycle: "
                    + expansionStack + " -> " + templateId);
            return null;
        }

        JsonObject arguments = object.has("arguments") && object.get("arguments").isJsonObject()
                ? object.getAsJsonObject("arguments")
                : new JsonObject();
        for (String parameter : template.parameters()) {
            if (!arguments.has(parameter)) {
                errors.add("Quest '" + questId + "' is missing template argument '" + parameter + "'");
            }
        }
        for (String argument : arguments.keySet()) {
            if (!template.parameters().contains(argument)) {
                errors.add("Quest '" + questId + "' supplies unknown template argument '" + argument + "'");
            }
        }
        if (template.parameters().stream().anyMatch(parameter -> !arguments.has(parameter))) {
            return null;
        }

        expansionStack.addLast(templateId);
        try {
            JsonElement substituted = substitute(template.prototype(), arguments, questId, errors);
            if (substituted == null || !substituted.isJsonObject()) {
                return null;
            }

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

    private static JsonElement substitute(
            JsonElement value,
            JsonObject arguments,
            Identifier questId,
            List<String> errors) {
        if (value.isJsonObject()) {
            JsonObject output = new JsonObject();
            value.getAsJsonObject().entrySet().forEach(entry -> {
                JsonElement replacement = substitute(entry.getValue(), arguments, questId, errors);
                if (replacement != null) {
                    output.add(entry.getKey(), replacement);
                }
            });
            return output;
        }
        if (value.isJsonArray()) {
            JsonArray output = new JsonArray();
            for (JsonElement child : value.getAsJsonArray()) {
                JsonElement replacement = substitute(child, arguments, questId, errors);
                if (replacement != null) {
                    output.add(replacement);
                }
            }
            return output;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return value.deepCopy();
        }

        String text = value.getAsString();
        if (text.startsWith("${") && text.endsWith("}") && text.indexOf("${") == 0
                && text.lastIndexOf('}') == text.length() - 1) {
            String parameter = text.substring(2, text.length() - 1);
            if (arguments.has(parameter)) {
                return arguments.get(parameter).deepCopy();
            }
        }

        String expanded = text;
        for (String parameter : arguments.keySet()) {
            String marker = "${" + parameter + "}";
            if (expanded.contains(marker)) {
                JsonElement argument = arguments.get(parameter);
                if (!argument.isJsonPrimitive()) {
                    errors.add("Quest '" + questId + "' can only interpolate primitive argument '"
                            + parameter + "' inside text");
                    return null;
                }
                expanded = expanded.replace(marker, argument.getAsString());
            }
        }
        return new JsonPrimitive(expanded);
    }

    private static <T> T decode(DataResult<T> result, String source, List<String> errors) {
        AtomicReference<T> value = new AtomicReference<>();
        result.ifSuccess(value::set);
        result.ifError(error -> errors.add(source + ": " + error.message()));
        return value.get();
    }

    private static String hash(JsonElement element) {
        String canonical = canonical(element);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(Character.forDigit((value >>> 4) & 0xF, 16));
                result.append(Character.forDigit(value & 0xF, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String canonical(JsonElement element) {
        if (element.isJsonObject()) {
            return element.getAsJsonObject().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> quote(entry.getKey()) + ':' + canonical(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (element.isJsonArray()) {
            return java.util.stream.StreamSupport.stream(element.getAsJsonArray().spliterator(), false)
                    .map(QuestResourceCompiler::canonical)
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        return element.toString();
    }

    private static String quote(String value) {
        return new JsonPrimitive(value).toString();
    }

    private record Template(Set<String> parameters, JsonObject prototype) {
    }
}
