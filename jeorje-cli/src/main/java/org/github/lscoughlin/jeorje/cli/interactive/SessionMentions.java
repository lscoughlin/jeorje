package org.github.lscoughlin.jeorje.cli.interactive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Finds the {@code @path} words of a message and adds the files that they name to the next message. A word ends at a
 * space, a tab, a newline, or a comma. A word that does not name a path in the working directory is ignored.
 */
public final class SessionMentions {
    private SessionMentions() { }

    /**
     * Adds the paths named by the {@code @path} words of {@code line} to {@code into}, relative to {@code cwd}, without
     * duplicates and in line order.
     *
     * @param line the text of the message
     * @param cwd the working directory
     * @param into the list of attachments to add to
     */
    public static void addPaths(String line, Path cwd, List<String> into) {
        for (var raw : line.split("[ \t\n,]+")) {
            if (raw.length() < 2 || raw.charAt(0) != '@') continue;
            var word = raw.substring(1);
            if (word.startsWith("@")) continue;
            var path = cwd.resolve(word).normalize();
            if (!path.startsWith(cwd.normalize()) || !Files.exists(path)) continue;
            var relative = cwd.relativize(path).toString();
            if (!relative.isEmpty() && !into.contains(relative)) into.add(relative);
        }
    }
}
