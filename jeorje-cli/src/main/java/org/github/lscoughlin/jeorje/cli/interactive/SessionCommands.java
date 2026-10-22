package org.github.lscoughlin.jeorje.cli.interactive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.implement.ImplementRequest;
import org.github.lscoughlin.jeorje.core.implement.PlanFiles;
import org.github.lscoughlin.jeorje.swarm.PeerState;
import org.github.lscoughlin.jeorje.ui.browser.FileBrowser;
import org.github.lscoughlin.jeorje.ui.commands.CommandResult;
import org.github.lscoughlin.jeorje.ui.commands.SlashCommands;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;

/**
 * The commands that need the whole session: planning, {@code /implement}, worktrees, the agent list, the file
 * browser, the editor, and the peers.
 */
final class SessionCommands {
    private static final Pattern STORY_ID = Pattern.compile("[A-Za-z]{1,4}\\d+");

    private final InteractiveSession session;

    SessionCommands(InteractiveSession session) {
        this.session = session;
    }

    void register(SlashCommands slash) {
        slash.register("plan", "/plan [goal|show|edit|done|cancel]", "Enter plan mode, or manage the plan", this::plan);
        slash.register("implement", "/implement [plan] [stories|pause|resume|status|stop]",
                "Implement a plan with workers", this::implement);
        slash.register("worktrees", "/worktrees [prune]", "List the worktrees, or remove leftover ones", this::worktrees);
        slash.register("merge", "/merge <agent> [strategy]", "Merge the worktree of an agent", this::merge);
        slash.register("discard", "/discard <agent>", "Discard the worktree and the branch of an agent", this::discard);
        slash.register("agents", "/agents [name|number]", "List the agents, or switch to one", this::agents);
        slash.register("files", "/files [list [filter]]", "Open the file browser, or print the tree", this::files);
        slash.register("edit", "/edit <path>", "Open a file in the editor", this::edit);
        slash.register("peers", "/peers", "List the peers of the swarm", a -> peers());
        slash.register("mcp", "/mcp", "List the MCP servers and their tools", a -> CommandResult.ok(session.mcpReport()));
    }

    // ---- plan ----

    private CommandResult plan(String args) {
        var mode = session.planMode();
        var arg = args.strip();
        switch (arg) {
            case "" -> {
                if (!mode.active()) {
                    enterPlanMode("");
                    var goal = mode.goal();
                    if (goal.isBlank()) return CommandResult.ok("Plan mode is on. Tell the agent the goal of the plan.");
                    session.sendPrompt("Continue the plan for this goal: " + goal, null);
                    return CommandResult.ok("Plan mode is on. Goal: " + goal);
                }
                return show();
            }
            case "show" -> {
                return show();
            }
            case "edit" -> {
                var file = currentPlan();
                if (file == null) return CommandResult.fail("There is no plan file yet.");
                session.editFile(file);
                return CommandResult.ok("");
            }
            case "done" -> {
                if (!mode.active()) return CommandResult.fail("Plan mode is not on.");
                mode.done();
                session.agent().planMode(null);
                session.refreshStatus();
                var file = mode.submittedPlan();
                return CommandResult.ok("Plan mode is off. " + (file.isPresent() ? "The plan is in " + file.get() + "."
                        : "No plan was written."));
            }
            case "cancel" -> {
                if (!mode.active()) return CommandResult.fail("Plan mode is not on.");
                try {
                    mode.cancel();
                } catch (IOException e) {
                    return CommandResult.fail("Cannot discard the draft: " + e.getMessage());
                }
                session.agent().planMode(null);
                session.refreshStatus();
                return CommandResult.ok("Plan mode is off. The draft is discarded.");
            }
            default -> {
                enterPlanMode(arg);
                session.sendPrompt(arg, null);
                return CommandResult.ok("Plan mode is on. Goal: " + arg);
            }
        }
    }

    private void enterPlanMode(String goal) {
        session.planMode().enter(goal);
        session.agent().planMode(session.planMode().promptSection());
        session.refreshStatus();
    }

