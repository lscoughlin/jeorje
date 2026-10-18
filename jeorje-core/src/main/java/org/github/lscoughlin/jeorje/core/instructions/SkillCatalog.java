package org.github.lscoughlin.jeorje.core.instructions;

import java.util.ArrayList;
import java.util.List;

/** Builds the skill catalog text for the system prompt. */
public final class SkillCatalog {

    public static final String INSTRUCTIONS = """
            The following skills give instructions for specific tasks. When a task matches
            the description of a skill, call the skill tool with its name before you
            continue. Relative paths in a skill are relative to the folder of its SKILL.md.""";

    /** The catalog text and the skills that did not fit. */
    public record Result(String text, List<Skill> listed, List<Skill> omitted) {
    }

    private SkillCatalog() {
    }

    public static Result build(List<Skill> visibleSkills, int maxListChars) {
        if (visibleSkills.isEmpty()) {
            return new Result("", List.of(), List.of());
        }
        var sb = new StringBuilder(INSTRUCTIONS).append("\n\n<available_skills>\n");
        var closing = "</available_skills>";
        var listed = new ArrayList<Skill>();
        var omitted = new ArrayList<Skill>();
        for (var skill : visibleSkills) {
            var entry = "  <skill>\n    <name>" + escape(skill.name()) + "</name>\n    <description>"
                    + escape(skill.description()) + "</description>\n    <location>" + skill.location()
                    + "</location>\n  </skill>\n";
            if (omitted.isEmpty() && sb.length() + entry.length() + closing.length() <= maxListChars) {
                sb.append(entry);
                listed.add(skill);
            } else {
                omitted.add(skill);
            }
        }
        sb.append(closing);
        return new Result(sb.toString(), List.copyOf(listed), List.copyOf(omitted));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", " ");
    }
}
