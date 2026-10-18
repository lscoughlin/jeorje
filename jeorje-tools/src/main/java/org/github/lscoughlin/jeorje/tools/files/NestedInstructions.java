package org.github.lscoughlin.jeorje.tools.files;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.github.lscoughlin.jeorje.core.instructions.InstructionsLoader;
import org.github.lscoughlin.jeorje.core.port.ToolContext;

/**
 * Delivers the nested {@code AGENTS.md} files (not the one in the workspace root) with the result of the first
 * {@code read}, {@code write}, or {@code edit} of a file in their directory. Each agent gets each file once for
 * the session. The root file is in the system prompt. The closest file comes last.
 */
public final class NestedInstructions {

    private final InstructionsLoader loader;
    private final Set<String> delivered = ConcurrentHashMap.newKeySet();

    /** @param maxBytes the size limit of one file ({@code instructions.maxBytes}) */
    public NestedInstructions(int maxBytes) {
        this.loader = new InstructionsLoader(List.of(), maxBytes, Integer.MAX_VALUE);
    }

    /** The text to put before the result of a tool call on {@code file}. It is empty when nothing is new. */
    public String deliver(ToolContext context, Path file) {
        var parent = file.getParent();
        if (parent == null) return "";
        var root = context.workingDirectory().toAbsolutePath().normalize();
        var agent = context.agent() == null ? "" : context.agent().toString();
        var out = new StringBuilder();
        for (var instruction : loader.directoryFiles(root, parent.toAbsolutePath().normalize())) {
            if (instruction.path().equals(file.toAbsolutePath().normalize())) continue;
            if (!delivered.add(agent + "|" + instruction.path())) continue;
            out.append("[directory instructions: ").append(FileSupport.display(context, instruction.path()))
                    .append("]\n").append(instruction.text().strip()).append("\n\n");
        }
        return out.toString();
    }
}
