package org.github.lscoughlin.jeorje.core.plan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.model.Notes;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plan mode service. It holds the mode state, the tool set of the mode, and the plan-mode section
 * of the system prompt.
 *
 * <p>Plan mode does not depend on any permission mode. The option {@code --yolo} does not change the
 * tool set. {@code bash} is in the tool set. The normal permission rules decide each {@code bash} call.
 */
public final class PlanMode {
    private static final Logger log = LoggerFactory.getLogger(PlanMode.class);

    /** The name of the tool that writes the plan. */
    public static final String PLAN_SUBMIT = "plan_submit";

    /** The tools of plan mode. */
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
        2. If a requirement is open, ask the user with the ask_user tool. Put related questions in one form. Give choices when you know the likely answers. Use the kind multi when the user can pick several. Do not invent the answer.
        3. Write each story so that a worker needs no other context. In the context part, name the files, the classes, the commands, and the facts that the worker needs. Do not write "as discussed". Do not refer to the conversation or to other stories. Use depends_on to set the order.
        4. Give each story a goal, acceptance criteria that a test can check, a context part, and tasks. Task ids start with the story id, for example S1.T1.
        5. Keep each story small. A story has 20 tasks at most and 8,000 characters at most. A plan has 50 stories at most.
        6. Two stories that can run at the same time must not touch the same path. Add a dependency or merge the stories.
        7. Call the advisor tool on your draft before you call plan_submit. If the tool answers that the advisor is off, go on without it.
        8. Call plan_submit to write the plan. If it returns errors, correct the plan and call it again.
        Explore with a limit. Use grep to find a place, and read with offset and limit to read only the part that you need. Do not read a file that you already read: its content is in your context or in the history, and recall finds it. Ask all open questions early, in one ask_user form with several questions. Start to write the plan as soon as you know enough. Open facts that remain go into the context of the story as a task for the worker.
        You can use bash to explore: to run the build, the tests, and other commands that show how the project works. Prefer grep and read to bash for reading files. Do not use bash to change the workspace. Do not create, edit, or delete project files with it.
        The tools write, edit, and spawn_agent are not available.
        After plan_submit, the user reviews the plan. Change the plan when the user asks. Call plan_submit again to replace it.""";

    /** The default number of tool calls for exploring before the agent must write the plan. */
    public static final int DEFAULT_MAX_EXPLORE_CALLS = 40;
    /** The tool calls after the limit that the mode allows before it restricts the tool set. */
    public static final int GRACE_CALLS = 10;
    /** The tools that still work after the grace calls. */
    private static final Set<String> AFTER_LIMIT = Set.of(PLAN_SUBMIT, "ask_user", "recall");
    private static final Set<String> NOT_COUNTED = Set.of(PLAN_SUBMIT, "ask_user", "todo", "advisor");

    private volatile boolean active;
    private int maxExploreCalls = DEFAULT_MAX_EXPLORE_CALLS;
    private int exploreCalls;
    private boolean warned;
    private boolean limitNoted;
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
        resetExploration();
        log.atInfo().log(() -> "plan mode on");
    }

    /** Sets the number of tool calls for exploring, from the config key {@code plan.maxExploreCalls}. */
    public synchronized PlanMode maxExploreCalls(int calls) {
        maxExploreCalls = Math.max(1, calls);
        return this;
    }

    /** The number of tool calls for exploring. */
    public synchronized int maxExploreCalls() { return maxExploreCalls; }

    /** The tool calls since the last plan, question, or message of the user. */
    public synchronized int exploreCalls() { return exploreCalls; }

    /** Starts the count of exploring calls again. A message of the user, a question, or a plan does this. */
    public synchronized void resetExploration() {
        exploreCalls = 0;
        warned = false;
        limitNoted = false;
    }

    /**
     * Counts one tool call of the model and says whether plan mode refuses it. A call of {@code plan_submit} or
     * {@code ask_user} starts the count again. After the limit and the grace calls, only {@code plan_submit},
     * {@code ask_user}, and {@code recall} work, until one of the two first tools runs.
     */
    public synchronized Optional<String> refuse(ToolUse use) {
        if (!active) return Optional.empty();
        var tool = use.tool();
        if (tool.equals(PLAN_SUBMIT) || tool.equals("ask_user")) {
            resetExploration();
            return Optional.empty();
        }
        if (NOT_COUNTED.contains(tool)) return Optional.empty();
        exploreCalls++;
        if (exploreCalls > maxExploreCalls + GRACE_CALLS && !AFTER_LIMIT.contains(tool)) {
            return Optional.of("Plan mode refused " + tool + ": you made " + exploreCalls + " tool calls without a "
                + "plan. Call plan_submit now with what you know, or ask the user with ask_user.");
        }
        return Optional.empty();
    }

    /**
     * The notes for the agent at a step boundary. Each note comes once for each count. The note at 75% of the limit
     * tells the agent to write the plan soon. The note at the limit tells it to call {@code plan_submit} now.
     */
    public synchronized List<String> notes() {
        if (!active) return List.of();
        var out = new java.util.ArrayList<String>();
        if (!warned && exploreCalls >= Math.ceil(maxExploreCalls * 0.75)) {
            warned = true;
            if (exploreCalls < maxExploreCalls) {
                out.add(Notes.of("You made " + exploreCalls + " tool calls. Write the plan soon. If a fact is open, "
                    + "ask the user with ask_user."));
            }
        }
        if (!limitNoted && exploreCalls >= maxExploreCalls) {
            warned = true;
            limitNoted = true;
            out.add(Notes.of("You reached the limit of " + maxExploreCalls + " tool calls for exploring. Call "
                + "plan_submit now with what you know. Put each open fact in the context of the story as a question "
                + "for the worker, or ask the user with ask_user. After " + GRACE_CALLS + " more calls, only "
                + "plan_submit, ask_user, and recall work."));
        }
        return out;
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

    /** The plan-mode section, with the goal if there is one. */
    public String promptSection() {
        var limit = "\nYou have " + maxExploreCalls + " tool calls to explore before you must write the plan.";
        return goal.isBlank() ? PROMPT_SECTION + limit : PROMPT_SECTION + limit + "\nThe goal of the plan: " + goal;
    }
}
