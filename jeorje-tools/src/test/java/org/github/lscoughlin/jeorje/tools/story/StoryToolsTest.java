package org.github.lscoughlin.jeorje.tools.story;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.core.implement.AdvisorReviewer;
import org.github.lscoughlin.jeorje.core.implement.Reviewer;
import org.github.lscoughlin.jeorje.core.implement.StoryHost;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.testkit.InMemoryContextStore;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient;
import org.github.lscoughlin.jeorje.testkit.ScriptedLlmClient.Response;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StoryToolsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    final List<String> calls = new ArrayList<>();
    final AgentId worker = new AgentId("sub-7");
    final ToolContext context = new ToolContext(Path.of("."), worker, CancellationToken.none(),
        new InMemoryContextStore(), Clock.systemUTC());

    final StoryHost host = new StoryHost() {
        @Override public Reply taskDone(AgentId w, String taskId, String note) {
            calls.add("task_done " + w + " " + taskId + " " + note);
            return taskId.equals("S1.T9") ? Reply.error("no such task") : Reply.ok("done " + taskId);
        }

        @Override public Reply storyDone(AgentId w, String summary, List<String> files) {
            calls.add("story_done " + w + " " + summary + " " + files);
            return Reply.ok("recorded");
        }

        @Override public Reply storyBlocked(AgentId w, String reason) {
            calls.add("story_blocked " + w + " " + reason);
            return Reply.ok("blocked");
        }

        @Override public String storyList() { return "S1 | One | running"; }

        @Override public Optional<String> storyText(String id) {
            return id.equals("S2") ? Optional.of("## Story S2: Two") : Optional.empty();
        }
    };

    private Tool tool(String name) {
        return StoryTools.storyTools(host).stream().filter(t -> t.definition().name().equals(name)).findFirst().orElseThrow();
    }

    private tools.jackson.databind.JsonNode json(String text) throws Exception { return JSON.readTree(text); }

    @Test
    void theStoryToolsCallTheHostWithTheWorker() throws Exception {
        assertEquals("done S1.T1", tool("task_done").execute(json("{\"taskId\":\"S1.T1\",\"note\":\"n\"}"), context).output());
        assertTrue(tool("task_done").execute(json("{\"taskId\":\"S1.T9\"}"), context).error());
        assertEquals("recorded", tool("story_done").execute(
            json("{\"summary\":\"Outcome: completed\",\"filesChanged\":[\"a\",\"b\"]}"), context).output());
        assertEquals("blocked", tool("story_blocked").execute(json("{\"reason\":\"no API\"}"), context).output());
        assertEquals(List.of("task_done sub-7 S1.T1 n", "task_done sub-7 S1.T9 null",
            "story_done sub-7 Outcome: completed [a, b]", "story_blocked sub-7 no API"), calls);
    }

    @Test
    void storyReadListsOrReturnsOneStory() throws Exception {
        var read = tool("story_read");
        assertTrue(read.readOnly());
        assertEquals("S1 | One | running", read.execute(json("{}"), context).output());
        assertEquals("## Story S2: Two", read.execute(json("{\"storyId\":\"S2\"}"), context).output());
        var missing = read.execute(json("{\"storyId\":\"S9\"}"), context);
        assertTrue(missing.error());
        assertTrue(missing.output().contains("no story S9"));
    }

    @Test
    void theToolSetHasTheFourStoryTools() throws Exception {
        for (var t : StoryTools.storyTools(host)) {
            assertTrue(t.definition().inputSchema().toString().contains("properties"), t.definition().name());
        }
        assertTrue(StoryTools.storyTools(host).get(0).definition().inputSchema().toString().contains("taskId"));
        assertEquals(List.of("task_done", "story_done", "story_blocked", "story_read"),
            StoryTools.storyTools(host).stream().map(t -> t.definition().name()).toList());
    }

    @Test
    void theAdvisorReviewerSendsTheStoryAndTheDiff() {
        var client = new ScriptedLlmClient(Response.text("approve"), Response.text("- The refill test is missing."));
        var reviewer = new AdvisorReviewer(client);
        var request = new Reviewer.Request("S1", "## Story S1: One", "DRAFT summary", List.of("a.txt"), "+added line");
        assertTrue(reviewer.review(request).approved());
        var rejected = reviewer.review(request);
        assertFalse(rejected.approved());
        assertEquals(List.of("The refill test is missing."), rejected.problems());
        var sent = client.requests().get(0);
        assertTrue(sent.tools().isEmpty());
        var body = StoryTestSupport.text(sent.messages().get(0));
        assertTrue(body.contains("## Story S1: One") && body.contains("DRAFT summary") && body.contains("a.txt")
            && body.contains("+added line"), body);
    }
}
