package org.github.lscoughlin.jeorje.ui.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Tab completion for the input line. It completes slash command names (built-in commands, custom commands, and
 * skills), the fixed arguments of some commands, and file paths for {@code /edit}, {@code /files list}, and
 * {@code /implement}.
 */
public final class InputCompletion {

    /** Most candidates that one completion lists. */
    static final int MAX_CANDIDATES = 200;

    /**
     * The result of one completion.
     *
     * @param line the new text before the cursor
     * @param shown the candidates to show to the user. The list is empty when the completion is unique or empty.
     */
    public record Result(String line, List<String> shown) {
        public Result {
            shown = List.copyOf(shown);
        }
    }

    private record Match(String text, String label, boolean directory) {}

    private final SlashCommands slash;
    private final Path base;

    /**
     * @param slash the slash commands
     * @param base the directory that relative paths start from
     */
    public InputCompletion(SlashCommands slash, Path base) {
        this.slash = slash;
        this.base = base.toAbsolutePath().normalize();
    }

    /** Completes the text before the cursor. A line with no candidates comes back unchanged. */
    public Result complete(String line) {
        if (line == null || !line.startsWith("/") || line.indexOf('\n') >= 0) return new Result(line == null ? "" : line, List.of());
        var matches = matches(line);
        if (matches.isEmpty()) return new Result(line, List.of());
        if (matches.size() == 1) {
            var only = matches.getFirst();
            return new Result(only.directory() ? only.text() : only.text() + " ", List.of());
        }
        var common = matches.stream().map(Match::text).reduce(InputCompletion::commonPrefix).orElse(line);
        var shown = matches.stream().map(Match::label).toList();
        return new Result(common.length() > line.length() ? common : line, shown);
    }

    private List<Match> matches(String line) {
        var space = line.indexOf(' ');
        if (space < 0) {
            return slash.complete(line).stream().map(c -> new Match(c.text(),
                    c.description() == null || c.description().isBlank() ? c.text() : c.text() + "  " + c.description(),
                    false)).toList();
        }
        var name = line.substring(1, space);
        var arg = line.substring(space + 1);
        return switch (name) {
            case "edit" -> paths("/edit ", arg);
            case "files" -> files(arg);
            case "implement" -> arg.indexOf(' ') < 0 ? paths("/implement ", arg) : List.of();
            default -> slash.complete(line).stream().map(c -> new Match(c.text(), c.text(), false)).toList();
        };
    }

    private List<Match> files(String arg) {
        if (arg.startsWith("list ")) return paths("/files list ", arg.substring(5));
        if ("list".startsWith(arg)) return List.of(new Match("/files list", "list", false));
        return List.of();
    }

    private List<Match> paths(String head, String typed) {
        var slashAt = typed.lastIndexOf('/');
        var dirPart = slashAt < 0 ? "" : typed.substring(0, slashAt + 1);
        var prefix = typed.substring(slashAt + 1);
        Path dir;
        try {
            dir = dirPart.isEmpty() ? base : base.resolve(dirPart);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (!Files.isDirectory(dir)) return List.of();
        var showHidden = prefix.startsWith(".");
        var out = new ArrayList<Match>();
        try (Stream<Path> children = Files.list(dir)) {
            children.map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)))
                    .filter(n -> showHidden || !n.startsWith("."))
                    .sorted()
                    .limit(MAX_CANDIDATES)
                    .forEach(n -> {
                        var directory = Files.isDirectory(dir.resolve(n));
                        var shown = n + (directory ? "/" : "");
                        out.add(new Match(head + dirPart + shown, shown, directory));
                    });
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    private static String commonPrefix(String a, String b) {
        var n = Math.min(a.length(), b.length());
        var i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return a.substring(0, i);
    }
}
