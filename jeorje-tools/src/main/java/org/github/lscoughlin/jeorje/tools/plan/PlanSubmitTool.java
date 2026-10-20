package org.github.lscoughlin.jeorje.tools.plan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.plan.Plan;
import org.github.lscoughlin.jeorje.core.plan.PlanException;
import org.github.lscoughlin.jeorje.core.plan.PlanMode;
import org.github.lscoughlin.jeorje.core.plan.PlanParser;
import org.github.lscoughlin.jeorje.core.plan.PlanValidator;
import org.github.lscoughlin.jeorje.core.plan.PlanWriter;
import org.github.lscoughlin.jeorje.core.plan.Story;
import org.github.lscoughlin.jeorje.core.plan.Task;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import org.github.lscoughlin.jeorje.tools.registry.Required;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code plan_submit} tool. It checks a plan and writes it to the plan directory. It is the only
 * tool of plan mode that writes. It writes only inside the plan directory. If the plan file exists,
 * the tool keeps the old file as {@code <name>.prev.md}.
 */
public final class PlanSubmitTool extends RecordTool<PlanSubmitTool.Input> {
    private static final Logger log = LoggerFactory.getLogger(PlanSubmitTool.class);

    /** The name of the tool. */
    public static final String NAME = PlanMode.PLAN_SUBMIT;

    /** One task of a story. */
    public record TaskInput(
            @Required @Describe("The task id. It starts with the story id and a dot, for example S1.T1.") String id,
            @Required @Describe("What to do, in one line.") String text) {}

    /** One story. */
    public record StoryInput(
            @Required @Describe("The story id, for example S1.") String id,
            @Required @Describe("A short title.") String title,
            @Required @Describe("The goal of the story, in one or two sentences.") String goal,
            @Describe("Ids of stories that must finish first. Empty or missing means none.") List<String> dependsOn,
            @Describe("Paths that the story changes. A directory ends with /.") List<String> touches,
            @Describe("The model for the worker. Missing means the default model.") String model,
            @Required @Describe("The files, classes, commands, and facts that the worker needs. No reference to the "
                    + "conversation or to other stories.") String context,
            @Required @Describe("Checks that show the story is done. One entry for each criterion.")
            List<String> acceptanceCriteria,
            @Describe("The command that checks the story. Missing means the plan verification.") String verification,
            @Required @Describe("The tasks, in order. At least one.") List<TaskInput> tasks) {}

    /** The input of {@code plan_submit}. */
    public record Input(
            @Required @Describe("The title of the plan.") String title,
            @Required @Describe("The goal of the plan, in one or two sentences.") String goal,
            @Describe("The command that checks the whole plan, for example mvn verify.") String verification,
            @Describe("The file name without extension. Missing means a name from the title. "
                    + "Use the same name to replace a plan.") String name,
            @Required @Describe("The stories.") List<StoryInput> stories) {}

    private final String planDir;
    private final PlanMode mode;
    private final Consumer<Path> onSubmitted;

    /**
     * @param planDir the plan directory, as in {@code plan.dir}. A relative path is relative to the working directory.
     * @param mode the plan mode service that records the submission, or null
     * @param onSubmitted called with the plan file after a successful write, or null
     */
    public PlanSubmitTool(String planDir, PlanMode mode, Consumer<Path> onSubmitted) {
        super(NAME, "Check the plan and write it to the plan directory. This is the only tool that writes in plan "
                + "mode. If a check fails, the result names the story and the rule. Correct the plan and call the "
                + "tool again. A call with the same name replaces the plan file.", Input.class, false);
        this.planDir = planDir == null || planDir.isBlank() ? "plan" : planDir;
        this.mode = mode;
        this.onSubmitted = onSubmitted;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) throws IOException {
        var today = LocalDate.now(context.clock());
        var dir = context.workingDirectory().toAbsolutePath().resolve(planDir).normalize();
        var named = input.name() != null && !input.name().isBlank();
        var earlier = mode == null ? null : mode.submittedPlan().orElse(null);
        // A call with no name replaces the plan that this mode wrote before. A new title does not make a new file.
        var slug = !named && earlier != null && earlier.startsWith(dir) && Files.isRegularFile(earlier)
                ? earlier.getFileName().toString().replaceAll("\\.md$", "")
                : slug(named ? input.name() : input.title());
        var file = dir.resolve(slug + ".md").normalize();
        var prevFile = dir.resolve(slug + ".prev.md").normalize();
        if (!file.startsWith(dir) || !prevFile.startsWith(dir) || Files.isSymbolicLink(file) || Files.isSymbolicLink(prevFile)) {
            return ToolResultData.error("The plan file must be inside the plan directory " + planDir + ".");
        }
        if (Files.isDirectory(file)) return ToolResultData.error(file.getFileName() + " is a directory.");

        var created = today.toString();
        var existed = Files.isRegularFile(file);
        if (existed) {
            try {
                var old = PlanParser.parse(file);
                created = old.front().getOrDefault("created", created);
            } catch (PlanException | IOException e) {
                log.atDebug().log(() -> "old plan file is not readable: " + e.getMessage());
            }
        }

        var plan = toPlan(input, created, today.toString());
        var violations = PlanValidator.validate(plan);
        if (!violations.isEmpty()) return ToolResultData.error(PlanValidator.format(violations));

        var text = plan.render();
        try {
            var back = PlanParser.parse(text);
            if (back.stories().size() != plan.stories().size()
                    || !back.stories().stream().map(s -> s.tasks().size()).toList()
                            .equals(plan.stories().stream().map(s -> s.tasks().size()).toList())) {
                return ToolResultData.error("The plan text breaks the plan file format. Do not use lines that start "
                        + "with '## ' or '### ' in the text of a story.");
            }
        } catch (PlanException e) {
            return ToolResultData.error("The plan text breaks the plan file format: " + e.getMessage()
                    + ". Do not use lines that start with '## ' or '### ' in the text of a story.");
        }

        Files.createDirectories(dir);
        if (existed) Files.copy(file, prevFile, StandardCopyOption.REPLACE_EXISTING);
        PlanWriter.write(plan, file);
        written.put(file, Files.readString(file));
        var moved = removeMovedDraft(earlier, file, dir);
        if (mode != null) mode.recordSubmission(file, existed ? prevFile : null);
        if (onSubmitted != null) onSubmitted.accept(file);

        var shown = context.workingDirectory().toAbsolutePath().normalize().relativize(file.toAbsolutePath());
        var tasks = plan.stories().stream().mapToInt(s -> s.tasks().size()).sum();
        log.atInfo().log(() -> "plan written to " + shown);
        return ToolResultData.ok("Plan written to " + shown + " (" + plan.stories().size() + " stories, " + tasks
                + " tasks)." + (existed ? " The previous version is in " + prevFile.getFileName() + "." : "")
                + (moved ? " The earlier draft " + earlier.getFileName() + " is removed." : "")
                + " The user reviews the plan now.");
    }

