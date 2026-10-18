package org.github.lscoughlin.jeorje.tools.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.diff.SplitText;
import org.github.lscoughlin.jeorje.core.state.DirectoryContextSet;
import org.github.lscoughlin.jeorje.core.state.StateUpdate;

/** Finds the directory documents on the path from the workspace root to a file. */
public final class DirectoryContext {

    /** The settings, from the {@code tools.read} config keys. */
    public record Settings(List<String> docs, int docChars, int fallbackLines, FrontMatter.Limits frontMatter) {
        public static Settings defaults() {
            return new Settings(List.of("AGENTS.md", "README.md", "llms.txt", "llm.txt"), 2000, 20,
                    FrontMatter.Limits.defaults());
        }
    }

    /** One document: its path and the text to show. */
    public record Doc(Path path, String text) {}

    /** The documents found and the updates to the set of delivered directories. */
    public record Found(List<Doc> docs, List<StateUpdate> updates) {}

    private static final long MAX_DOC_BYTES = 256 * 1024;

    private DirectoryContext() {}

    /**
     * Collects the documents for a file. The order is from the root to the directory of the file.
     * A directory that is in {@code delivered} is skipped. The {@code AGENTS.md} of the root is
     * skipped, because the system prompt holds it.
     */
    public static Found collect(Path root, Path file, DirectoryContextSet delivered, Settings settings) {
        var docs = new ArrayList<Doc>();
        var updates = new ArrayList<StateUpdate>();
        var fileDir = file.getParent();
        if (fileDir == null || !fileDir.startsWith(root)) return new Found(docs, updates);
        var chain = new ArrayList<Path>();
        for (var dir = fileDir; dir != null && dir.startsWith(root); dir = dir.getParent()) chain.addFirst(dir);
        for (var dir : chain) {
            if (delivered.contains(dir)) continue;
            var before = docs.size();
            for (var name : settings.docs()) {
                if (name.equals("AGENTS.md") && dir.equals(root)) continue;
                var doc = dir.resolve(name);
                if (doc.equals(file) || !Files.isRegularFile(doc)) continue;
                var text = render(doc, settings);
                if (text != null) docs.add(new Doc(doc, text));
            }
            if (docs.size() > before) updates.add(new StateUpdate.DirectoryDelivered(dir));
        }
        return new Found(docs, updates);
    }

    private static String render(Path doc, Settings settings) {
        try {
            var size = Files.size(doc);
            var bytes = Files.readAllBytes(doc);
            if (size > MAX_DOC_BYTES) bytes = java.util.Arrays.copyOf(bytes, (int) MAX_DOC_BYTES);
            var lines = SplitText.decode(bytes).lines();
            var text = FrontMatter.scan(lines, settings.frontMatter())
                    .orElseGet(() -> String.join("\n", lines.subList(0, Math.min(lines.size(), settings.fallbackLines()))));
            return text.length() > settings.docChars() ? text.substring(0, settings.docChars()) + "\n[cut]" : text;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
