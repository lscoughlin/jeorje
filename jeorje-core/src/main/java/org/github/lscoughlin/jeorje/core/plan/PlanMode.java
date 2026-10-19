package org.github.lscoughlin.jeorje.core.plan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.permissions.ShellCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plan mode service. It holds the mode state, the tool set of the mode, the read-only check for
 * {@code bash}, and the plan-mode section of the system prompt.
 *
 * <p>Plan mode does not depend on any permission mode. The option {@code --yolo} does not change the
 * tool set or the {@code bash} check.
 */
public final class PlanMode {
    private static final Logger log = LoggerFactory.getLogger(PlanMode.class);

    /** The name of the tool that writes the plan. */
    public static final String PLAN_SUBMIT = "plan_submit";

    /** The tools of plan mode. {@code bash} is in the set, but only for read-only commands. */
    public static final Set<String> TOOLS = Set.of(
            "read", "ls", "glob", "grep", "recall", "webfetch", "skill", "ask_user", "advisor", "todo",
            PLAN_SUBMIT, "bash");

    /** The text on the status line while the mode is active. */
    public static final String STATUS_LABEL = "PLAN";

    /** The plan-mode section of the system prompt. */
    public static final String PROMPT_SECTION = """
        You are in plan mode. You cannot change the workspace. You write a plan.
        A plan is a list of stories. A worker finishes one story alone. The worker sees only the text of its story.
        Rules:
        1. Explore first. Read the code, the documents, and the tests with the read-only tools. Do not guess.
        2. If a requirement is open, ask the user with the ask_user tool. Do not invent the answer.
        3. Write each story so that a worker needs no other context. In the context part, name the files, the classes, the commands, and the facts that the worker needs. Do not write "as discussed". Do not refer to the conversation or to other stories. Use depends_on to set the order.
        4. Give each story a goal, acceptance criteria that a test can check, a context part, and tasks. Task ids start with the story id, for example S1.T1.
        5. Keep each story small. A story has 20 tasks at most and 8,000 characters at most. A plan has 50 stories at most.
        6. Two stories that can run at the same time must not touch the same path. Add a dependency or merge the stories.
        7. If an advisor model is set, call the advisor tool on your draft before you call plan_submit.
        8. Call plan_submit to write the plan. If it returns errors, correct the plan and call it again.
        You can use bash only for read-only commands. The tools write, edit, and spawn_agent are not available.
        After plan_submit, the user reviews the plan. Change the plan when the user asks. Call plan_submit again to replace it.""";

    private volatile boolean active;
    private volatile String goal = "";
    private volatile Path submitted;
    private volatile Path previous;
    private int submissions;

    /** True while plan mode is active. */
    public boolean active() { return active; }

    /** The goal of the current plan, or an empty string. */
    public String goal() { return goal; }

    /** The label for the status line, or an empty string outside plan mode. */
    public String statusLabel() { return active ? STATUS_LABEL : ""; }

    /** Enters plan mode. A blank goal keeps the current goal. */
    public synchronized void enter(String newGoal) {
        if (newGoal != null && !newGoal.isBlank()) {
            if (!newGoal.strip().equals(goal)) {
                submitted = null;
                previous = null;
                submissions = 0;
            }
            goal = newGoal.strip();
        }
        active = true;
        log.atInfo().log(() -> "plan mode on");
    }

    /** Leaves plan mode and keeps the plan. */
    public synchronized void done() {
        active = false;
        log.atInfo().log(() -> "plan mode off, plan kept");
    }

    /**
     * Leaves plan mode and discards the draft. A plan file that this mode created is deleted. If the
     * mode replaced an older plan once, the older plan comes back. If it replaced the plan more than
     * once, the older plan is no longer available, and the last draft stays.
     */
    public synchronized void cancel() throws IOException {
        active = false;
        var file = submitted;
        var prev = previous;
        var count = submissions;
        submitted = null;
        previous = null;
        submissions = 0;
        goal = "";
        if (file != null) {
            if (prev == null) {
                Files.deleteIfExists(file);
            } else if (count == 1 && Files.exists(prev)) {
                Files.move(prev, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        log.atInfo().log(() -> "plan mode off, draft discarded");
    }

    /**
     * Records a plan file that {@code plan_submit} wrote.
     *
     * @param previousCopy the {@code .prev.md} file that holds the replaced plan, or null if the plan is new
     */
    public synchronized void recordSubmission(Path file, Path previousCopy) {
        if (submitted == null || !submitted.equals(file)) {
            previous = previousCopy;
            submissions = 0;
        }
        submitted = file;
        submissions++;
    }

    /** The plan file that {@code plan_submit} wrote in this mode. */
    public Optional<Path> submittedPlan() { return Optional.ofNullable(submitted); }

    /** True if the tool is in the tool set of plan mode. */
    public static boolean allows(String tool) { return TOOLS.contains(tool); }

    /** The tools of plan mode in a stable order. */
    public static List<String> toolNames() { return TOOLS.stream().sorted().toList(); }

    /**
     * True if every part of a shell command is on the read-only allow list. The result does not depend
     * on the permission mode.
     */
    public static boolean bashAllowed(String command) {
        if (command == null || command.isBlank()) return false;
        var parts = ShellCommands.split(command);
        return !parts.isEmpty() && parts.stream().allMatch(ShellCommands::isReadOnly);
    }

    /** The plan-mode section, with the goal if there is one. */
    public String promptSection() {
        return goal.isBlank() ? PROMPT_SECTION : PROMPT_SECTION + "\nThe goal of the plan: " + goal;
    }
}
