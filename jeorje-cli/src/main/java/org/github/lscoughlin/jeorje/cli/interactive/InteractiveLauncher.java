package org.github.lscoughlin.jeorje.cli.interactive;

import java.io.IOException;
import org.github.lscoughlin.jeorje.cli.CliEnvironment;
import org.github.lscoughlin.jeorje.cli.CliException;
import org.github.lscoughlin.jeorje.cli.GlobalOptions;
import org.jline.terminal.TerminalBuilder;

/** Starts the interactive interface on the terminal of the process. */
public final class InteractiveLauncher {

    private InteractiveLauncher() {}

    /**
     * Opens the system terminal and runs a session. When the process has no terminal, it prints a hint and
     * returns 0.
     */
    public static int launch(CliEnvironment env, GlobalOptions options) {
        return launch(env, options, null);
    }

    /**
     * Like {@link #launch(CliEnvironment, GlobalOptions)}, and resumes a session.
     *
     * @param resume the id of the session to resume, an empty string for the latest session, or null for a new one
     */
    public static int launch(CliEnvironment env, GlobalOptions options, String resume) {
        var console = System.console();
        if (console == null || !console.isTerminal()) {
            env.out().println("The interactive interface needs a terminal. "
                    + "Use 'jeorje run \"<task>\"' to run a task without an interface.");
            return 0;
        }
        try (var terminal = TerminalBuilder.builder().system(true).build()) {
            return new InteractiveSession(env, options, terminal, InteractiveSession.Hooks.defaults(), resume).run();
        } catch (IOException e) {
            throw new CliException("Cannot open the terminal: " + e.getMessage());
        }
    }
}
