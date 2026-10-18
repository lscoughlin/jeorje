package org.github.lscoughlin.jeorje.core.instructions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Makes the instructions part of the system prompt: the {@code AGENTS.md} text and the skill catalog.
 * The loader checks the modification time of each file on every call, so the agent can call
 * {@link #text} before each request and an edit applies at once.
 */
public final class PromptInstructions {

    private final InstructionsLoader loader;
    private final Path fallbackRoot;
    private final Supplier<List<Skill>> skills;
    private final int maxListChars;

    /**
     * @param loader the loader of the {@code AGENTS.md} files, or null when the instructions are off
     * @param fallbackRoot the project directory, used when the working directory does not exist yet
     * @param skills the skills that the model may see, or null when skills are off
     * @param maxListChars the size limit of the catalog
     */
    public PromptInstructions(InstructionsLoader loader, Path fallbackRoot, Supplier<List<Skill>> skills,
                              int maxListChars) {
        this.loader = loader;
        this.fallbackRoot = fallbackRoot;
        this.skills = skills;
        this.maxListChars = maxListChars;
    }

    /** The text for an agent that works in the given directory. It is empty when there is nothing to say. */
    public String text(Path workingDirectory) {
        var root = workingDirectory != null && Files.isDirectory(workingDirectory) ? workingDirectory : fallbackRoot;
        var sb = new StringBuilder();
        if (loader != null) sb.append(loader.systemPrompt(root));
        var catalog = catalog();
        if (!catalog.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(catalog);
        }
        return sb.toString();
    }

    /** The skill catalog with the behavior block, or an empty text when no skill is available. */
    public String catalog() {
        if (skills == null) return "";
        var visible = skills.get();
        if (visible == null || visible.isEmpty()) return "";
        return SkillCatalog.build(visible, maxListChars).text();
    }
}
