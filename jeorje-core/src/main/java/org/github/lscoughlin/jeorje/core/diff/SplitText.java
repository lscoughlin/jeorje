package org.github.lscoughlin.jeorje.core.diff;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Text split into lines with no terminators.
 *
 * <p>The record remembers the terminator of each line, the dominant terminator, and whether the
 * text ends with a newline. {@link #render()} puts the text back together.
 *
 * @param lines the lines, with no terminators
 * @param ends the terminator of each line (same size as lines); never empty strings
 * @param eol the dominant terminator, {@code "\n"} or {@code "\r\n"}
 * @param finalNewline true if the text ends with a terminator (always true for empty text)
 */
public record SplitText(List<String> lines, List<String> ends, String eol, boolean finalNewline) {

    private static final int BINARY_SCAN_BYTES = 8000;

    public SplitText {
        lines = List.copyOf(lines);
        ends = List.copyOf(ends);
        if (lines.size() != ends.size()) {
            throw new IllegalArgumentException("lines and ends differ in size");
        }
    }

    /** The empty text. */
    public static SplitText empty() {
        return new SplitText(List.of(), List.of(), "\n", true);
    }

    /** Builds text from lines. The terminator is {@code eol} for every line. */
    public static SplitText ofLines(List<String> lines, String eol, boolean finalNewline) {
        var ends = new ArrayList<String>(lines.size());
        for (var ignored : lines) {
            ends.add(eol);
        }
        return new SplitText(lines, ends, eol, finalNewline || lines.isEmpty());
    }

    /** Splits a string at {@code \n} and {@code \r\n}. A lone {@code \r} stays in the line. */
    public static SplitText split(String text) {
        var lines = new ArrayList<String>();
        var ends = new ArrayList<String>();
        var crlf = 0;
        var lf = 0;
        var start = 0;
        var n = text.length();
        while (start < n) {
            var nl = text.indexOf('\n', start);
            if (nl < 0) {
                lines.add(text.substring(start));
                ends.add(null);
                break;
            }
            if (nl > start && text.charAt(nl - 1) == '\r') {
                lines.add(text.substring(start, nl - 1));
                ends.add("\r\n");
                crlf++;
            } else {
                lines.add(text.substring(start, nl));
                ends.add("\n");
                lf++;
            }
            start = nl + 1;
        }
        var eol = crlf > lf ? "\r\n" : "\n";
        var finalNewline = lines.isEmpty() || ends.getLast() != null;
        for (var i = 0; i < ends.size(); i++) {
            if (ends.get(i) == null) {
                ends.set(i, eol);
            }
        }
        return new SplitText(lines, ends, eol, finalNewline);
    }

    /** Decodes bytes as strict UTF-8 and splits them. Refuses binary content. */
    public static SplitText decode(byte[] bytes) {
        var scan = Math.min(bytes.length, BINARY_SCAN_BYTES);
        for (var i = 0; i < scan; i++) {
            if (bytes[i] == 0) {
                throw new TextDiffException(TextDiffException.Kind.BINARY,
                        "The content is binary (a zero byte in the first 8,000 bytes).");
            }
        }
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return split(decoder.decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException e) {
            throw new TextDiffException(TextDiffException.Kind.BINARY,
                    "The content is binary (it is not valid UTF-8).");
        }
    }

    /** Joins the lines with their terminators. */
    public String render() {
        var sb = new StringBuilder();
        for (var i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i < lines.size() - 1 || finalNewline) {
                sb.append(ends.get(i));
            }
        }
        return sb.toString();
    }
}
