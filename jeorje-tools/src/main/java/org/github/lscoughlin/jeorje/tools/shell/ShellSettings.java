package org.github.lscoughlin.jeorje.tools.shell;

import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.core.permissions.ChildEnvironment;

/**
 * Settings of the {@code bash} tool.
 *
 * @param shell the shell command and its flags. The command text is added as the last argument.
 * @param sourceEnvironment the environment that {@link ChildEnvironment} filters
 * @param headChars the number of characters kept from the start of long output
 * @param tailChars the number of characters kept from the end of long output
 * @param listener receives the output while the command runs
 */
public record ShellSettings(List<String> shell, Map<String, String> sourceEnvironment, int headChars,
                            int tailChars, OutputListener listener) {
    public ShellSettings {
        shell = List.copyOf(shell);
        sourceEnvironment = Map.copyOf(sourceEnvironment);
        listener = listener == null ? OutputListener.NONE : listener;
        if (shell.isEmpty()) throw new IllegalArgumentException("shell is empty");
        if (headChars < 0 || tailChars < 1) throw new IllegalArgumentException("invalid output limits");
    }

    /** The defaults: {@code /bin/sh -c}, the environment of this process, and 10000 + 14000 characters. */
    public static ShellSettings defaults() {
        return new ShellSettings(List.of("/bin/sh", "-c"), System.getenv(), 10_000, 14_000, OutputListener.NONE);
    }

    public ShellSettings withShell(List<String> value) {
        return new ShellSettings(value, sourceEnvironment, headChars, tailChars, listener);
    }

    public ShellSettings withSourceEnvironment(Map<String, String> value) {
        return new ShellSettings(shell, value, headChars, tailChars, listener);
    }

    public ShellSettings withLimits(int head, int tail) {
        return new ShellSettings(shell, sourceEnvironment, head, tail, listener);
    }

    public ShellSettings withListener(OutputListener value) {
        return new ShellSettings(shell, sourceEnvironment, headChars, tailChars, value);
    }
}