    /** The text that this tool wrote last to each file. A draft with other text was changed by the user. */
    private final java.util.Map<Path, String> written = new java.util.concurrent.ConcurrentHashMap<>();

    /** Deletes the earlier draft of this mode when the new plan went to another file. Returns true if it deleted. */
    private boolean removeMovedDraft(Path earlier, Path file, Path dir) {
        if (earlier == null || earlier.equals(file) || !earlier.startsWith(dir) || !Files.isRegularFile(earlier)) return false;
        try {
            var last = written.get(earlier);
            if (last == null || !last.equals(Files.readString(earlier))) return false;
            Files.delete(earlier);
            written.remove(earlier);
            return true;
        } catch (IOException e) {
            log.atDebug().log(() -> "earlier draft not removed: " + e.getMessage());
            return false;
        }
    }

    /** Makes the plan from the tool input. */
    static Plan toPlan(Input in, String created, String updated) {
        var stories = new ArrayList<Story>();
        for (var s : in.stories() == null ? List.<StoryInput>of() : in.stories()) {
            var tasks = new ArrayList<Task>();
            for (var t : s.tasks() == null ? List.<TaskInput>of() : s.tasks()) {
                tasks.add(new Task(oneLine(t.id()), oneLine(t.text())));
            }
            var criteria = new ArrayList<String>();
            for (var c : s.acceptanceCriteria() == null ? List.<String>of() : s.acceptanceCriteria()) {
                if (c == null || c.isBlank()) continue;
                criteria.add(c.strip().startsWith("- ") ? c.strip() : "- " + c.strip());
            }
            stories.add(new Story(oneLine(nullToEmpty(s.id())), oneLine(s.title()), oneLine(s.goal()),
                    clean(s.dependsOn()), clean(s.touches()), s.model() == null || s.model().isBlank() ? null : oneLine(s.model()),
                    nullToEmpty(s.context()).strip(), String.join("\n", criteria),
                    s.verification() == null || s.verification().isBlank() ? null : s.verification().strip(), tasks));
        }
        var front = new LinkedHashMap<String, String>();
        front.put("title", yaml(in.title()));
        front.put("status", "draft");
        front.put("goal", yaml(in.goal()));
        front.put("created", created);
        front.put("updated", updated);
        front.put("stories", String.valueOf(stories.size()));
        if (in.verification() != null && !in.verification().isBlank()) front.put("verification", yaml(in.verification()));
        return new Plan(front, List.of(), "# Plan: " + oneLine(in.title()), stories, "");
    }

    /** The file name part for a title. */
    static String slug(String title) {
        var s = nullToEmpty(title).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 60) s = s.substring(0, 60).replaceAll("-+$", "");
        return s.isEmpty() ? "plan" : s;
    }

    private static List<String> clean(List<String> list) {
        if (list == null) return List.of();
        return list.stream().filter(x -> x != null && !x.isBlank()).map(PlanSubmitTool::oneLine)
                .map(x -> x.startsWith("`") && x.endsWith("`") && x.length() > 1 ? x.substring(1, x.length() - 1) : x).toList();
    }

    /** A front matter value. It gets quotes when plain text would be read in another way. */
    static String yaml(String value) {
        var v = oneLine(value);
        var special = v.contains(": ") || v.contains(" #") || v.startsWith("#") || v.endsWith(":")
                || !v.isEmpty() && "\"'[]{}&*!|>%@`-?,".indexOf(v.charAt(0)) >= 0;
        return special ? "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : v;
    }

    private static String oneLine(String s) { return nullToEmpty(s).replaceAll("\\s+", " ").strip(); }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
