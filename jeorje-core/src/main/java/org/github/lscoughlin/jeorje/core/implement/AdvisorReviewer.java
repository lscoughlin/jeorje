package org.github.lscoughlin.jeorje.core.implement;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.jeorje.core.agent.AgentSettings;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.Message;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Reviewer} that asks the advisor model. The model gets the story, the draft summary, the changed files,
 * and the diff. It answers {@code approve} or a list of problems.
 *
 * <p>A blank answer and a failed call are not a review. The reviewer asks again after 2 seconds and after 5
 * seconds. If it still has no answer, it returns {@link Verdict#unavailable}.
 *
 * <p>A blank answer that stopped at the output limit is the model that ran out of tokens before it answered. The
 * reviewer then asks the remaining attempts with the larger retry output limit.
 */
public final class AdvisorReviewer implements Reviewer {
    static final String SYSTEM = """
        You review the result of one story of a plan. Check the result against the acceptance criteria of the story.
        You have no tools. If every acceptance criterion is met, answer with the single word: approve
        Otherwise answer with one problem on each line. Start each line with "- ". Name the criterion that fails.""";
    private static final int MAX_DIFF_CHARS = 60_000;
    private static final long TIMEOUT_SECONDS = 180;
    static final String NO_ANSWER = "The review gave no answer.";
    /** The output limit of the first review call. */
    static final int DEFAULT_REVIEW_OUTPUT_TOKENS = 2048;
    /** The output limit of the review calls after a blank answer that stopped at the output limit. */
    static final int DEFAULT_REVIEW_RETRY_OUTPUT_TOKENS = 8192;
    /** The waits before the second and the third call. */
    static final List<Duration> RETRY_WAITS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(5));
    private static final Logger log = LoggerFactory.getLogger(AdvisorReviewer.class);

    private final LlmClient client;
    private final AgentSettings.Sleeper sleeper;
    private final int reviewOutputTokens;
    private final int reviewRetryOutputTokens;

    public AdvisorReviewer(LlmClient client) { this(client, AgentSettings.Sleeper.system()); }

    /** @param sleeper waits between two calls. Tests give a sleeper that does not wait. */
    public AdvisorReviewer(LlmClient client, AgentSettings.Sleeper sleeper) {
        this(client, sleeper, DEFAULT_REVIEW_OUTPUT_TOKENS, DEFAULT_REVIEW_RETRY_OUTPUT_TOKENS);
    }

    /**
     * @param sleeper waits between two calls. Tests give a sleeper that does not wait.
     * @param reviewOutputTokens the output limit of the first call
     * @param reviewRetryOutputTokens the output limit of the later calls after a blank answer that stopped at the
     *     output limit
     */
    public AdvisorReviewer(LlmClient client, AgentSettings.Sleeper sleeper, int reviewOutputTokens,
                           int reviewRetryOutputTokens) {
        this.client = client;
        this.sleeper = sleeper;
        this.reviewOutputTokens = reviewOutputTokens;
        this.reviewRetryOutputTokens = reviewRetryOutputTokens;
    }

    @Override
    public Verdict review(Request request) {
        var body = new StringBuilder("Story:\n").append(request.storyText())
            .append("\n\nDraft summary of the worker:\n").append(request.draftSummary())
            .append("\n\nChanged files:\n").append(String.join("\n", request.changedFiles()))
            .append("\n\nDiff:\n");
        var diff = request.diff() == null ? "" : request.diff();
        body.append(diff.length() > MAX_DIFF_CHARS ? diff.substring(0, MAX_DIFF_CHARS) + "\n[diff cut]" : diff);
        var messages = List.of(Message.user(body.toString()));
        var limit = reviewOutputTokens;
        var reason = NO_ANSWER;
        for (var attempt = 0; ; attempt++) {
            var limitReached = false;
            try {
                var answer = collect(client.stream(new LlmRequest(SYSTEM, messages, List.of(), limit),
                    CancellationToken.none()));
                var verdict = parse(answer.text());
                if (!verdict.unavailable()) return verdict;
                reason = verdict.reason();
                limitReached = answer.outputLimitReached();
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) return Verdict.unavailable("The review was interrupted.");
                reason = "The review call failed: " + e.getMessage();
            }
            if (attempt >= RETRY_WAITS.size()) return Verdict.unavailable(reason);
            if (limitReached && limit != reviewRetryOutputTokens) {
                var before = limit;
                limit = reviewRetryOutputTokens;
                var after = limit;
                log.atInfo().log(() -> "The review used its output limit. Jeorje raises the limit from "
                    + before + " to " + after + " tokens.");
            }
            var wait = RETRY_WAITS.get(attempt);
            var why = reason;
            log.atInfo().log(() -> "The review has no result (" + why + "). Jeorje asks again after " + wait);
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Verdict.unavailable("The review was interrupted.");
            }
        }
    }

    /** Reads the answer of the model. */
    static Verdict parse(String answer) {
        var lines = answer.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (lines.isEmpty()) return Verdict.unavailable(NO_ANSWER);
        var first = lines.get(0).toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (lines.size() == 1 && (first.equals("approve") || first.equals("approved"))) return Verdict.approve();
        var problems = new ArrayList<String>();
        for (var line : lines) problems.add(line.startsWith("- ") ? line.substring(2).strip() : line);
        return Verdict.reject(problems);
    }

    /** The text of one answer and its stop. The stop is null when the stream gave no stop. */
    record Answer(String text, LlmEvent.MessageStop stop) {
        /** True when the model used its whole output limit before it answered. */
        boolean outputLimitReached() { return stop != null && stop.outputLimitReached(); }
    }

    private static Answer collect(Flow.Publisher<LlmEvent> publisher) {
        var result = new CompletableFuture<Answer>();
        var text = new StringBuilder();
        var stop = new LlmEvent.MessageStop[1];
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }

            @Override public void onNext(LlmEvent e) {
                if (e instanceof LlmEvent.TextDelta d) text.append(d.text());
                else if (e instanceof LlmEvent.MessageStop s) stop[0] = s;
            }

            @Override public void onError(Throwable t) { result.completeExceptionally(t); }

            @Override public void onComplete() { result.complete(new Answer(text.toString(), stop[0])); }
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
