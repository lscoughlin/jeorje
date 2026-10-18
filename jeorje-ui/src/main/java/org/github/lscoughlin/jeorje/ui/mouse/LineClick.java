package org.github.lscoughlin.jeorje.ui.mouse;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

/** A click handler for a chat line. A handler returns true when it used the click. */
@FunctionalInterface
public interface LineClick {
    boolean onClick(int line, int col, String text);

    /** A click on a tool result header expands or collapses the result. */
    static LineClick toolResult(IntFunction<Optional<String>> toolUseIdAt, Consumer<String> toggle) {
        return (line, col, text) -> {
            var id = toolUseIdAt.apply(line);
            id.ifPresent(toggle);
            return id.isPresent();
        };
    }

    /** A click on a file path opens the file in the editor. */
    static LineClick filePath(Consumer<String> open) {
        return (line, col, text) -> {
            var path = FilePaths.pathAt(text, col);
            path.ifPresent(open);
            return path.isPresent();
        };
    }

    /** Finds file paths in a line of text. */
    final class FilePaths {
        private static final Pattern PATH = Pattern.compile("(?<![\\w./~-])(?:~|\\.{1,2})?/?[\\w.-]+(?:/[\\w.-]+)+(?::\\d+)?|(?<![\\w./-])[\\w-]+\\.[A-Za-z]{1,6}(?::\\d+)?");

        private FilePaths() {}

        public static Optional<String> pathAt(String text, int col) {
            var m = PATH.matcher(text);
            while (m.find()) {
                if (col >= m.start() && col < m.end()) return Optional.of(m.group().replaceFirst(":\\d+$", ""));
            }
            return Optional.empty();
        }

        public static List<String> all(String text) {
            var out = new java.util.ArrayList<String>();
            var m = PATH.matcher(text);
            while (m.find()) out.add(m.group());
            return out;
        }
    }
}
