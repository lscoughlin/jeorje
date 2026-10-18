package org.github.lscoughlin.jeorje.core.instructions;

import java.nio.file.Path;
import java.util.Map;

/** A loaded skill. {@code location} is the absolute path of SKILL.md. */
public record Skill(String name, String description, Path location, Scopes.Scope scope, String body,
                    String license, String compatibility, Map<String, String> metadata,
                    String allowedTools, boolean disableModelInvocation) {

    public Path directory() {
        return location.getParent();
    }
}