    private CommandResult show() {
        var file = currentPlan();
        if (file == null) return CommandResult.ok("There is no plan file yet.");
        try {
            return CommandResult.ok(file + "\n" + Files.readString(file));
        } catch (IOException e) {
            return CommandResult.fail("Cannot read " + file + ": " + e.getMessage());
        }
    }

    private Path currentPlan() {
        var submitted = session.planMode().submittedPlan();
        if (submitted.isPresent()) return submitted.get();
        var dir = session.planDir();
        if (!Files.isDirectory(dir)) return null;
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".md")
                            && !p.getFileName().toString().endsWith(".prev.md"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // ---- implement ----

    private CommandResult implement(String args) {
        var orchestrator = session.orchestrator();
        var tokens = args.isBlank() ? new String[0] : args.strip().split("\\s+");
        if (tokens.length == 1) {
            switch (tokens[0]) {
                case "pause" -> {
                    if (!orchestrator.active()) return CommandResult.fail("No run is active.");
                    orchestrator.pause();
                    return CommandResult.ok("The run is paused. Running workers finish.");
                }
                case "resume" -> {
                    if (!orchestrator.active()) return CommandResult.fail("No run is active.");
                    orchestrator.resume();
                    return CommandResult.ok("The run continues.");
                }
                case "stop" -> {
                    if (!orchestrator.active()) return CommandResult.fail("No run is active.");
                    session.gate().denyAll();
                    orchestrator.stop();
                    return CommandResult.ok("The run is stopped.");
                }
                case "status" -> {
                    return CommandResult.ok(orchestrator.status());
                }
                default -> { }
            }
        }
        String ref = null;
        var only = new LinkedHashSet<String>();
        for (var i = 0; i < tokens.length; i++) {
            if (i == 0 && !STORY_ID.matcher(tokens[i]).matches()) ref = tokens[i];
            else only.add(tokens[i]);
        }
        var dir = session.planDir();
        var plan = PlanFiles.find(dir, ref);
        if (plan.isEmpty()) {
            return CommandResult.fail(ref == null ? "No plan with open stories in " + dir + "."
                    : "No plan named " + ref + " in " + dir + ".");
        }
        if (ref == null) {
            var open = PlanFiles.openPlans(dir);
            if (open.size() > 1) {
                var sb = new StringBuilder("More than one plan has open stories. Use /implement <plan>:");
                open.forEach(p -> sb.append("\n  ").append(p.getFileName()));
                return CommandResult.fail(sb.toString());
            }
        }
        var manager = session.worktrees();
        if (manager != null) {
            var status = manager.git().run(session.cwd(), "status", "--porcelain");
            if (status.ok() && !status.trimmed().isEmpty()) {
                session.notice(InteractiveSession.MAIN, "The working tree has uncommitted changes. The workers start "
                        + "from the last commit and do not see them.");
            }
        }
        var yes = session.options().yes() || session.options().yolo();
        try {
            orchestrator.start(new ImplementRequest(plan.get(), only, yes));
        } catch (IllegalStateException e) {
            return CommandResult.fail(e.getMessage());
        }
        return CommandResult.ok("Implementing " + plan.get().getFileName() + ".");
    }

    // ---- worktrees ----

    private CommandResult worktrees(String args) {
        var commands = session.worktreeCommands();
        if (commands == null) return CommandResult.fail("Worktrees are not available. This is not a git repository.");
        if (args.strip().equals("prune")) return result(commands.prune());
        if (!args.isBlank()) return CommandResult.fail("Usage: /worktrees [prune]");
        return CommandResult.ok(commands.listText().stripTrailing());
    }

    private CommandResult merge(String args) {
        var commands = session.worktreeCommands();
        if (commands == null) return CommandResult.fail("Worktrees are not available. This is not a git repository.");
        var parts = args.isBlank() ? new String[0] : args.strip().split("\\s+");
        if (parts.length == 0) return CommandResult.fail("Usage: /merge <agent> [strategy]");
        return result(commands.merge(parts[0], parts.length > 1 ? parts[1] : null));
    }

    private CommandResult discard(String args) {
        var commands = session.worktreeCommands();
        if (commands == null) return CommandResult.fail("Worktrees are not available. This is not a git repository.");
        if (args.isBlank()) return CommandResult.fail("Usage: /discard <agent>");
        return result(commands.discard(args.strip()));
    }

    private static CommandResult result(org.github.lscoughlin.jeorje.tools.worktree.WorktreeCommands.Outcome outcome) {
        return outcome.ok() ? CommandResult.ok(outcome.message()) : CommandResult.fail(outcome.message());
    }

    // ---- agents, files, editor, peers ----

    private CommandResult agents(String args) {
        var roster = session.roster();
        var switcher = session.switcher();
        if (args.isBlank()) {
            var size = session.realTerminal().getSize();
            switcher.setBounds(new Rect(0, 0, Math.max(1, size.getColumns()), Math.max(50, roster.agents().size() * 2)));
            switcher.open();
            var sb = new StringBuilder("Agents (harness, agent, subagent; each level is indented)\n");
            var lines = switcher.render();
            for (var i = 0; i < lines.size(); i++) sb.append(String.format("%2d ", i + 1)).append(lines.get(i)).append('\n');
            switcher.close();
            sb.append("Use /agents <name or number> to switch. Use /back to return. The key Ctrl-X a opens the switcher pane.");
            return CommandResult.ok(sb.toString());
        }
        var found = roster.find(args.strip());
        if (found.isEmpty()) return CommandResult.fail("No agent: " + args.strip());
        if (found.get().id().value().startsWith(AgentRoster.PEER_PREFIX)) {
            return CommandResult.fail(found.get().name() + " is a remote instance. Pick one of its agents in the switcher pane.");
        }
        switcher.switchTo(found.get().id());
        return CommandResult.ok("Foreground agent: " + found.get().name() + ". Use /back to return.");
    }

    private CommandResult files(String args) {
        var arg = args.strip();
        if (arg.isEmpty()) {
            session.openBrowser();
            return CommandResult.ok("");
        }
        var filter = "";
        if (arg.equals("list")) {
            filter = "";
        } else if (arg.startsWith("list ")) {
            filter = arg.substring(5).strip();
        } else {
            return CommandResult.fail("Usage: /files [list [filter]]");
        }
        var size = session.realTerminal().getSize();
        var browser = new FileBrowser(session.cwd(), new Rect(0, 0, Math.max(1, size.getColumns()),
                Math.max(1, size.getRows())), session.config().ui().mouse().wheelLines());
        browser.open();
        if (!filter.isEmpty()) browser.setFilter(filter);
        // The text listing shows every row, so the bounds cover the whole tree.
        browser.setBounds(new Rect(0, 0, Math.max(1, size.getColumns()), Math.max(1, browser.rows().size())));
        var lines = new ArrayList<String>();
        lines.add("Files in " + session.cwd() + (filter.isEmpty() ? "" : " (filter: " + filter + ")"));
        lines.addAll(browser.render());
        lines.add("Use /edit <path> to open a file. The key Ctrl-X f opens the file browser pane.");
        return CommandResult.ok(String.join("\n", lines));
    }

    private CommandResult edit(String args) {
        if (args.isBlank()) return CommandResult.fail("Usage: /edit <path>");
        var path = session.cwd().resolve(args.strip()).normalize();
        session.editFile(path);
        return CommandResult.ok("");
    }

    private CommandResult peers() {
        var swarm = session.swarm();
        if (swarm == null) return CommandResult.ok("The swarm is off. Set swarm.enabled or start with --peer host:port.");
        var sb = new StringBuilder("Peers\n");
        var self = swarm.self();
        sb.append("  ").append(self.name()).append(" (this instance) ").append(self.address()).append('\n');
        var any = false;
        for (var peer : swarm.members().values()) {
            if (peer.peerId().equals(self.peerId())) continue;
            any = true;
            sb.append("  ").append(peer.name()).append(' ').append(peer.address()).append(' ').append(peer.state());
            if (!peer.workspace().isEmpty()) sb.append(' ').append(peer.workspace());
            if (!peer.capabilities().isEmpty()) sb.append(" [").append(String.join(", ", peer.capabilities())).append(']');
            sb.append('\n');
        }
        if (!any) sb.append("  No other peers.\n");
        sb.append("Alive peers: ").append(swarm.peers(PeerState.ALIVE).size());
        return CommandResult.ok(sb.toString());
    }
}
