package org.github.lscoughlin.jeorje.cli;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.github.lscoughlin.jeorje.core.config.ConfigException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/** The entry point. picocli parses the command line. */
@Command(name = "jeorje", mixinStandardHelpOptions = true, version = "jeorje 0.1.0",
        description = "A Java coding agent with a queryable context index.")
public final class JeorjeMain implements Callable<Integer> {

    @Mixin
    final GlobalOptions global = new GlobalOptions();

    @Option(names = "--resume", arity = "0..1", fallbackValue = "", paramLabel = "[session]",
            description = "Open the interface on an earlier session. Default: the latest session.")
    String resume;

    private final CliEnvironment env;

    public JeorjeMain(CliEnvironment env) {
        this.env = env;
    }

    public static void main(String[] args) {
        System.exit(execute(CliEnvironment.system(), args));
    }

    /** Runs the command line and returns the exit code. */
    public static int execute(CliEnvironment env, String... args) {
        var main = new JeorjeMain(env);
        var line = new CommandLine(main);
        line.setOut(new PrintWriter(env.out(), true));
        line.setErr(new PrintWriter(env.err(), true));
        line.addSubcommand("run", new RunCommand(env, main.global));
        line.addSubcommand("resume", new ResumeCommand(env, main.global));
        line.addSubcommand("index", new IndexCommand(env, main.global));
        line.addSubcommand("config", new ConfigCommand(env, main.global));
        line.setExecutionExceptionHandler((e, cmd, parse) -> {
            var message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            env.err().println("Error: " + message);
            return e instanceof CliException || e instanceof ConfigException ? 2 : 1;
        });
        return line.execute(args);
    }

    @Override
    public Integer call() {
        return org.github.lscoughlin.jeorje.cli.interactive.InteractiveLauncher.launch(env, global, resume);
    }
}
