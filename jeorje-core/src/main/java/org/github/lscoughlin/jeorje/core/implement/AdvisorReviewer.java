package org.github.lscoughlin.jeorje.core.implement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.port.LlmClient;

/**
 * A {@link Reviewer} that asks the advisor model. The model gets the story, the draft summary, the changed files,
 * and the diff. It answers {@code approve} or a list of problems.
 */
public final class AdvisorReviewer implements Reviewer {
    static final String SYSTEM = """
        You review the result of one story of a plan. Check the result against the acceptance criteria of the story.
        You have no tools. If every acceptance criterion is met, answer with the single word: approve
        Otherwise answer with one problem on each line. Start each line with "- ". Name the criterion that fails.""";
    private static final int MAX_DIFF_CHARS = 60_000;
    private static final long TIMEOUT_SECONDS = 180;

    private final LlmClient client;

    public AdvisorReviewer(LlmClient client) { this.client = client; }

    @Override
    public Verdict review(Request request) {
        var body = new StringBuilder("Story:\n").append(request.storyText())
            .append("\n\nDraft summary of the worker:\n").append(request.draftSummary())
            .append("\n\nChanged files:\n").append(String.join("\n", request.changedFiles()))
            .append("\n\nDiff:\n");
        var diff = request.diff() == null ? "" : request.diff();
        body.append(diff.length() > MAX_DIFF_CHARS ? diff.substring(0, MAX_DIFF_CHARS) + "\n[diff cut]" : diff);
        var llm = new LlmRequest(SYSTEM, List.of(Message.user(body.toString())), List.of(), 2048);
        return parse(collect(client.stream(llm, CancellationToken.none())));
    }

    /** Reads the answer of the model. */
    static Verdict parse(String answer) {
        var lines = answer.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (lines.isEmpty()) return Verdict.reject(List.of("The advisor gave no answer."));
        var first = lines.get(0).toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (lines.size() == 1 && (first.equals("approve") || first.equals("approved"))) return Verdict.approve();
        var problems = new ArrayList<String>();
        for (var line : lines) problems.add(line.startsWith("- ") ? line.substring(2).strip() : line);
        return Verdict.reject(problems);
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
            return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("The advisor call failed: "
                + (e.getCause() == null ? e : e.getCause()).getMessage(), e);
        }
    }
}
