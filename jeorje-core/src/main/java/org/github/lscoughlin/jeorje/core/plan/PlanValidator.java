package org.github.lscoughlin.jeorje.core.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The checks of {@code plan_submit}, and the plan limits. */
public final class PlanValidator {
    /** The most stories in one plan. */
    public static final int MAX_STORIES = 50;
    /** The most tasks in one story. */
    public static final int MAX_TASKS = 20;
    /** The most characters in the text of one story. */
    public static final int MAX_STORY_CHARS = 8_000;

    /** The rule names. */
    public static final String UNIQUE_IDS = "unique-ids";
    public static final String REQUIRED_PARTS = "required-parts";
    public static final String DEPENDS_ON = "depends-on";
    public static final String SELF_CONTAINED = "self-contained";
    public static final String TOUCHES_OVERLAP = "touches-overlap";
    public static final String LIMITS = "limits";

    private static final List<Pattern> CONVERSATION = List.of(
            Pattern.compile("(?i)\\bas (we )?(discussed|mentioned|agreed|said|noted)\\b"),
            Pattern.compile("(?i)\\b(discussed|mentioned|agreed) (above|earlier|before|previously)\\b"),
            Pattern.compile("(?i)\\b(see|read|check) (the )?(above|below|previous|earlier|other) (story|stories|task|tasks|section|message)\\b"),
            Pattern.compile("(?i)\\bsee (story|task)\\s+\\S"),
            Pattern.compile("(?i)\\b(in|from|during) (the|our|this|that) (earlier |previous )?(conversation|chat|discussion)\\b"),
            Pattern.compile("(?i)\\b(the )?(previous|earlier|last|other) (story|stories)\\b"),
            Pattern.compile("(?i)\\bas (described|explained|stated|shown) (above|earlier|before)\\b"));

    private PlanValidator() {}

    /**
     * One failed check.
     *
     * @param storyId the story, or null for a rule about the plan
     * @param rule the rule name
     * @param message what is wrong and how to fix it
     */
    public record Violation(String storyId, String rule, String message) {
        @Override
        public String toString() {
            return (storyId == null ? "Plan" : "Story " + storyId) + " [" + rule + "]: " + message;
        }
    }

    /** Runs all checks. The list is empty if the plan is valid. */
    public static List<Violation> validate(Plan plan) {
        var out = new ArrayList<Violation>();
        if (plan.title().isBlank()) out.add(new Violation(null, REQUIRED_PARTS, "The plan needs a title."));
        if (plan.stories().isEmpty()) out.add(new Violation(null, REQUIRED_PARTS, "The plan needs at least one story."));
        if (plan.stories().size() > MAX_STORIES) {
            out.add(new Violation(null, LIMITS, "The plan has " + plan.stories().size() + " stories. The limit is "
                    + MAX_STORIES + ". Merge stories or split the plan."));
        }
        uniqueIds(plan, out);
        requiredParts(plan, out);
        limits(plan, out);
        dependencies(plan, out);
        selfContained(plan, out);
        touches(plan, out);
        return out;
    }

    /** The violations as one message for the model. */
    public static String format(List<Violation> violations) {
        var sb = new StringBuilder("The plan has ").append(violations.size()).append(violations.size() == 1 ? " error:" : " errors:");
        violations.forEach(v -> sb.append("\n- ").append(v));
        return sb.toString();
    }

    private static void uniqueIds(Plan plan, List<Violation> out) {
        var stories = new HashSet<String>();
        var tasks = new HashSet<String>();
        for (var s : plan.stories()) {
            if (s.id().isBlank()) {
                out.add(new Violation(s.id(), UNIQUE_IDS, "A story has an empty id."));
            } else if (!stories.add(s.id())) {
                out.add(new Violation(s.id(), UNIQUE_IDS, "The story id is used more than once."));
            }
            for (var t : s.tasks()) {
                if (!t.id().startsWith(s.id() + ".")) {
                    out.add(new Violation(s.id(), UNIQUE_IDS, "Task id " + t.id() + " must start with '" + s.id() + ".'."));
                }
                if (!tasks.add(t.id())) out.add(new Violation(s.id(), UNIQUE_IDS, "Task id " + t.id() + " is used more than once."));
            }
        }
    }

    private static void requiredParts(Plan plan, List<Violation> out) {
        for (var s : plan.stories()) {
            if (s.title().isBlank()) out.add(new Violation(s.id(), REQUIRED_PARTS, "The story needs a title."));
            if (s.goal().isBlank()) out.add(new Violation(s.id(), REQUIRED_PARTS, "The story needs a goal."));
            if (s.acceptance().isBlank()) out.add(new Violation(s.id(), REQUIRED_PARTS, "The story needs acceptance criteria."));
            if (s.context().isBlank()) out.add(new Violation(s.id(), REQUIRED_PARTS, "The story needs a context part."));
            if (s.tasks().isEmpty()) out.add(new Violation(s.id(), REQUIRED_PARTS, "The story needs at least one task."));
            for (var t : s.tasks()) {
                if (t.text().isBlank()) out.add(new Violation(s.id(), REQUIRED_PARTS, "Task " + t.id() + " has no text."));
            }
        }
    }

    private static void limits(Plan plan, List<Violation> out) {
        for (var s : plan.stories()) {
            if (s.tasks().size() > MAX_TASKS) {
                out.add(new Violation(s.id(), LIMITS, "The story has " + s.tasks().size() + " tasks. The limit is "
                        + MAX_TASKS + ". Split the story."));
            }
            var size = s.render().length();
            if (size > MAX_STORY_CHARS) {
                out.add(new Violation(s.id(), LIMITS, "The story has " + size + " characters. The limit is "
                        + MAX_STORY_CHARS + ". Shorten the story or split it."));
            }
        }
    }

