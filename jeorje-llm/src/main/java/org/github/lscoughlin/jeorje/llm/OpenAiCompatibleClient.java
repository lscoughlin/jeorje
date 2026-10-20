package org.github.lscoughlin.jeorje.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.random.RandomGenerator;
import org.github.lscoughlin.jeorje.core.model.CancellationToken;
import org.github.lscoughlin.jeorje.core.model.LlmEvent;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * An {@link LlmClient} for OpenAI-compatible endpoints, such as OpenRouter. It sends
 * {@code POST {baseUrl}/chat/completions} with {@code stream: true} and reads the server-sent events.
 */
public final class OpenAiCompatibleClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    /** Waits for a delay. Tests replace it. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final OpenAiSettings settings;
    private final HttpClient http;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final WireMapper wire = new WireMapper(mapper);
    private final Sleeper sleeper;
    private final RandomGenerator random;

    public OpenAiCompatibleClient(OpenAiSettings settings) {
        this(settings, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
    }

    public OpenAiCompatibleClient(OpenAiSettings settings, HttpClient http) {
        this(settings, http, d -> Thread.sleep(d), RandomGenerator.getDefault());
    }

    OpenAiCompatibleClient(OpenAiSettings settings, HttpClient http, Sleeper sleeper, RandomGenerator random) {
        this.settings = settings;
        this.http = http;
        this.sleeper = sleeper;
        this.random = random;
    }

    @Override
    public Flow.Publisher<LlmEvent> stream(LlmRequest request, CancellationToken cancel) {
        return subscriber -> {
            var publisher = new SubmissionPublisher<LlmEvent>();
            publisher.subscribe(subscriber);
            var run = new Run(request, cancel, publisher);
            Thread.ofVirtual().name("llm-stream").start(run::execute);
        };
    }

    /** The state of one streamed call. */
    private final class Run {
        private final LlmRequest request;
        private final CancellationToken cancel;
        private final SubmissionPublisher<LlmEvent> out;
        private volatile Thread worker;
        private volatile InputStream body;
        private boolean emitted;

        Run(LlmRequest request, CancellationToken cancel, SubmissionPublisher<LlmEvent> out) {
            this.request = request;
            this.cancel = cancel;
            this.out = out;
        }

        void execute() {
            worker = Thread.currentThread();
            cancel.onCancel(this::onCancel);
            try {
                call();
                out.close();
            } catch (Exception e) {
                if (cancel.isCancelled()) {
                    out.close();
                } else {
                    log.atDebug().log(() -> "Stream failed: " + e.getMessage());
                    out.closeExceptionally(e);
                }
            }
        }

        private void onCancel() {
            var stream = body;
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) { /* The stream is closed anyway. */ }
            }
            var thread = worker;
            if (thread != null && thread != Thread.currentThread()) thread.interrupt();
        }

        private void call() throws Exception {
            var json = mapper.writeValueAsString(wire.request(settings.model(), request));
            var http1 = buildRequest(json);
            for (var attempt = 1; ; attempt++) {
                if (cancel.isCancelled()) return;
                Duration retryAfter = null;
                LlmException failure;
                try {
                    var response = http.send(http1, HttpResponse.BodyHandlers.ofInputStream());
                    var status = response.statusCode();
                    if (status == 200) {
                        body = response.body();
                        if (cancel.isCancelled()) { onCancel(); return; }
                        readStream(body);
                        return;
                    }
                    var text = readError(response.body());
                    var retryable = status == 429 || status >= 500;
                    failure = new LlmException("HTTP " + status + " from model endpoint: " + text, status, retryable);
                    if (retryable) retryAfter = parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null));
                } catch (LlmException e) {
                    failure = e;
                } catch (IOException e) {
                    if (cancel.isCancelled()) return;
                    failure = new LlmException("Network error: " + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage()), 0, !emitted);
                    failure.initCause(e);
                }
                if (cancel.isCancelled()) return;
                if (!failure.retryable() || emitted || attempt >= settings.maxAttempts()) throw failure;
                var delay = retryAfter != null ? retryAfter : backoff(attempt);
                var attemptNumber = attempt;
                log.atDebug().log(() -> "Retry " + attemptNumber + " in " + delay.toMillis() + " ms after: "
                        + "retryable error");
                sleeper.sleep(delay);
            }
        }


        private HttpRequest buildRequest(String json) {
            var builder = HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (settings.apiKey() != null && !settings.apiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + settings.apiKey());
            }
            settings.headers().forEach(builder::header);
            return builder.build();
        }

        private void readStream(InputStream in) throws IOException {
            var assembler = new StreamAssembler(mapper);
            var data = new StringBuilder();
            var done = false;
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while (!done && (line = reader.readLine()) != null) {
                    if (cancel.isCancelled()) return;
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            done = dispatch(assembler, data.toString());
                            data.setLength(0);
                        }
                    } else if (line.startsWith("data:")) {
                        var value = line.substring(5);
                        if (value.startsWith(" ")) value = value.substring(1);
                        if (data.length() > 0) data.append('\n');
                        data.append(value);
                    }
                    // Comment lines (start with ":") and other fields are ignored.
                }
                if (!done && data.length() > 0) done = dispatch(assembler, data.toString());
            } catch (IOException e) {
                if (cancel.isCancelled()) return;
                throw e;
            }
            if (cancel.isCancelled()) return;
            if (!done && !assembler.finished()) {
                throw new LlmException("Stream ended before the model finished", 0, false);
            }
            for (var event : assembler.flush()) emit(event);
            emit(assembler.finish());
        }

        /** Returns true for the end marker. */
        private boolean dispatch(StreamAssembler assembler, String data) throws LlmException {
            if (data.strip().equals("[DONE]")) return true;
            for (var event : assembler.accept(data)) emit(event);
            return false;
        }

        private void emit(LlmEvent event) {
            if (cancel.isCancelled()) return;
            emitted = true;
            out.submit(event);
        }

        private String readError(InputStream in) {
            try (in) {
                var bytes = in.readNBytes(2000);
                return new String(bytes, StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                return "";
            }
        }
    }

    Duration backoff(int attempt) {
        var base = settings.baseDelay().toMillis();
        var cap = settings.maxDelay().toMillis();
        var exp = Math.min(cap, base * (1L << Math.min(attempt - 1, 20)));
        var half = exp / 2;
        return Duration.ofMillis(half + (half <= 0 ? 0 : random.nextLong(half + 1)));
    }

    static Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) return null;
        var text = value.strip();
        try {
            var seconds = Double.parseDouble(text);
            return seconds < 0 ? null : Duration.ofMillis((long) (seconds * 1000));
        } catch (NumberFormatException e) {
            try {
                var when = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME);
                var wait = Duration.between(ZonedDateTime.now(when.getZone()), when);
                return wait.isNegative() ? Duration.ZERO : wait;
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }
}
