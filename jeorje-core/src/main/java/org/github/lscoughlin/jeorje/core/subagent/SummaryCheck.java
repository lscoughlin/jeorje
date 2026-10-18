package org.github.lscoughlin.jeorje.core.subagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Parses and checks a completion summary. */
final class SummaryCheck {
    static final int MAX_WORDS = 300;
    static final int MIN_KEYWORDS = 3;
    static final int MAX_KEYWORDS = 12;
    static final List<String> LABELS =
        List.of("Outcome", "Done", "Files", "Branch", "Decisions", "Verification", "Open issues", "Keywords");

    private static final Pattern LABEL_LINE = Pattern.compile(
        "^\\s*(?:[-*#>]+\\s*)?\\**\\s*(Outcome|Done|Files|Branch|Decisions|Verification|Open issues|Keywords)\\s*\\**\\s*:\\s*\\**\\s*(.*)$",
        Pattern.CASE_INSENSITIVE);

    private SummaryCheck() {}

    /** Removes a code fence around the answer. */
    static String clean(String text) {
        var t = text == null ? "" : text.strip();
        if (t.startsWith("```")) {
            var firstEnd = t.indexOf('\n');
            if (firstEnd > 0) t = t.substring(firstEnd + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.strip();
        }
        return t;
    }

    /** The parts by label. A part has the text of its line and the lines that follow it. */
    static Map<String, String> parts(String text) {
        var parts = new LinkedHashMap<String, StringBuilder>();
        StringBuilder current = null;
        for (var line : text.split("\\R")) {
            var m = LABEL_LINE.matcher(line);
            if (m.matches()) {
                var label = canonical(m.group(1));
                if (parts.containsKey(label)) {
                    current = null;
                    continue;
                }
                current = new StringBuilder(m.group(2).strip());
                parts.put(label, current);
            } else if (current != null && !line.isBlank()) {
                if (!current.isEmpty()) current.append(' ');
                current.append(line.strip());
            }
        }
        var out = new LinkedHashMap<String, String>();
        parts.forEach((k, v) -> out.put(k, v.toString().strip()));
        return out;
    }

    private static String canonical(String label) {
        for (var l : LABELS) if (l.equalsIgnoreCase(label)) return l;
        return label;
    }

    static int words(String text) {
        var t = text.strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    static List<String> keywords(String keywordsPart) {
        var out = new ArrayList<String>();
        for (var k : keywordsPart.split("[,;]")) {
            var t = k.strip().replaceAll("^[`'\"]+|[`'\"]+$", "");
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * Checks the summary.
     *
     * @param expectedOutcome {@code completed}, {@code failed}, or {@code cancelled}
     * @return the problems. The list is empty when the summary is valid.
     */
    static List<String> validate(String text, String expectedOutcome, HistoryText history) {
        var problems = new ArrayList<String>();
        var parts = parts(text);
        for (var label : LABELS) {
            if (!parts.containsKey(label) || parts.get(label).isEmpty()) problems.add("The part '" + label + "' is missing.");
        }
        if (words(text) > MAX_WORDS) problems.add("The summary has " + words(text) + " words. The limit is " + MAX_WORDS + ".");
        var outcome = parts.get("Outcome");
        if (outcome != null && !outcome.isEmpty()
            && !outcome.toLowerCase(Locale.ROOT).startsWith(expectedOutcome)) {
            problems.add("The part 'Outcome' must start with '" + expectedOutcome + "'.");
        }
        var keywordPart = parts.get("Keywords");
        if (keywordPart != null && !keywordPart.isEmpty()) {
            var keywords = keywords(keywordPart);
            if (keywords.size() < MIN_KEYWORDS || keywords.size() > MAX_KEYWORDS) {
                problems.add("Keywords must have " + MIN_KEYWORDS + " to " + MAX_KEYWORDS + " terms. Found "
                    + keywords.size() + ".");
            }
            for (var k : keywords) {
                if (!history.contains(k)) problems.add("The keyword '" + k + "' is not in the history of the subagent.");
            }
        }
        return problems;
    }
}
