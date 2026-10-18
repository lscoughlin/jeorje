package org.github.lscoughlin.jeorje.core.implement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.github.lscoughlin.jeorje.core.plan.Plan;
import org.github.lscoughlin.jeorje.core.plan.PlanException;
import org.github.lscoughlin.jeorje.core.plan.PlanParser;

/** Helpers for the plan files that {@code /implement} reads and changes. */
public final class PlanFiles {
    private static final Pattern STATUS = Pattern.compile("^(status:\\s*)([^\\s#]*)(.*)$");

    private PlanFiles() {}

    /** The same plan with another status in the front matter. */
    public static Plan withStatus(Plan plan, String status) {
        var front = new LinkedHashMap<>(plan.front());
        front.put("status", status);
        var lines = new ArrayList<String>();
        var replaced = false;
        for (var line : plan.frontLines()) {
            var m = STATUS.matcher(line);
            if (m.matches()) {
                lines.add(m.group(1) + status + m.group(3));
                replaced = true;
            } else {
                lines.add(line);
            }
        }
        if (!replaced && !lines.isEmpty()) lines.add("status: " + status);
        return new Plan(front, lines, plan.preamble(), plan.stories(), plan.epilogue());
    }

    /** Changes the {@code status} line of the front matter in the file. Other text stays as it is. */
    public static void writeStatus(Path file, String status) throws IOException {
        var original = Files.readString(file, StandardCharsets.UTF_8);
        var lines = new ArrayList<>(List.of(original.split("\n", -1)));
        if (lines.isEmpty() || !lines.get(0).strip().equals("---")) return;
        var end = -1;
        for (var i = 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("---")) {
                end = i;
                break;
            }
            var m = STATUS.matcher(lines.get(i));
            if (m.matches()) {
                lines.set(i, m.group(1) + status + m.group(3));
                end = -2;
                break;
            }
        }
        if (end >= 0) lines.add(end, "status: " + status);
        var text = String.join("\n", lines);
        if (text.equals(original)) return;
        var tmp = Files.createTempFile(file.toAbsolutePath().getParent(), ".plan-", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Makes a shell command from the verification part of a story. The part is usually a command in backticks.
     * Backticks are command substitution in a shell, so they are removed. Code fences are removed too. Several
     * lines join with {@code &&}.
     *
     * @return the command, or an empty string
     */
    public static String command(String verification) {
        if (verification == null) return "";
        var parts = new ArrayList<String>();
        for (var raw : verification.split("\n")) {
            var line = raw.strip();
            if (line.isEmpty() || line.startsWith("```") || line.startsWith("~~~")) continue;
            if (line.startsWith("- ") || line.startsWith("* ")) line = line.substring(2).strip();
            if (line.length() >= 2 && line.startsWith("`") && line.endsWith("`")) {
                line = line.substring(1, line.length() - 1).strip();
            }
            if (!line.isEmpty()) parts.add(line);
        }
        return String.join(" && ", parts);
    }

    /** The slug of a plan file: the file name without {@code .md}. */
    public static String slug(Path file) {
        var name = file.getFileName().toString();
        var slug = (name.endsWith(".md") ? name.substring(0, name.length() - 3) : name)
            .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        return slug.isEmpty() ? "plan" : slug;
    }

    /** The plan files of a directory that have open stories, newest first. A file with an error is skipped. */
    public static List<Path> openPlans(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".md"))
                .filter(p -> {
                    try {
                        return !PlanParser.parse(p).openStories().isEmpty();
                    } catch (IOException | PlanException e) {
                        return false;
                    }
                })
                .sorted(Comparator.comparingLong(PlanFiles::modified).reversed())
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Finds the plan file for a reference: a path, or a slug in the plan directory. With no reference it gives the
     * newest plan that has open stories.
     */
    public static Optional<Path> find(Path dir, String reference) {
        if (reference == null || reference.isBlank()) return openPlans(dir).stream().findFirst();
        var direct = Path.of(reference);
        if (Files.isRegularFile(direct)) return Optional.of(direct);
        for (var candidate : List.of(dir.resolve(reference), dir.resolve(reference + ".md"))) {
            if (Files.isRegularFile(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
