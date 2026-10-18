package org.github.lscoughlin.jeorje.core.config;

import java.nio.file.Path;

/** Expands {@code ~}, {@code ${HOME}}, and {@code ${CWD}} at the start of a path value. */
public final class PathExpander {

    private PathExpander() {
    }

    public static String expand(String value, Path home, Path cwd) {
        if (value == null) {
            return null;
        }
        if (value.equals("~")) {
            return home.toString();
        }
        if (value.startsWith("~/")) {
            return home + value.substring(1);
        }
        if (value.startsWith("${HOME}")) {
            return home + value.substring("${HOME}".length());
        }
        if (value.startsWith("${CWD}")) {
            return cwd + value.substring("${CWD}".length());
        }
        return value;
    }
}
