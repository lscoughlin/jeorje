package org.github.lscoughlin.jeorje.core.implement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.plan.Plan;
import org.github.lscoughlin.jeorje.core.plan.Story;

/**
 * The scheduler of {@code /implement}. It is a pure function of the plan and the story states. It starts a story
 * when all of its dependencies are done, when a worker slot is free, and when no running story shares a path with it.
 */
public final class Scheduler {
    private Scheduler() {}

    /**
     * The stories to start now, in plan order.
     *
     * @param states the state of each story id
     * @param selected the ids that the run covers
     * @param parallel the largest number of workers at the same time
     */
    public static List<Story> next(Plan plan, Map<String, StoryState> states, Set<String> selected, int parallel) {
        var running = new ArrayList<Story>();
        for (var s : plan.stories()) if (states.get(s.id()) == StoryState.RUNNING) running.add(s);
        var slots = parallel - running.size();
        var out = new ArrayList<Story>();
        for (var s : plan.stories()) {
            if (slots <= 0) break;
            if (!selected.contains(s.id()) || states.get(s.id()) != StoryState.PENDING) continue;
            if (!ready(s, states)) continue;
            if (collides(s, running) || collides(s, out)) continue;
            out.add(s);
            slots--;
        }
        return out;
    }

    /** True if every dependency of the story is done. */
    public static boolean ready(Story story, Map<String, StoryState> states) {
        for (var dep : story.dependsOn()) if (states.get(dep) != StoryState.DONE) return false;
        return true;
    }

    /** The pending stories that depend, directly or not, on a blocked story. */
    public static List<String> blockedDependents(Plan plan, Map<String, StoryState> states) {
        var blocked = new LinkedHashSet<String>();
        for (var e : states.entrySet()) if (e.getValue() == StoryState.BLOCKED) blocked.add(e.getKey());
        var out = new ArrayList<String>();
        var changed = true;
        while (changed) {
            changed = false;
            for (var s : plan.stories()) {
                if (states.get(s.id()) != StoryState.PENDING || blocked.contains(s.id())) continue;
                if (s.dependsOn().stream().anyMatch(blocked::contains)) {
                    blocked.add(s.id());
                    out.add(s.id());
                    changed = true;
                }
            }
        }
        return out;
    }

    /** The ids of the stories that the run covers: the named stories and their open dependencies. */
    public static Set<String> closure(Plan plan, Set<String> only) {
        var all = new LinkedHashSet<String>();
        if (only == null || only.isEmpty()) {
            plan.stories().forEach(s -> all.add(s.id()));
            return all;
        }
        var stack = new ArrayList<>(only);
        while (!stack.isEmpty()) {
            var id = stack.removeLast();
            var story = plan.story(id).orElse(null);
            if (story == null || !all.add(id)) continue;
            for (var dep : story.dependsOn()) {
                var d = plan.story(dep).orElse(null);
                if (d != null && !d.done()) stack.add(dep);
            }
        }
        return all;
    }

    private static boolean collides(Story story, List<Story> others) {
        for (var o : others) if (touchesOverlap(story.touches(), o.touches())) return true;
        return false;
    }

    /** True if one path list shares a path with the other: the same path, or one path inside the other. */
    public static boolean touchesOverlap(List<String> a, List<String> b) {
        for (var x : a) {
            for (var y : b) {
                var nx = normalize(x);
                var ny = normalize(y);
                if (nx.isEmpty() || ny.isEmpty()) continue;
                if (nx.equals(ny) || nx.startsWith(ny + "/") || ny.startsWith(nx + "/")) return true;
            }
        }
        return false;
    }

    private static String normalize(String path) {
        var p = path.strip();
        while (p.startsWith("./")) p = p.substring(2);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
