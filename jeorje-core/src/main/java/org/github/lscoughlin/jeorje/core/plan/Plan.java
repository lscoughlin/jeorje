package org.github.lscoughlin.jeorje.core.plan;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A plan: front matter, an introduction, and stories.
 *
 * @param front the front matter keys and values, in file order
 * @param frontLines the raw front matter lines between the {@code ---} lines, or empty to make them from {@code front}
 * @param preamble the text between the front matter and the first story
 * @param stories the stories in file order
 * @param epilogue the text after the last story that starts with a heading that is not a story, or an empty string
 */
public record Plan(Map<String, String> front, List<String> frontLines, String preamble, List<Story> stories,
                   String epilogue) {
    public Plan {
        front = front == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(front));
        frontLines = frontLines == null ? List.of() : List.copyOf(frontLines);
        preamble = preamble == null ? "" : preamble;
        stories = stories == null ? List.of() : List.copyOf(stories);
        epilogue = epilogue == null ? "" : epilogue;
    }

    public String title() { return front.getOrDefault("title", ""); }

    public String status() { return front.getOrDefault("status", "draft"); }

    public String goal() { return front.getOrDefault("goal", ""); }

    /** The plan-level verification command, or an empty string. */
    public String verification() { return front.getOrDefault("verification", ""); }

    public Optional<Story> story(String id) { return stories.stream().filter(s -> s.id().equals(id)).findFirst(); }

    /** The same plan with one story replaced. An unknown id changes nothing. */
    public Plan withStory(Story next) {
        return new Plan(front, frontLines, preamble,
                stories.stream().map(s -> s.id().equals(next.id()) ? next : s).toList(), epilogue);
    }

    /** The stories that are not done. */
    public List<Story> openStories() { return stories.stream().filter(s -> !s.done()).toList(); }

    /** The plan as text. */
    public String render() { return PlanWriter.render(this); }
}
