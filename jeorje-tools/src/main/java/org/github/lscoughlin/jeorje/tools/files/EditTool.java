package org.github.lscoughlin.jeorje.tools.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;

/** The {@code edit} tool. It replaces an exact string in a file. */
public final class EditTool extends RecordTool<EditTool.Input> {

    /** The input of {@code edit}. */
    public record Input(
            @Describe("Path of the file. A relative path is relative to the working directory.") String path,
            @Describe("The exact text to replace.") String oldString,
            @Describe("The new text.") String newString,
            @Describe("Replace every match. Default false: the match must be unique.") Boolean replaceAll) {}

    private final PermissionPolicy policy;
    private final AgentState state;
    private final NestedInstructions nested;

    public EditTool(PermissionPolicy policy, AgentState state) {
        this(policy, state, null);
    }

    /** @param nested delivers the nested AGENTS.md files with the result, or null */
    public EditTool(PermissionPolicy policy, AgentState state, NestedInstructions nested) {
        super("edit", "Replace an exact string in a file. The file must be read first in this session. It fails "
                + "when the string is missing, or not unique unless replaceAll is true. The result is a unified diff.",
                Input.class, false);
        this.policy = policy;
        this.state = state;
        this.nested = nested;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) throws Exception {
        if (input.path() == null || input.oldString() == null || input.newString() == null) {
            return ToolResultData.error("path, oldString, and newString are required.");
        }
        java.nio.file.Path file;
        try {
            file = FileSupport.resolve(input.path(), context, policy, "edit");
        } catch (FileSupport.Refused e) {
            return ToolResultData.error(e.getMessage());
        }
        var shown = FileSupport.display(context, file);
        if (!Files.isRegularFile(file)) return ToolResultData.error("File does not exist: " + shown);
        if (input.oldString().isEmpty()) return ToolResultData.error("oldString is empty. Use the write tool to create a file.");
        if (input.oldString().equals(input.newString())) return ToolResultData.error("oldString and newString are the same.");
        byte[] current;
        SplitText before;
        try {
            current = Files.readAllBytes(file);
            var refusal = FileSupport.staleCheck(file, current, state.files(), shown);
            if (refusal != null) return ToolResultData.error(refusal);
            before = SplitText.decode(current);
        } catch (TextDiffException e) {
            return ToolResultData.error("Cannot change " + shown + ": " + e.getMessage());
        }
        var normal = String.join("\n", before.lines()) + (before.finalNewline() && !before.lines().isEmpty() ? "\n" : "");
        var oldText = input.oldString().replace("\r\n", "\n");
        var newText = input.newString().replace("\r\n", "\n");
        var count = 0;
        for (var at = normal.indexOf(oldText); at >= 0; at = normal.indexOf(oldText, at + oldText.length())) count++;
        if (count == 0) {
            return ToolResultData.error("oldString was not found in " + shown + ". Read the file again and copy the text exactly.");
        }
        var all = Boolean.TRUE.equals(input.replaceAll());
        if (count > 1 && !all) {
            return ToolResultData.error("oldString matches " + count + " places in " + shown
                    + ". Add more context to make it unique, or set replaceAll.");
        }
        var changed = all ? normal.replace(oldText, newText)
                : normal.substring(0, normal.indexOf(oldText)) + newText + normal.substring(normal.indexOf(oldText) + oldText.length());
        var after = rebuild(before, SplitText.split(changed));
        var content = after.render();
        try {
            FileSupport.atomicWrite(file, content);
        } catch (IOException e) {
            return ToolResultData.error("Cannot write " + shown + ": " + e.getMessage());
        }
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        List<StateUpdate> updates = List.of(new StateUpdate.FileRecorded(file, FileSupport.stateOf(file, bytes)));
        var diff = TextDiff.unified("a/" + shown, "b/" + shown, before, after, TextDiff.Options.defaults());
        var delivered = nested == null ? "" : nested.deliver(context, file);
        return ToolResultData.ok(delivered + "Edited " + shown + " (" + (all ? count : 1) + (count == 1 || !all ? " replacement" : " replacements")
                + ", +" + diff.added() + " -" + diff.removed() + ").\n" + diff.unified(), updates);
    }

    /**
     * Puts the line ends of the original back. Lines at the start and at the end that did not
     * change keep their own terminator. Other lines get the dominant terminator.
     */
    static SplitText rebuild(SplitText before, SplitText changed) {
        var lines = changed.lines();
        var oldLines = before.lines();
        var ends = new ArrayList<String>();
        var prefix = 0;
        while (prefix < lines.size() && prefix < oldLines.size() && lines.get(prefix).equals(oldLines.get(prefix))) prefix++;
        var suffix = 0;
        while (suffix < lines.size() - prefix && suffix < oldLines.size() - prefix
                && lines.get(lines.size() - 1 - suffix).equals(oldLines.get(oldLines.size() - 1 - suffix))) suffix++;
        for (var i = 0; i < lines.size(); i++) {
            if (i < prefix) ends.add(before.ends().get(i));
            else if (i >= lines.size() - suffix) ends.add(before.ends().get(oldLines.size() - (lines.size() - i)));
            else if (oldLines.size() == lines.size()) ends.add(before.ends().get(i));
            else ends.add(before.eol());
        }
        return new SplitText(lines, ends, before.eol(), changed.finalNewline());
    }
}
