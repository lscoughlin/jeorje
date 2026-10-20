package org.github.lscoughlin.jeorje.cli;

import java.util.concurrent.Callable;
import org.github.lscoughlin.jeorje.core.session.IndexRebuilder;
import org.github.lscoughlin.jeorje.core.session.SessionStore;
import org.github.lscoughlin.jeorje.core.session.TurnChunker;
import org.github.lscoughlin.jeorje.index.store.LuceneContextStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code jeorje index rebuild}: embeds all stored history again from the transcript. */
@Command(name = "index", mixinStandardHelpOptions = true, description = "Index commands.",
        subcommands = IndexCommand.Rebuild.class)
public final class IndexCommand implements Callable<Integer> {

    private final CliEnvironment env;
    private final GlobalOptions options;

    IndexCommand(CliEnvironment env, GlobalOptions options) {
        this.env = env;
        this.options = options;
    }

    @Override
    public Integer call() {
        env.out().println("Use 'jeorje index rebuild'.");
        return 0;
    }

    /** The rebuild sub-command. */
    @Command(name = "rebuild", mixinStandardHelpOptions = true,
            description = "Build the index again from the transcript.")
    public static final class Rebuild implements Callable<Integer> {

        @Option(names = "--session", description = "Session id. Default: the latest session.")
        String sessionId;

        @picocli.CommandLine.ParentCommand
        IndexCommand parent;

        @Override
        public Integer call() {
            var env = parent.env;
            var loaded = Wiring.loadConfig(parent.options, env);
            Wiring.configureLogging(loaded.config(), env, true);
            var cwd = Wiring.workDir(parent.options, env);
            var sessions = new SessionStore(cwd);
            var found = sessionId == null ? sessions.resumeLatest() : sessions.resume(sessionId);
            if (found.isEmpty()) throw new CliException("No session found in this project.");
            var session = found.get();
            var embedder = Wiring.embedder(loaded.config(), env);
            try (var store = LuceneContextStore.open(session.indexDir(), embedder,
                    LuceneContextStore.Options.defaults().withUserBoost(loaded.config().index().userBoost()).withRebuild(true))) {
                var count = new IndexRebuilder(store,
                        TurnChunker.forFormat(session.id(), session.manifest().chunkFormat())).rebuild(session.transcriptPath());
                env.out().println("Rebuilt the index of session " + session.id() + ": " + count + " chunks"
                        + (embedder == null ? " (text-only)." : "."));
            } finally {
                if (embedder instanceof AutoCloseable c) {
                    try {
                        c.close();
                    } catch (Exception ignored) {
                        // nothing to do
                    }
                }
            }
            return 0;
        }
    }
}
