package org.github.lscoughlin.jeorje.core.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the system prompt. A custom prompt changes only the identity section. The tool guidance, the
 * workspace description, the instructions, and the guidance for pruned chunks always stay.
 */
public final class SystemPromptBuilder {
    private static final Logger log = LoggerFactory.getLogger(SystemPromptBuilder.class);

    /** The built-in identity and behavior text. */
    public static final String DEFAULT_IDENTITY = """
        You are Jeorje, a coding agent. You work in the workspace of the user and you finish the tasks that the user gives you.
        Read the code before you change it. Make small, focused changes. Run the tests when you change behavior.
        Prefer native tools over using bash.
        Say what you did in short, plain sentences. If you are not sure, ask.""";

    /** The guidance for pruned chunks and the recall keyword hints. */
    public static final String PRUNED_GUIDANCE = """
        ## Pruned context
        Jeorje can prune old parts of this conversation to save space. A pruned part starts with a header like
        [<chunk id> ... | pruned L<level>]. It ends with a hint like [recall: keyword, keyword, id=<chunk id>].
        The full text is still in the history index. To get it back, call the recall tool with the keywords or with the chunk id.
        Do not guess the content of a pruned part. Recall it first.
        To find what the user said, asked, or chose, call the recall tool with the filter kind "user".""";

    private SystemPromptBuilder() {}

    /**
     * The parts of the prompt.
     *
     * @param identity the {@code prompts.system} config, or null
     * @param planMode the plan-mode section, or null outside plan mode
     */
    public record Parts(JeorjeConfig.Prompt identity, List<ToolDefinition> tools, Path workingDirectory,
                        String platform, LocalDate date, String instructions, String planMode) {}

    public static String build(Parts parts) {
        var sb = new StringBuilder();
        sb.append(identity(parts.identity())).append("\n\n");
        sb.append("## Tools\n").append(toolGuidance(parts.tools())).append("\n\n");
        sb.append("## Workspace\n")
            .append("Working directory: ").append(parts.workingDirectory()).append('\n')
            .append("Platform: ").append(parts.platform()).append('\n')
            .append("Date: ").append(parts.date()).append("\n\n");
        var instructions = parts.instructions() == null ? "" : parts.instructions().strip();
        if (!instructions.isEmpty()) sb.append("## Instructions\n").append(instructions).append("\n\n");
        sb.append(PRUNED_GUIDANCE);
        if (parts.planMode() != null && !parts.planMode().isBlank()) {
            sb.append("\n\n## Plan mode\n").append(parts.planMode().strip());
        }
        return sb.toString();
    }

    /** The identity section: the built-in text, with the config text appended or in its place. */
    static String identity(JeorjeConfig.Prompt prompt) {
        var custom = prompt == null ? "" : resolve(prompt);
        if (custom.isBlank()) return DEFAULT_IDENTITY;
        var mode = prompt.mode() == null ? "append" : prompt.mode();
        return switch (mode) {
            case "replace" -> custom.strip();
            default -> DEFAULT_IDENTITY + "\n\n" + custom.strip();
        };
    }

    private static String resolve(JeorjeConfig.Prompt prompt) {
        if (prompt.text() != null && !prompt.text().isBlank()) return prompt.text();
        if (prompt.file() == null || prompt.file().isBlank()) return "";
        try {
            return Files.readString(Path.of(prompt.file()), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.atWarn().log(() -> "Cannot read the system prompt file " + prompt.file() + ": " + e.getMessage());
            return "";
        }
    }

    private static String toolGuidance(List<ToolDefinition> tools) {
        var sb = new StringBuilder("Use a tool when you need facts about the workspace. Do not guess. "
            + "You can call several read-only tools in one message. Write tools run one after the other.\n");
        for (var tool : tools) sb.append("- ").append(tool.name()).append(": ").append(tool.description()).append('\n');
        return sb.toString().stripTrailing();
    }
}
