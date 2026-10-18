package org.github.lscoughlin.jeorje.cli;

import java.util.concurrent.Callable;
import org.github.lscoughlin.jeorje.core.model.Role;
import org.github.lscoughlin.jeorje.core.model.Text;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code jeorje run "<task>"}: runs one task with no interface and prints the final answer. */
@Command(name = "run", mixinStandardHelpOptions = true, description = "Run a task with no interface.")
public final class RunCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<task>",
            description = "The task for the agent. \"/plan <goal>\" writes a plan. "
                    + "\"/implement <plan> [stories]\" implements a plan.")
    String task;

    @Option(names = "--plan", paramLabel = "<path>",
            description = "Implement the plan in this file. The task, if any, lists the story ids.")
    String plan;

    private final CliEnvironment env;
    private final GlobalOptions options;

    RunCommand(CliEnvironment env, GlobalOptions options) {
        this.env = env;
        this.options = options;
    }

    @Override
    public Integer call() {
        if (plan != null) return HeadlessWork.runPlan(env, options, plan, task);
        if (task == null || task.isBlank()) {
            env.err().println("Error: give a task, or use --plan <path>.");
            return 1;
        }
        if (HeadlessWork.isCommand(task)) return HeadlessWork.runCommand(env, options, task);
        var loaded = Wiring.loadConfig(options, env);
        Wiring.configureLogging(loaded.config(), env, true);
        Wiring.warnUnattended(options, env);
        loaded.warnings().forEach(w -> env.err().println("Warning: " + w));
        try (var runtime = Wiring.open(loaded, options, env)) {
            runtime.saveManifest("running");
            runtime.agent().runTurn(task);
            runtime.saveManifest("idle");
            var answer = finalAnswer(runtime);
            if (!answer.isBlank()) env.out().println(answer);
            runtime.errors().forEach(e -> env.err().println("Error: " + e));
            return runtime.errors().isEmpty() ? 0 : 1;
        }
    }

    /** The text of the last assistant message of the last turn. */
    static String finalAnswer(Wiring.Runtime runtime) {
        var turns = runtime.agent().turns();
        if (turns.isEmpty()) return "";
        var messages = turns.getLast().messages();
        for (var i = messages.size() - 1; i >= 0; i--) {
            var message = messages.get(i);
            if (message.role() == Role.ASSISTANT) {
                var sb = new StringBuilder();
                message.content().forEach(c -> {
                    if (c instanceof Text t) sb.append(t.text());
                });
                if (!sb.isEmpty()) return sb.toString();
            }
        }
        return "";
    }
}
