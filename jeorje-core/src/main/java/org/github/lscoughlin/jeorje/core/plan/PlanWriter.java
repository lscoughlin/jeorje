package org.github.lscoughlin.jeorje.core.plan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Writes plans. {@link #render} makes the full text of a plan. {@link #update} changes an existing
 * text and touches only the task checkboxes and the handoff sections. All other text stays as it is.
 */
public final class PlanWriter {
    private PlanWriter() {}

    /** The full text of a plan. Parsing this text gives an equal plan. */
    public static String render(Plan plan) {
        var out = new StringBuilder("---\n");
        if (plan.frontLines().isEmpty()) {
            plan.front().forEach((k, v) -> out.append(k).append(": ").append(v).append('\n'));
        } else {
            plan.frontLines().forEach(l -> out.append(l).append('\n'));
        }
        out.append("---\n");
        if (!plan.preamble().isBlank()) out.append('\n').append(plan.preamble()).append('\n');
        for (var story : plan.stories()) out.append('\n').append(renderStory(story)).append('\n');
        if (!plan.epilogue().isBlank()) out.append('\n').append(plan.epilogue()).append('\n');
        return out.toString();
    }

    /** The text of one story, with no trailing blank line. */
    static String renderStory(Story s) {
        var out = new StringBuilder();
        out.append("## Story ").append(s.id()).append(": ").append(s.title()).append('\n');
        out.append("- **Goal:** ").append(s.goal()).append('\n');
        out.append("- **Depends on:** ").append(s.dependsOn().isEmpty() ? "none" : String.join(", ", s.dependsOn())).append('\n');
        out.append("- **Touches:** ").append(s.touches().isEmpty() ? "none"
                : s.touches().stream().map(t -> "`" + t + "`").collect(Collectors.joining(", "))).append('\n');
        if (s.model() != null && !s.model().isBlank()) out.append("- **Model:** ").append(s.model()).append('\n');
        section(out, "Context", s.context());
        section(out, "Acceptance criteria", s.acceptance());
        if (s.verification() != null) section(out, "Verification", s.verification());
        out.append("\n### Tasks\n");
        s.tasks().forEach(t -> out.append(t.render()).append('\n'));
        if (s.handoff() != null) section(out, "Handoff", s.handoff());
        if (s.previousAttempt() != null) section(out, "Previous attempt", s.previousAttempt());
        return out.toString().stripTrailing();
    }

    private static void section(StringBuilder out, String name, String body) {
        out.append("\n### ").append(name).append('\n');
        if (!body.isBlank()) out.append(body.strip()).append('\n');
    }

    /** Writes the full text of a plan to a file. A temporary file and a move make the change atomic. */
    public static void write(Plan plan, Path file) throws IOException {
        writeAtomic(file, render(plan));
    }

    /**
     * Changes the checkboxes and the handoff sections of {@code original} to the values in {@code plan}.
     * A story or task that {@code original} does not have is ignored.
     */
    public static String update(String original, Plan plan) {
        var current = PlanParser.parse(original);
        var lines = new ArrayList<>(Arrays.asList(original.split("\n", -1)));
        var edits = new ArrayList<Edit>();
        for (var old : current.stories()) {
            var wanted = plan.story(old.id()).orElse(null);
            if (wanted == null) continue;
            for (var task : old.tasks()) {
                var next = wanted.tasks().stream().filter(t -> t.id().equals(task.id())).findFirst().orElse(null);
                if (next != null && next.state() != task.state()) {
                    var line = lines.get(task.line());
                    var open = line.indexOf('[');
                    edits.add(new Edit(task.line(), task.line() + 1,
                            List.of(line.substring(0, open + 1) + next.state().mark() + line.substring(open + 2))));
                }
            }
            if (!Objects.equals(blankToNull(old.handoff()), blankToNull(wanted.handoff()))) {
                edits.add(handoffEdit(lines, old, blankToNull(wanted.handoff())));
            }
        }
        edits.sort(Comparator.comparingInt(Edit::from).reversed());
        for (var e : edits) {
            for (var i = e.to() - 1; i >= e.from(); i--) lines.remove(i);
            lines.addAll(e.from(), e.replacement());
        }
        return String.join("\n", lines);
    }

    /** Reads a plan file, applies {@link #update}, and writes the file again if the text changed. */
    public static boolean updateFile(Path file, Plan plan) throws IOException {
        var original = Files.readString(file, StandardCharsets.UTF_8);
        var changed = update(original, plan);
        if (changed.equals(original)) return false;
        writeAtomic(file, changed);
        return true;
    }

    private static Edit handoffEdit(List<String> lines, Story old, String text) {
        var span = old.handoffSpan();
        if (span != null) {
            if (text == null) {
                var from = span.start();
                if (from > 0 && lines.get(from - 1).isBlank()) from--;
                return new Edit(from, span.end(), List.of());
            }
            return new Edit(span.start(), span.end(), sectionLines(text));
        }
        var at = old.span().start();
        for (var i = old.span().start(); i < old.span().end() && i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) at = i + 1;
        }
        var add = new ArrayList<String>();
        add.add("");
        add.addAll(sectionLines(text));
        return new Edit(at, at, add);
    }

    private static List<String> sectionLines(String text) {
        var out = new ArrayList<String>();
        out.add("### Handoff");
        out.addAll(Arrays.asList(text.strip().split("\n", -1)));
        return out;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }

    private static void writeAtomic(Path file, String text) throws IOException {
        var parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        var tmp = Files.createTempFile(parent, ".plan-", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private record Edit(int from, int to, List<String> replacement) {}
}
