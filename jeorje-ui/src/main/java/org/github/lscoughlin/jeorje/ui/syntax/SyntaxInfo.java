package org.github.lscoughlin.jeorje.ui.syntax;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One syntax found in a nanorc file.
 *
 * @param name     the syntax name from the {@code syntax} line
 * @param file     the nanorc file that defines the syntax
 * @param patterns the file name patterns from the {@code syntax} line
 */
public record SyntaxInfo(String name, Path file, List<Pattern> patterns) {

    public SyntaxInfo {
        patterns = List.copyOf(patterns);
    }

    /** Returns the end of the first match in the text, or -1 when no pattern matches. */
    int matchEnd(String text) {
        for (var pattern : patterns) {
            var matcher = pattern.matcher(text);
            if (matcher.find()) {
                return matcher.end();
            }
        }
        return -1;
    }
}