    private static void dependencies(Plan plan, List<Violation> out) {
        var ids = new HashSet<String>();
        plan.stories().forEach(s -> ids.add(s.id()));
        var graph = new LinkedHashMap<String, List<String>>();
        for (var s : plan.stories()) {
            var valid = new ArrayList<String>();
            for (var d : s.dependsOn()) {
                if (d.equals(s.id())) {
                    out.add(new Violation(s.id(), DEPENDS_ON, "The story depends on itself."));
                } else if (!ids.contains(d)) {
                    out.add(new Violation(s.id(), DEPENDS_ON, "depends_on names " + d + ". The plan has no story with this id."));
                } else {
                    valid.add(d);
                }
            }
            graph.put(s.id(), valid);
        }
        var state = new HashMap<String, Integer>();
        var reported = new HashSet<String>();
        for (var id : graph.keySet()) findCycle(id, graph, state, new ArrayList<>(), reported, out);
    }

    private static void findCycle(String id, Map<String, List<String>> graph, Map<String, Integer> state,
                                  List<String> path, Set<String> reported, List<Violation> out) {
        var seen = state.getOrDefault(id, 0);
        if (seen == 2) return;
        if (seen == 1) {
            var cycle = new ArrayList<>(path.subList(path.indexOf(id), path.size()));
            if (reported.add(String.join(",", new java.util.TreeSet<>(cycle)))) {
                cycle.add(id);
                out.add(new Violation(id, DEPENDS_ON, "The dependencies have a cycle: " + String.join(" -> ", cycle) + "."));
            }
            return;
        }
        state.put(id, 1);
        path.add(id);
        for (var next : graph.getOrDefault(id, List.of())) findCycle(next, graph, state, path, reported, out);
        path.remove(path.size() - 1);
        state.put(id, 2);
    }

    private static void selfContained(Plan plan, List<Violation> out) {
        for (var s : plan.stories()) {
            var others = new ArrayList<String>();
            plan.stories().stream().map(Story::id).filter(id -> !id.equals(s.id()) && !id.isBlank()).forEach(others::add);
            var parts = new LinkedHashMap<String, String>();
            parts.put("goal", s.goal());
            parts.put("context", s.context());
            parts.put("acceptance criteria", s.acceptance());
            if (s.verification() != null) parts.put("verification", s.verification());
            parts.put("tasks", s.tasks().stream().map(Task::text).reduce("", (a, b) -> a + "\n" + b));
            for (var part : parts.entrySet()) {
                var text = part.getValue();
                for (var p : CONVERSATION) {
                    var m = p.matcher(text);
                    if (m.find()) {
                        out.add(new Violation(s.id(), SELF_CONTAINED, "The " + part.getKey() + " says '" + m.group()
                                + "'. A worker sees only its own story. Write the needed facts in the story."));
                        break;
                    }
                }
                for (var other : others) {
                    if (Pattern.compile("(?<![A-Za-z0-9_.-])" + Pattern.quote(other) + "(?![A-Za-z0-9_-])").matcher(text).find()) {
                        out.add(new Violation(s.id(), SELF_CONTAINED, "The " + part.getKey() + " refers to story " + other
                                + ". Name the files and facts in the story. Use depends_on for order."));
                    }
                }
            }
        }
    }

    private static void touches(Plan plan, List<Violation> out) {
        var reach = reachability(plan);
        var stories = plan.stories();
        for (var i = 0; i < stories.size(); i++) {
            for (var j = i + 1; j < stories.size(); j++) {
                var a = stories.get(i);
                var b = stories.get(j);
                if (reach.getOrDefault(a.id(), Set.of()).contains(b.id()) || reach.getOrDefault(b.id(), Set.of()).contains(a.id())) continue;
                var shared = overlap(a.touches(), b.touches());
                if (shared != null) {
                    out.add(new Violation(a.id(), TOUCHES_OVERLAP, "Stories " + a.id() + " and " + b.id()
                            + " can run at the same time and both touch " + shared
                            + ". Add a dependency between them or merge them."));
                }
            }
        }
    }

    /** For each story, the ids of all stories that it depends on, directly or not. */
    private static Map<String, Set<String>> reachability(Plan plan) {
        var direct = new HashMap<String, List<String>>();
        plan.stories().forEach(s -> direct.put(s.id(), s.dependsOn()));
        var out = new HashMap<String, Set<String>>();
        for (var s : plan.stories()) {
            var seen = new LinkedHashSet<String>();
            var stack = new ArrayList<>(s.dependsOn());
            while (!stack.isEmpty()) {
                var next = stack.remove(stack.size() - 1);
                if (seen.add(next)) stack.addAll(direct.getOrDefault(next, List.of()));
            }
            out.put(s.id(), seen);
        }
        return out;
    }

    private static String overlap(List<String> a, List<String> b) {
        for (var x : a) {
            for (var y : b) {
                var nx = normalize(x);
                var ny = normalize(y);
                if (nx.isEmpty() || ny.isEmpty()) continue;
                if (nx.equals(ny) || ny.startsWith(nx + "/")) return "'" + x + "'" + (nx.equals(ny) ? "" : " (contains '" + y + "')");
                if (nx.startsWith(ny + "/")) return "'" + y + "'" + " (contains '" + x + "')";
            }
        }
        return null;
    }

    private static String normalize(String path) {
        var p = path.strip();
        while (p.startsWith("./")) p = p.substring(2);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
