package org.github.lscoughlin.jeorje.tools.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.github.lscoughlin.jeorje.core.diff.SplitText;
import org.github.lscoughlin.jeorje.core.diff.TextDiff;
import org.github.lscoughlin.jeorje.core.diff.TextDiffException;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.state.AgentState;
import org.github.lscoughlin.jeorje.core.state.StateUpdate;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.Required;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;

/** The {@code write} tool. It creates a file or replaces its content. */
public final class WriteTool extends RecordTool<WriteTool.Input> {

    /** The input of {@code write}. */
    public record Input(
            @Required @Describe("Path of the file. A relative path is relative to the working directory.") String path,
            @Required @Describe("The full new content of the file.") String content) {}

    private final PermissionPolicy policy;
    private final AgentState state;
    private final NestedInstructions nested;

    public WriteTool(PermissionPolicy policy, AgentState state) {
        this(policy, state, null);
    }

    /** @param nested delivers the nested AGENTS.md files with the result, or null */
    public WriteTool(PermissionPolicy policy, AgentState state, NestedInstructions nested) {
        super("write", "Create a file or replace its content. An existing file must be read first in this "
                + "session, and it must not have changed since. The result is a unified diff.", Input.class, false);
        this.policy = policy;
        this.state = state;
        this.nested = nested;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) throws Exception {
        if (input.path() == null || input.content() == null) return ToolResultData.error("path and content are required.");
        java.nio.file.Path file;
        try {
            file = FileSupport.resolve(input.path(), context, policy, "write");
        } catch (FileSupport.Refused e) {
            return ToolResultData.error(e.getMessage());
        }
        var shown = FileSupport.display(context, file);
        if (Files.isDirectory(file)) return ToolResultData.error(shown + " is a directory.");
        var exists = Files.exists(file);
        var before = SplitText.empty();
        var content = input.content();
        if (exists) {
            byte[] current;
            try {
                current = Files.readAllBytes(file);
                var refusal = FileSupport.staleCheck(file, current, state.files(), shown);
                if (refusal != null) return ToolResultData.error(refusal);
                before = SplitText.decode(current);
            } catch (TextDiffException e) {
                return ToolResultData.error("Cannot change " + shown + ": " + e.getMessage());
            }
            var fresh = SplitText.split(content);
            if (before.eol().equals("\r\n") && !content.contains("\r\n")) {
                content = SplitText.ofLines(fresh.lines(), "\r\n", fresh.finalNewline()).render();
            }
        }
        var after = SplitText.split(content);
        try {
            FileSupport.atomicWrite(file, content);
        } catch (IOException e) {
            return ToolResultData.error("Cannot write " + shown + ": " + e.getMessage());
        }
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        List<StateUpdate> updates = List.of(new StateUpdate.FileRecorded(file, FileSupport.stateOf(file, bytes)));
        var diff = TextDiff.unified(exists ? "a/" + shown : "/dev/null", "b/" + shown, before, after,
                TextDiff.Options.defaults());
        var head = exists ? "Wrote " + shown : "Created " + shown;
        var body = diff.identical() ? head + ". The content did not change.\n"
                : head + " (+" + diff.added() + " -" + diff.removed() + ").\n" + diff.unified();
        return ToolResultData.ok(prefix(context, file) + body, updates);
    }

    private String prefix(ToolContext context, java.nio.file.Path file) {
        return nested == null ? "" : nested.deliver(context, file);
    }
}
