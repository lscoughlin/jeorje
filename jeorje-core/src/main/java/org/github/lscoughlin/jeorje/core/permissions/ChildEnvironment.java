package org.github.lscoughlin.jeorje.core.permissions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Builds the minimal environment for child processes. It never has API keys. */
public final class ChildEnvironment {

    private static final Set<String> KEEP = Set.of(
            "PATH", "HOME", "USER", "LOGNAME", "LANG", "TERM", "TMPDIR", "SHELL", "TZ", "PWD");

    private static final Pattern SECRET_NAME =
            Pattern.compile("(?i).*(key|token|secret|password|passwd|credential|auth).*");

    private ChildEnvironment() {
    }

    /** Keeps only safe names from the given environment. This holds also in {@code --yolo}. */
    public static Map<String, String> minimal(Map<String, String> environment) {
        var out = new LinkedHashMap<String, String>();
        environment.forEach((name, value) -> {
            var allowed = KEEP.contains(name) || name.startsWith("LC_");
            if (allowed && !SECRET_NAME.matcher(name).matches()) out.put(name, value);
        });
        return out;
    }

    /** The minimal environment of this process. */
    public static Map<String, String> minimal() {
        return minimal(System.getenv());
    }
}
