package org.github.lscoughlin.jeorje.core.plan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads a plan file. The parser reports the first structure error with its line number. It does not
 * judge the content. {@link PlanValidator} does that.
 */
public final class PlanParser {
    /** The most lines that the front matter can have. */
    public static final int MAX_FRONT_MATTER_LINES = 20;

    private static final Pattern STORY_HEADING = Pattern.compile("^## Story\\s+([^\\s:]+):\\s*(.*\\S)?\\s*$");
    private static final Pattern LABEL = Pattern.compile("^- \\*\\*([^*]+?):\\*\\*\\s*(.*)$");
    private static final Pattern TASK = Pattern.compile("^- \\[(.)\\]\\s+(\\S+)\\s*(.*)$");
    private static final Pattern QUOTED = Pattern.compile("^([\"'])((?:\\\\.|(?!\\1).)*)\\1(?:\\s+#.*)?$");
    private static final Pattern COMMENT =Pattern.compile("\\s+#(\\s.*)?$");

    private PlanParser() {}

    /** Reads and parses a plan file. */
    public static Plan parse(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Parses plan text. */
    public static Plan parse(String text) {
        var source = text.startsWith("﻿") ? text.substring(1) : text;
        var lines = source.split("\n", -1);
        var end = frontMatterEnd(lines);
        var frontLines = new ArrayList<String>();
        var front = new LinkedHashMap<String, String>();
        for (var i = 1; i < end; i++) {
            frontLines.add(lines[i]);
            readFrontLine(lines[i], i + 1, front);
        }
        var i = end + 1;
        var preamble = new ArrayList<String>();
        var fence = false;
        for (; i < lines.length; i++) {
            var line = clean(lines[i]);
            if (toggles(line)) fence = !fence;
            if (!fence && line.startsWith("## Story")) break;
            preamble.add(lines[i]);
        }
        var stories = new ArrayList<Story>();
        var epilogue = new ArrayList<String>();
        var seen = new java.util.HashSet<String>();
        while (i < lines.length) {
            var next = storyEnd(lines, i);
            var story = parseStory(lines, i, next);
            if (!seen.add(story.id())) throw new PlanException(i + 1, "Story id " + story.id() + " is used twice.");
            stories.add(story);
            i = next;
            if (i < lines.length && !clean(lines[i]).startsWith("## Story")) {
                epilogue.addAll(Arrays.asList(lines).subList(i, lines.length));
                break;
            }
        }
        return new Plan(front, frontLines, trimBlank(preamble), stories, trimBlank(epilogue));
    }

    private static int frontMatterEnd(String[] lines) {
        if (lines.length == 0 || !clean(lines[0]).equals("---")) {
            throw new PlanException(1, "A plan starts with a front matter block. The first line must be '---'.");
        }
        for (var i = 1; i < lines.length; i++) {
            if (clean(lines[i]).equals("---")) {
                if (i - 1 > MAX_FRONT_MATTER_LINES) {
                    throw new PlanException(1, "The front matter has " + (i - 1) + " lines. The limit is "
                            + MAX_FRONT_MATTER_LINES + ".");
                }
                return i;
            }
            if (i > MAX_FRONT_MATTER_LINES + 1) {
                throw new PlanException(1, "The front matter is not closed within " + MAX_FRONT_MATTER_LINES + " lines.");
            }
        }
        throw new PlanException(1, "The front matter is not closed. Add a '---' line.");
    }

    private static void readFrontLine(String raw, int number, Map<String, String> front) {
        var line = clean(raw);
        if (line.isBlank() || line.stripLeading().startsWith("#")) return;
        var colon = line.indexOf(':');
        if (colon <= 0 || Character.isWhitespace(line.charAt(0))) {
            throw new PlanException(number, "Front matter line must have the form 'key: value'.");
        }
        var key = line.substring(0, colon).strip();
        var value = line.substring(colon + 1).strip();
        var quoted = QUOTED.matcher(value);
        if (quoted.matches()) {
            // A quoted value keeps any ' #' inside the quotes. Only text after the closing quote can be a comment.
            var inner = quoted.group(2);
            value = quoted.group(1).equals("\"") ? inner.replace("\\\"", "\"").replace("\\\\", "\\") : inner;
        } else {
            value = COMMENT.matcher(value).replaceFirst("").strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
        }
        if (front.putIfAbsent(key, value) != null) throw new PlanException(number, "Front matter key '" + key + "' is used twice.");
    }

    private static int storyEnd(String[] lines, int start) {
        var fence = false;
        for (var i = start + 1; i < lines.length; i++) {
            var line = clean(lines[i]);
            if (toggles(line)) fence = !fence;
            if (!fence && line.startsWith("## ")) {
                return i;
            }
        }
        return lines.length;
    }

    private static Story parseStory(String[] lines, int start, int end) {
        var heading = STORY_HEADING.matcher(clean(lines[start]));
        if (!heading.matches() || heading.group(2) == null) {
            throw new PlanException(start + 1, "A story heading has the form '## Story <id>: <title>'.");
        }
        var id = heading.group(1);
        var title = heading.group(2).strip();

        var labels = new LinkedHashMap<String, String>();
        var i = start + 1;
        String last = null;
        for (; i < end; i++) {
            var line = clean(lines[i]);
            if (line.startsWith("### ")) break;
            if (line.isBlank()) { last = null; continue; }
            var label = LABEL.matcher(line);
            if (label.matches()) {
                var name = label.group(1).strip();
                if (!switch (name) { case "Goal", "Depends on", "Touches", "Model" -> true; default -> false; }) {
                    throw new PlanException(i + 1, "Unknown story label '" + name + "' in story " + id + ".");
                }
                if (labels.putIfAbsent(name, label.group(2).strip()) != null) {
                    throw new PlanException(i + 1, "Label '" + name + "' is used twice in story " + id + ".");
                }
                last = name;
            } else if (last != null && Character.isWhitespace(line.charAt(0))) {
                labels.merge(last, line.strip(), (a, b) -> a + " " + b);
            } else {
                throw new PlanException(i + 1, "Expected a label line '- **Name:** value' or a '### ' heading in story " + id + ".");
            }
        }

        var sections = new LinkedHashMap<String, String>();
        Span handoffSpan = null;
        var tasks = new ArrayList<Task>();
        var fence = false;
        while (i < end) {
            var heading3 = clean(lines[i]);
            var name = heading3.substring(4).strip();
            if (!switch (name) {
                case "Context", "Acceptance criteria", "Verification", "Tasks", "Handoff", "Previous attempt" -> true;
                default -> false;
            }) {
                throw new PlanException(i + 1, "Unknown section '" + name + "' in story " + id + ".");
            }
            if (sections.containsKey(name)) throw new PlanException(i + 1, "Section '" + name + "' is used twice in story " + id + ".");
            var bodyStart = i + 1;
            var j = bodyStart;
            fence = false;
            for (; j < end; j++) {
                var line = clean(lines[j]);
                if (toggles(line)) fence = !fence;
                if (!fence && line.startsWith("### ")) break;
            }
            var body = new ArrayList<String>();
            for (var k = bodyStart; k < j; k++) body.add(lines[k]);
            sections.put(name, trimBlank(body));
            if (name.equals("Tasks")) readTasks(lines, bodyStart, j, id, tasks);
            if (name.equals("Handoff")) handoffSpan = new Span(i, lastNonBlank(lines, i, j) + 1);
            i = j;
        }

        var dependsOn = list(labels.get("Depends on"));
        var touches = list(labels.get("Touches"));
        var model = labels.get("Model");
        return new Story(id, title, labels.getOrDefault("Goal", ""), dependsOn, touches,
                model == null || model.isBlank() ? null : model, sections.getOrDefault("Context", ""),
                sections.getOrDefault("Acceptance criteria", ""), sections.get("Verification"), tasks,
                sections.get("Handoff"), sections.get("Previous attempt"), new Span(start, end), handoffSpan);
    }

    private static void readTasks(String[] lines, int from, int to, String storyId, List<Task> out) {
        Task last = null;
        for (var i = from; i < to; i++) {
            var line = clean(lines[i]);
            if (line.isBlank()) { last = null; continue; }
            var m = TASK.matcher(line);
            if (m.matches()) {
                var state = TaskState.ofMark(m.group(1).charAt(0));
                if (state == null) {
                    throw new PlanException(i + 1, "Unknown checkbox mark '" + m.group(1) + "' in story " + storyId + ". Use [ ], [x], or [!].");
                }
                last = new Task(m.group(2), m.group(3).strip(), state, i);
                out.add(last);
            } else if (last != null && Character.isWhitespace(line.charAt(0))) {
                var joined = (last.text() + " " + line.strip()).strip();
                out.set(out.size() - 1, new Task(last.id(), joined, last.state(), last.line()));
                last = out.get(out.size() - 1);
            } else {
                throw new PlanException(i + 1, "A task line has the form '- [ ] <id> <text>' in story " + storyId + ".");
            }
        }
    }

    private static List<String> list(String value) {
        if (value == null || value.isBlank() || value.strip().equalsIgnoreCase("none")) return List.of();
        var out = new ArrayList<String>();
        for (var part : value.split(",")) {
            var item = part.strip();
            if (item.length() >= 2 && item.startsWith("`") && item.endsWith("`")) item = item.substring(1, item.length() - 1).strip();
            if (!item.isEmpty() && !item.equalsIgnoreCase("none")) out.add(item);
        }
        return out;
    }

    private static int lastNonBlank(String[] lines, int from, int to) {
        var last = from;
        for (var i = from; i < to; i++) if (!clean(lines[i]).isBlank()) last = i;
        return last;
    }

    private static String trimBlank(List<String> lines) {
        var from = 0;
        var to = lines.size();
        while (from < to && clean(lines.get(from)).isBlank()) from++;
        while (to > from && clean(lines.get(to - 1)).isBlank()) to--;
        var out = new ArrayList<String>();
        for (var i = from; i < to; i++) out.add(clean(lines.get(i)));
        return String.join("\n", out);
    }

    private static boolean toggles(String line) {
        var t = line.stripLeading();
        return t.startsWith("```") || t.startsWith("~~~");
    }

    private static String clean(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
