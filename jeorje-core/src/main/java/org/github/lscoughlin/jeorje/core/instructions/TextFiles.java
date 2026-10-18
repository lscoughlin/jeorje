package org.github.lscoughlin.jeorje.core.instructions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small file helpers for the loaders. */
final class TextFiles {

    private TextFiles() {
    }

    static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Cuts text to at most maxBytes of UTF-8 and appends a note when it cuts. */
    static String cut(String text, int maxBytes, String note) {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        var cut = new String(bytes, 0, Math.max(0, maxBytes), StandardCharsets.UTF_8);
        while (!cut.isEmpty() && cut.charAt(cut.length() - 1) == '\uFFFD') {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "\n" + note;
    }
}
