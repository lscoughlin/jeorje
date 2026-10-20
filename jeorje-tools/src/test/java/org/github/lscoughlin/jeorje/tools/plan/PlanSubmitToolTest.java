package org.github.lscoughlin.jeorje.tools.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.plan.PlanMode;
import org.github.lscoughlin.jeorje.core.plan.PlanParser;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class PlanSubmitToolTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir Path work;
    PlanMode mode;
    List<Path> submitted;
    ToolRegistry registry;
    ToolContext context;

    @BeforeEach
    void setUp() {
        mode = new PlanMode();
        submitted = new ArrayList<>();
        registry = ToolRegistry.builder().register(new PlanSubmitTool("plan", mode, submitted::add)).build();
        context = new ToolContext(work, new AgentId("root"), CancellationToken.none(), null,
                Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC));
    }

    static String story(String id, String dependsOn, String touches, String contextText) {
        return """
            {"id":"%s","title":"Title %s","goal":"Goal of %s.","dependsOn":%s,"touches":%s,
             "context":"%s","acceptanceCriteria":["Tests pass.","Class exists."],
             "tasks":[{"id":"%s.T1","text":"Write it."},{"id":"%s.T2","text":"Test it."}]}
            """.formatted(id, id, id, dependsOn, touches, contextText, id, id);
    }

    static String plan(String title, String... stories) {
        return """
            {"title":"%s","goal":"Reach the goal.","verification":"mvn verify","stories":[%s]}
            """.formatted(title, String.join(",", stories));
    }

    ToolResultData submit(String json) {
        return registry.execute(new ToolUse("1", "plan_submit", JSON.readTree(json)), context);
    }

    @Test
    void writesAValidPlanIntoThePlanDirectory() throws IOException {
        var result = submit(plan("Add rate limiting",
                story("S1", "[]", "[\"src/a/\"]", "The module uses Clock."),
                story("S2", "[\"S1\"]", "[\"src/a/\"]", "Use the bucket class.")));
        assertFalse(result.error(), result.output());
        var file = work.resolve("plan/add-rate-limiting.md");
        assertTrue(Files.exists(file));
        assertTrue(result.output().contains("plan/add-rate-limiting.md"));
        assertEquals(List.of(file), submitted);
        assertEquals(file, mode.submittedPlan().orElseThrow());
        var parsed = PlanParser.parse(file);
        assertEquals("Add rate limiting", parsed.title());
        assertEquals("draft", parsed.status());
        assertEquals("2026-10-18", parsed.front().get("created"));
        assertEquals("2", parsed.front().get("stories"));
        assertEquals(List.of("S1"), parsed.stories().get(1).dependsOn());
        assertEquals("- Tests pass.\n- Class exists.", parsed.stories().get(0).acceptance());
        assertEquals(2, parsed.stories().get(0).tasks().size());
        assertFalse(Files.exists(work.resolve("plan/add-rate-limiting.prev.md")));
    }

    @Test
    void replaceKeepsThePreviousVersion() throws IOException {
        submit(plan("Same plan", story("S1", "[]", "[\"a/\"]", "First version.")));
        var file = work.resolve("plan/same-plan.md");
        var first = Files.readString(file);
        var result = submit(plan("Same plan", story("S1", "[]", "[\"a/\"]", "Second version.")));
        assertFalse(result.error(), result.output());
        assertTrue(result.output().contains("same-plan.prev.md"));
        assertEquals(first, Files.readString(work.resolve("plan/same-plan.prev.md")));
        assertTrue(Files.readString(file).contains("Second version."));
        assertEquals("2026-10-18", PlanParser.parse(file).front().get("created"));
    }

    @Test
    void aCallWithNoNameReplacesTheDraftOfThisMode() throws IOException {
        submit(plan("First title", story("S1", "[]", "[\"a/\"]", "First version.")));
        var file = work.resolve("plan/first-title.md");
        var result = submit(plan("Another title", story("S1", "[]", "[\"a/\"]", "Second version.")));
        assertFalse(result.error(), result.output());
        assertTrue(Files.readString(file).contains("Second version."));
        assertEquals(List.of("first-title.md", "first-title.prev.md"), listing());
        assertEquals("Another title", PlanParser.parse(file).title());
    }

    @Test
    void aNewNameMovesTheDraftAndRemovesTheOldFile() throws IOException {
        submit(plan("First title", story("S1", "[]", "[\"a/\"]", "First version.")));
        var result = submit(plan("First title", story("S1", "[]", "[\"a/\"]", "Moved.")).replace("{\"title\"",
                "{\"name\":\"better-name\",\"title\""));
        assertFalse(result.error(), result.output());
        assertEquals(List.of("better-name.md"), listing());
        assertTrue(result.output().contains("first-title.md is removed"), result.output());
        assertEquals(work.resolve("plan/better-name.md"), mode.submittedPlan().orElseThrow());
    }

    @Test
    void aDraftThatTheUserChangedIsNotRemoved() throws IOException {
        submit(plan("First title", story("S1", "[]", "[\"a/\"]", "First version.")));
        var old = work.resolve("plan/first-title.md");
        Files.writeString(old, Files.readString(old) + "\nMy note.\n");
        var result = submit(plan("First title", story("S1", "[]", "[\"a/\"]", "Moved.")).replace("{\"title\"",
                "{\"name\":\"other\",\"title\""));
        assertFalse(result.error(), result.output());
        assertEquals(List.of("first-title.md", "other.md"), listing());
    }

    @Test
    void frontMatterWithSpecialTextReadsBackTheSame() throws IOException {
        var title = "Fix: the \"parser\" # 1 [a]";
        var result = submit(plan(title.replace("\"", "\\\""), story("S1", "[]", "[\"a/\"]", "Text.")));
        assertFalse(result.error(), result.output());
        var parsed = PlanParser.parse(work.resolve("plan/" + PlanSubmitTool.slug(title) + ".md"));
        assertEquals(title, parsed.title());
        for (var value : List.of("plain", "a: b", "x #y", "#lead", "- dash", "ends:", "say \"hi\"", "back\\slash", "it's")) {
            var text = "---\ntitle: " + PlanSubmitTool.yaml(value) + "\n---\n";
            assertEquals(value, PlanParser.parse(text).title(), text);
        }
    }

    private List<String> listing() throws IOException {
        try (var s = Files.list(work.resolve("plan"))) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void failedChecksReturnAnErrorAndWriteNothing() {
        var result = submit(plan("Bad plan",
                story("S1", "[]", "[\"a/\"]", "As discussed, use the helper."),
                story("S2", "[]", "[\"a/b\"]", "Fine.")));
        assertTrue(result.error());
        assertTrue(result.output().contains("Story S1 [self-contained]"), result.output());
        assertTrue(result.output().contains("touches-overlap"), result.output());
        assertFalse(Files.exists(work.resolve("plan")));
        assertTrue(submitted.isEmpty());
    }

    @Test
    void limitErrorsAreReported() {
        var stories = new ArrayList<String>();
        for (var i = 1; i <= 51; i++) stories.add(story("S" + i, "[]", "[\"d" + i + "/\"]", "Context."));
        var result = submit(plan("Big", stories.toArray(String[]::new)));
        assertTrue(result.error());
        assertTrue(result.output().contains("51 stories"), result.output());
    }

    @Test
    void nameCannotEscapeThePlanDirectory() {
        var json = plan("T", story("S1", "[]", "[]", "Context.")).replace("{\"title\"", "{\"name\":\"../../evil\",\"title\"");
        var result = submit(json);
        assertFalse(result.error(), result.output());
        assertTrue(Files.exists(work.resolve("plan/evil.md")));
        assertFalse(Files.exists(work.resolve("evil.md")));
    }

    @Test
    void textWithHeadingLinesIsRefused() {
        var result = submit(plan("Heading", story("S1", "[]", "[]", "Line one.\\n## Story S9: Fake\\nMore.")));
        assertTrue(result.error());
        assertTrue(result.output().contains("format"), result.output());
        assertFalse(Files.exists(work.resolve("plan")));
    }

    @Test
    void usesTheConfiguredPlanDirectory() {
        registry = ToolRegistry.builder().register(new PlanSubmitTool("docs/plans", null, null)).build();
        var result = submit(plan("Other dir", story("S1", "[]", "[]", "Context.")));
        assertFalse(result.error(), result.output());
        assertTrue(Files.exists(work.resolve("docs/plans/other-dir.md")));
    }

    @Test
    void schemaListsRequiredFields() {
        var schema = registry.find("plan_submit").orElseThrow().definition().inputSchema();
        assertTrue(schema.path("required").toString().contains("stories"));
        assertEquals("array", schema.path("properties").path("stories").path("type").asString());
    }

    @Test
    void slugs() {
        assertEquals("add-rate-limiting", PlanSubmitTool.slug("Add rate limiting!"));
        assertEquals("plan", PlanSubmitTool.slug("???"));
        assertEquals("evil", PlanSubmitTool.slug("../../evil"));
        assertTrue(PlanSubmitTool.slug("x".repeat(200)).length() <= 60);
    }
}
