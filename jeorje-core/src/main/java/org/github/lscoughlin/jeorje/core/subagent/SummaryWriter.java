package org.github.lscoughlin.jeorje.core.subagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the completion summary. A model call with no tools makes the text. The writer checks the text, asks one
 * time again if the check fails, and then builds the fallback summary.
 */
final class SummaryWriter {
    private static final Logger log = LoggerFactory.getLogger(SummaryWriter.class);
    private static final int MAX_OUTPUT_TOKENS = 1024;
    private static final int MAX_HISTORY_CHARS = 60_000;
    private static final long CALL_TIMEOUT_SECONDS = 120;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_./\\-]{3,}");
    private static final Set<String> STOP = Set.of("this", "that", "with", "from", "have", "will", "your", "tool",
        "the", "and", "for", "are", "not", "was", "you", "can", "task", "user", "assistant", "result", "call",
        "true", "false", "null", "path", "output", "input");

    static final String SYSTEM = """
        You write the completion summary of a subagent. Use the history that the user message gives.
        Do not call tools. Write plain text with exactly these labeled parts, each part on its own line:
        Outcome: completed, failed, or cancelled. Add the reason when it is not completed.
        Done: what the subagent did, in a few short sentences.
        Files: the files that it created, changed, or deleted.
        Branch: the branch and the worktree, the source branch, the merge result and commit, and the conflicts that it resolved. Write none if it used no worktree.
        Decisions: choices that it made, with the reason.
        Verification: the commands that it ran and the results.
        Open issues: work that it did not finish, and problems that it found.
        Keywords: 3 to 12 exact terms from its work (identifiers, paths, error names), separated by commas. Each term must appear in the history.
        The whole summary has at most 300 words. Write short sentences.""";

    /** The facts for one summary. */
    record Request(String name, String outcome, String reason, String branch, HistoryText history) {}

    private final LlmClient client;

    /** @param client the model for the text, or null to build the fallback summary at once */
    SummaryWriter(LlmClient client) {
        this.client = client;
    }

    String write(Request request) {
        if (client != null) {
            var feedback = List.<String>of();
            for (var attempt = 1; attempt <= 2; attempt++) {
                try {
                    var text = SummaryCheck.clean(call(request, feedback));
                    var problems = SummaryCheck.validate(text, request.outcome(), request.history());
                    if (problems.isEmpty()) return text;
                    feedback = problems;
                    var number = attempt;
                    log.atDebug().log(() -> "summary of " + request.name() + " failed the check " + number + ": " + problems);
                } catch (RuntimeException e) {
                    feedback = List.of("The model call failed: " + e.getMessage());
                    log.atDebug().log(() -> "summary call for " + request.name() + " failed: " + e.getMessage());
                }
            }
        }
        return fallback(request);
    }

    private String call(Request request, List<String> feedback) {
        var body = new StringBuilder();
        body.append("Agent name: ").append(request.name()).append('\n');
        body.append("Outcome to report: ").append(request.outcome());
        if (request.reason() != null) body.append(" (").append(request.reason()).append(')');
        body.append('\n');
        if (request.branch() != null) body.append("Branch facts: ").append(request.branch()).append('\n');
        body.append("\nHistory of the subagent:\n").append(request.history().capped(MAX_HISTORY_CHARS));
        if (!feedback.isEmpty()) {
            body.append("\n\nYour last answer was not valid. Fix these problems:\n");
            feedback.forEach(p -> body.append("- ").append(p).append('\n'));
        }
        var llm = new LlmRequest(SYSTEM, List.of(Message.user(body.toString())), List.of(), MAX_OUTPUT_TOKENS);
        return collect(client.stream(llm, CancellationToken.none()));
    }

    private static String collect(Flow.Publisher<LlmEvent> publisher) {
        var result = new CompletableFuture<String>();
        var text = new StringBuilder();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }

            @Override public void onNext(LlmEvent e) {
                if (e instanceof LlmEvent.TextDelta d) text.append(d.text());
            }

            @Override public void onError(Throwable t) { result.completeExceptionally(t); }

            @Override public void onComplete() { result.complete(text.toString()); }
        });
        try {
            return result.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("summary call failed: " + (e.getCause() == null ? e : e.getCause()).getMessage(), e);
        }
    }

    /** The summary from the last assistant text, the changed files, and the reason. It always passes the check. */
    static String fallback(Request request) {
        var history = request.history();
        var parts = new LinkedHashMap<String, String>();
        var outcome = request.outcome() + (request.reason() == null ? "" : ": " + request.reason());
        parts.put("Outcome", limit(outcome, 40));
        var last = history.lastAssistantText();
        parts.put("Done", last.isEmpty() ? "No final text. The runtime built this summary." : limit(oneLine(last), 90));
        var files = history.changedFiles();
        parts.put("Files", files.isEmpty() ? "none recorded" : limit(String.join(", ", files), 40));
        parts.put("Branch", request.branch() == null ? "none" : limit(oneLine(request.branch()), 40));
        parts.put("Decisions", "none recorded");
        parts.put("Verification", "not recorded");
        parts.put("Open issues", request.reason() == null ? "none recorded" : limit(request.reason(), 40));
        parts.put("Keywords", String.join(", ", fallbackKeywords(history, files)));
        var sb = new StringBuilder();
        parts.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        return sb.toString().strip();
    }

    private static String oneLine(String text) { return text.replaceAll("\\s+", " ").strip(); }

    private static String limit(String text, int words) {
        var split = text.strip().split("\\s+");
        if (split.length <= words) return text.strip();
        return String.join(" ", java.util.Arrays.copyOf(split, words)) + " ...";
    }

    private static List<String> fallbackKeywords(HistoryText history, List<String> files) {
        var out = new LinkedHashSet<String>();
        for (var f : files) {
            if (out.size() >= 4) break;
            if (history.contains(f) && !f.contains(",") && !f.contains(" ")) out.add(f);
        }
        var counts = new LinkedHashMap<String, Integer>();
        var m = TOKEN.matcher(history.full());
        while (m.find()) {
            var t = m.group();
            if (STOP.contains(t.toLowerCase(Locale.ROOT))) continue;
            counts.merge(t, 1, Integer::sum);
        }
        var ranked = new ArrayList<>(counts.entrySet());
        ranked.sort((a, b) -> {
            var byCount = Integer.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : Integer.compare(b.getKey().length(), a.getKey().length());
        });
        for (var e : ranked) {
            if (out.size() >= 8) break;
            out.add(e.getKey());
        }
        var padding = List.of("subagent", "task", "result");
        for (var p : padding) if (out.size() < SummaryCheck.MIN_KEYWORDS) out.add(p);
        return new ArrayList<>(out);
    }

    static Map<String, String> parse(String summary) { return SummaryCheck.parts(summary); }
}
