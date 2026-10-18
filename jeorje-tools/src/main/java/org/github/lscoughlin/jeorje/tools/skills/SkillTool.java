package org.github.lscoughlin.jeorje.tools.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.github.lscoughlin.jeorje.core.instructions.Skill;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code skill} tool. The result is the body of the skill without the frontmatter, wrapped in
 * a {@code skill_content} element. The context classifier treats the results of this tool as
 * {@code PROTECTED}: it matches the tool name {@code skill}.
 *
 * <p>A second call for a skill that is still in the working context returns a short note. Call
 * {@link #release} when compaction removes a skill result, so that the next call loads it again.
 */
public final class SkillTool implements Tool {
    /** The name of the tool. */
    public static final String NAME = "skill";
    /** The most files in the resource list. */
    public static final int MAX_RESOURCES = 100;

    private static final Logger log = LoggerFactory.getLogger(SkillTool.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PLACEHOLDER = "$ARGUMENTS";

    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final Map<String, Set<String>> loaded = new ConcurrentHashMap<>();
    private final ToolDefinition definition;
    private final java.util.function.Consumer<Skill> onLoaded;

    private SkillTool(List<Skill> visible, java.util.function.Consumer<Skill> onLoaded) {
        this.onLoaded = onLoaded;
        visible.forEach(s -> skills.putIfAbsent(s.name(), s));
        this.definition = new ToolDefinition(NAME,
                "Load the instructions of a skill. Call it when the task matches the description of a skill. "
                        + "The result has the instructions and the list of the files of the skill.",
                schema());
    }

    /**
     * Makes the tool for the skills that the model may see. Returns empty when there is no skill:
     * Jeorje does not register the tool then.
     */
    public static Optional<SkillTool> create(List<Skill> visibleSkills) {
        return create(visibleSkills, null);
    }

    /**
     * Makes the tool. {@code onLoaded} is called when the model loads a skill for the first time, for example to
     * apply the {@code allowed-tools} field.
     */
    public static Optional<SkillTool> create(List<Skill> visibleSkills,
                                             java.util.function.Consumer<Skill> onLoaded) {
        if (visibleSkills == null || visibleSkills.isEmpty()) return Optional.empty();
        return Optional.of(new SkillTool(visibleSkills, onLoaded));
    }

    /** The skills that this tool can load. */
    public List<Skill> skills() { return List.copyOf(skills.values()); }

    /** The folders of the skills. Give them to the path sandbox as read-only folders. */
    public static List<Path> readableDirectories(List<Skill> skills) {
        return skills.stream().map(Skill::directory).distinct().toList();
    }

    private JsonNode schema() {
        var schema = JSON.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        var name = props.putObject("name");
        name.put("type", "string");
        name.put("description", "The name of the skill.");
        var values = name.putArray("enum");
        skills.keySet().forEach(values::add);
        var args = props.putObject("arguments");
        args.put("type", "string");
        args.put("description", "Optional text. It replaces $ARGUMENTS in the skill.");
        schema.putArray("required").add("name");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public ToolDefinition definition() { return definition; }

    @Override
    public boolean readOnly() { return true; }

    /** Marks a skill as no longer in the working context of an agent. */
    public void release(String agentId, String skillName) {
        var set = loaded.get(agentId);
        if (set != null) set.remove(skillName);
    }

    /** Marks all skills of an agent as no longer in the working context. */
    public void releaseAll(String agentId) { loaded.remove(agentId); }

    @Override
    public ToolResultData execute(JsonNode input, ToolContext context) {
        var name = input == null ? "" : input.path("name").asString("");
        var skill = skills.get(name);
        if (skill == null) {
            return ToolResultData.error("Unknown skill '" + name + "'. Valid names: " + String.join(", ", skills.keySet()));
        }
        var arguments = input.path("arguments").asString("");
        var agent = context.agent() == null ? "" : context.agent().toString();
        var set = loaded.computeIfAbsent(agent, k -> ConcurrentHashMap.newKeySet());
        if (!set.add(name)) {
            log.atDebug().log(() -> "Skill " + name + " is already in the context.");
            return ToolResultData.ok("The skill '" + name + "' is already in your context. Use the instructions above.");
        }
        if (onLoaded != null) onLoaded.accept(skill);
        return ToolResultData.ok(render(skill, arguments));
    }

    /** Makes the wrapped result for a skill. */
    static String render(Skill skill, String arguments) {
        var body = skill.body() == null ? "" : skill.body();
        var args = arguments == null ? "" : arguments;
        if (body.contains(PLACEHOLDER)) {
            body = body.replace(PLACEHOLDER, args);
        } else if (!args.isBlank()) {
            body = body + "\n\nARGUMENTS: " + args;
        }
        var out = new StringBuilder("<skill_content name=\"").append(skill.name()).append("\">\n");
        out.append(body.stripTrailing()).append('\n');
        if (skill.compatibility() != null && !skill.compatibility().isBlank()) {
            out.append("\nCompatibility: ").append(skill.compatibility().replaceAll("\\s+", " ").strip()).append('\n');
        }
        out.append("\nSkill directory: ").append(skill.directory()).append('\n');
        out.append("Relative paths in this skill are relative to the skill directory.\n\n");
        out.append("<skill_resources>\n");
        var files = resources(skill.directory());
        files.list().forEach(f -> out.append("  <file>").append(f).append("</file>\n"));
        if (files.truncated()) {
            out.append("  <note>The list stops at ").append(MAX_RESOURCES).append(" files.</note>\n");
        }
        out.append("</skill_resources>\n</skill_content>");
        return out.toString();
    }

    private record Resources(List<String> list, boolean truncated) {}

    private static Resources resources(Path dir) {
        var found = new ArrayList<String>();
        var truncated = false;
        try (var stream = Files.walk(dir)) {
            var all = stream.filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !p.equals("SKILL.md"))
                    .sorted().toList();
            truncated = all.size() > MAX_RESOURCES;
            found.addAll(all.subList(0, Math.min(all.size(), MAX_RESOURCES)));
        } catch (IOException e) {
            log.atWarn().log(() -> "Cannot list the files of " + dir + ": " + e.getMessage());
        }
        return new Resources(found, truncated);
    }
}
