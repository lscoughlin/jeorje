package org.github.lscoughlin.jeorje.tools.files;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.diff.SplitText;
import org.github.lscoughlin.jeorje.core.diff.TextDiffException;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.state.AgentState;
import org.github.lscoughlin.jeorje.core.state.StateUpdate;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;

/**
 * The {@code read} tool. It returns the directory context, the front matter, and the numbered
 * lines of a file. It does not write shared state. It returns the updates in the result.
 */
public final class ReadTool extends RecordTool<ReadTool.Input> {
    static final int DEFAULT_LIMIT = 2000;
    static final int MAX_LINE_CHARS = 2000;
    private static final Set<String> DATA_EXTENSIONS = Set.of(".yaml", ".yml", ".json");

    /** The input of {@code read}. */
    public record Input(
            @Describe("Path of the file. A relative path is relative to the working directory.") String path,
            @Describe("Line number to start at, from 1. Default 1.") Integer offset,
            @Describe("Most lines to return. Default 2000.") Integer limit) {}

    private final PermissionPolicy policy;
    private final AgentState state;
    private final DirectoryContext.Settings settings;
    private final NestedInstructions nested;

    public ReadTool(PermissionPolicy policy, AgentState state) {
        this(policy, state, DirectoryContext.Settings.defaults());
    }

    public ReadTool(PermissionPolicy policy, AgentState state, DirectoryContext.Settings settings) {
        this(policy, state, settings, null);
    }

    /** @param nested delivers the nested AGENTS.md files, or null; the settings then do not list AGENTS.md */
    public ReadTool(PermissionPolicy policy, AgentState state, DirectoryContext.Settings settings,
                    NestedInstructions nested) {
        super("read", "Read a file. The result has numbered lines. Use offset and limit to read a range. "
                + "You must read an existing file before you write or edit it.", Input.class, true);
        this.policy = policy;
        this.state = state;
        this.settings = settings;
        this.nested = nested;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) throws Exception {
        java.nio.file.Path file;
        try {
            file = FileSupport.resolve(input.path(), context, policy, "read");
        } catch (FileSupport.Refused e) {
            return ToolResultData.error(e.getMessage());
        }
        var shown = FileSupport.display(context, file);
        if (Files.isDirectory(file)) return ToolResultData.error(shown + " is a directory. Use the ls tool.");
        if (!Files.isRegularFile(file)) return ToolResultData.error("File does not exist: " + shown);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return ToolResultData.error("Cannot read " + shown + ": " + e.getMessage());
        }
        context.cancel().throwIfCancelled();
        SplitText text;
        try {
            text = SplitText.decode(bytes);
        } catch (TextDiffException e) {
            return ToolResultData.error("Cannot read " + shown + ": " + e.getMessage());
        }
        var updates = new ArrayList<StateUpdate>();
        updates.add(new StateUpdate.FileRecorded(file, FileSupport.stateOf(file, bytes)));

        var out = new StringBuilder();
        if (nested != null) out.append(nested.deliver(context, file));
        var root = context.workingDirectory().toAbsolutePath().normalize();
        var found = DirectoryContext.collect(root, file, state.directories(), settings);
        for (var doc : found.docs()) {
            out.append("[directory context: ").append(FileSupport.display(context, doc.path())).append("]\n")
                    .append(doc.text()).append("\n\n");
        }
        updates.addAll(found.updates());

        var lines = text.lines();
        var offset = input.offset() == null || input.offset() < 1 ? 1 : input.offset();
        if (offset == 1 && !isData(file)) {
            var front = FrontMatter.scan(lines, settings.frontMatter());
            front.ifPresent(block -> out.append("[front matter]\n").append(block).append("\n\n"));
        }
        var limit = input.limit() == null || input.limit() < 1 ? DEFAULT_LIMIT : input.limit();
        var start = Math.min(offset - 1, lines.size());
        var end = (int) Math.min((long) lines.size(), (long) start + limit);
        if (lines.isEmpty()) {
            out.append("[").append(shown).append(" is empty]\n");
        } else if (start >= lines.size()) {
            out.append("[").append(shown).append(" has ").append(lines.size())
                    .append(" lines. The offset is past the end.]\n");
        } else {
            for (var i = start; i < end; i++) {
                var line = lines.get(i);
                if (line.length() > MAX_LINE_CHARS) line = line.substring(0, MAX_LINE_CHARS) + " [line cut]";
                out.append(String.format("%6d\t", i + 1)).append(line).append('\n');
            }
            if (end < lines.size()) {
                out.append("[").append(lines.size() - end).append(" more lines. Use offset ").append(end + 1)
                        .append(" to continue.]\n");
            }
        }
        return ToolResultData.ok(out.toString(), updates);
    }

    private static boolean isData(java.nio.file.Path file) {
        var name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return DATA_EXTENSIONS.stream().anyMatch(name::endsWith);
    }
}
