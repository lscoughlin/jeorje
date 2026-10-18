package org.github.lscoughlin.jeorje.cli;

import java.util.concurrent.Callable;
import org.github.lscoughlin.jeorje.cli.interactive.InteractiveLauncher;
import org.github.lscoughlin.jeorje.core.session.SessionStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code jeorje resume [session]}: continues a session. The agent gets back its turns, its pinned items, and its
 * pruned working context. With {@code --task} the command continues without an interface and prints the answer.
 * Otherwise it opens the interactive interface on the session.
 */
@Command(name = "resume", mixinStandardHelpOptions = true, description = "Resume a session.")
public final class ResumeCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "[session]",
            description = "Session id. Default: the latest session of the project.")
    String sessionId;

    @Option(names = "--task", paramLabel = "<text>",
            description = "Continue the session with this task and no interface. Print the final answer.")
    String task;

    private final CliEnvironment env;
    private final GlobalOptions options;

    ResumeCommand(CliEnvironment env, GlobalOptions options) {
        this.env = env;
        this.options = options;
    }

    @Override
    public Integer call() {
        var sessions = new SessionStore(Wiring.workDir(options, env));
        var found = sessionId == null ? sessions.resumeLatest() : sessions.resume(sessionId);
        if (found.isEmpty()) {
            throw new CliException(sessionId == null ? "No session found in this project."
                    : "Session not found: " + sessionId);
        }
        var session = found.get();
        env.out().println("Session " + session.id() + " (" + session.manifest().lastTurn() + " turns, state "
                + session.manifest().runState() + ")");
        if (task == null) return InteractiveLauncher.launch(env, options, session.id());
        return continueHeadless(sessions, session);
    }

    private int continueHeadless(SessionStore sessions,
                                 org.github.lscoughlin.jeorje.core.session.Session session) {
        var loaded = Wiring.loadConfig(options, env);
        Wiring.configureLogging(loaded.config(), env, true);
        Wiring.warnUnattended(options, env);
        loaded.warnings().forEach(w -> env.err().println("Warning: " + w));
        try (var runtime = Wiring.openResumed(loaded, options, env, sessions, session)) {
            var before = runtime.agent().turns().size();
            env.err().println("Resumed " + before + " turns.");
            runtime.saveManifest("running");
            runtime.agent().runTurn(task);
            runtime.saveManifest("idle");
            var answer = RunCommand.finalAnswer(runtime);
            if (!answer.isBlank()) env.out().println(answer);
            runtime.errors().forEach(e -> env.err().println("Error: " + e));
            return runtime.errors().isEmpty() ? 0 : 1;
        }
    }
}
