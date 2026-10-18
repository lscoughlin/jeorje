package org.github.lscoughlin.jeorje.tools.registry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the tools by name. It gives the model the tool list, and it runs tool calls with input
 * validation, a timeout, binary refusal, and an output cap.
 */
public final class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** The default timeout of a tool: 120 s. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private static final java.util.concurrent.ExecutorService EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, Tool> tools;
    private final Map<String, Duration> timeouts;
    private final Duration defaultTimeout;
    private final OutputLimiter limiter;

    private ToolRegistry(Map<String, Tool> tools, Map<String, Duration> timeouts, Duration defaultTimeout,
                         OutputLimiter limiter) {
        this.tools = Map.copyOf(tools);
        this.timeouts = Map.copyOf(timeouts);
        this.defaultTimeout = defaultTimeout;
        this.limiter = limiter;
        this.order = List.copyOf(tools.keySet());
    }

    private final List<String> order;

    public static Builder builder() {
        return new Builder();
    }

    /** The tool with this name. */
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** The names of the tools, in registration order. */
    public List<String> names() {
        return order;
    }

    public int size() {
        return tools.size();
    }

    /** The definitions that Jeorje gives to the model, in registration order. */
    public List<ToolDefinition> definitions() {
        return order.stream().map(name -> tools.get(name).definition()).toList();
    }

    /** A registry with only the named tools. Unknown names are ignored. */
    public ToolRegistry only(Collection<String> names) {
        var kept = new LinkedHashMap<String, Tool>();
        order.stream().filter(names::contains).forEach(n -> kept.put(n, tools.get(n)));
        return new ToolRegistry(kept, timeouts, defaultTimeout, limiter);
    }

    /** A registry with all tools except the named ones. */
    public ToolRegistry without(Collection<String> names) {
        var kept = new LinkedHashMap<String, Tool>();
        order.stream().filter(n -> !names.contains(n)).forEach(n -> kept.put(n, tools.get(n)));
        return new ToolRegistry(kept, timeouts, defaultTimeout, limiter);
    }

    /** A registry with only the read-only tools. */
    public ToolRegistry readOnlyTools() {
        var kept = new LinkedHashMap<String, Tool>();
        order.stream().filter(n -> tools.get(n).readOnly()).forEach(n -> kept.put(n, tools.get(n)));
        return new ToolRegistry(kept, timeouts, defaultTimeout, limiter);
    }

    /**
     * A registry in which one string property of one tool has a fixed set of values. The
     * schema gets an {@code enum} and validation refuses other values.
     */
    public ToolRegistry constrain(String tool, String property, List<String> values) {
        var original = tools.get(tool);
        if (original == null) throw new IllegalArgumentException("unknown tool " + tool);
        var copy = new LinkedHashMap<String, Tool>();
        order.forEach(n -> copy.put(n, n.equals(tool) ? new ConstrainedTool(original, property, values) : tools.get(n)));
        return new ToolRegistry(copy, timeouts, defaultTimeout, limiter);
    }

    /** The timeout for a tool. */
    public Duration timeoutFor(String name) {
        var set = timeouts.get(name);
        if (set != null) return set;
        if (tools.get(name) instanceof TimedTool timed && timed.timeout() != null) return timed.timeout();
        return defaultTimeout;
    }

    public int maxOutputChars() {
        return limiter.maxChars();
    }

    /**
     * Runs one call. The result is never null and this method never throws. Input errors, unknown
     * tools, timeouts, cancellation, and tool failures become error results.
     */
    public ToolResultData execute(ToolUse use, ToolContext context) {
        var tool = tools.get(use.tool());
        if (tool == null) {
            return ToolResultData.error("Unknown tool '" + use.tool() + "'. Available tools: " + String.join(", ", order));
        }
        var input = use.input();
        var errors = InputValidator.validate(tool.definition().inputSchema(), input);
        if (!errors.isEmpty()) {
            return ToolResultData.error("Invalid input for " + use.tool() + ": " + String.join("; ", errors));
        }
        return limiter.apply(runWithTimeout(tool, input, context));
    }

    private ToolResultData runWithTimeout(Tool tool, tools.jackson.databind.JsonNode input, ToolContext context) {
        var name = tool.definition().name();
        var timeout = timeoutFor(name);
        {
            Future<ToolResultData> future = EXECUTOR.submit(() -> tool.execute(input, context));
            context.cancel().onCancel(() -> future.cancel(true));
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.atWarn().log(() -> "tool " + name + " timed out after " + timeout.toSeconds() + " s");
                return ToolResultData.error("Tool " + name + " timed out after " + timeout.toSeconds()
                        + " s and was interrupted.");
            } catch (CancellationException e) {
                return ToolResultData.error("Tool " + name + " was cancelled.");
            } catch (ExecutionException e) {
                var cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof CancellationException) {
                    return ToolResultData.error("Tool " + name + " was cancelled.");
                }
                log.atDebug().log(() -> "tool " + name + " failed: " + cause);
                return ToolResultData.error("Tool " + name + " failed: " + describe(cause));
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                return ToolResultData.error("Tool " + name + " was interrupted.");
            }
        }
    }

    private static String describe(Throwable t) {
        var message = t.getMessage();
        return message == null ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + message;
    }

    /**
     * Runs the calls of one assistant message. If every call is read-only, they run at the same
     * time. Otherwise they run one after the other, in order. The results have the order of the calls.
     */
    public List<ToolResultData> executeAll(List<ToolUse> uses, ToolContext context) {
        if (uses.size() > 1 && canRunTogether(uses)) {
            {
                var futures = uses.stream().map(use -> EXECUTOR.submit(() -> execute(use, context))).toList();
                var results = new ArrayList<ToolResultData>();
                for (var future : futures) {
                    try {
                        results.add(future.get());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        results.add(ToolResultData.error("Interrupted."));
                    } catch (ExecutionException e) {
                        results.add(ToolResultData.error("Tool failed: " + describe(e.getCause())));
                    }
                }
                return results;
            }
        }
        return uses.stream().map(use -> execute(use, context)).toList();
    }

    /** True if all calls are for known read-only tools. */
    public boolean canRunTogether(List<ToolUse> uses) {
        return uses.stream().allMatch(u -> tools.get(u.tool()) != null && tools.get(u.tool()).readOnly());
    }

    /** Builds a registry. */
    public static final class Builder {
        private final Map<String, Tool> tools = new LinkedHashMap<>();
        private final Map<String, Duration> timeouts = new LinkedHashMap<>();
        private Duration defaultTimeout = DEFAULT_TIMEOUT;
        private int maxChars = OutputLimiter.DEFAULT_MAX_CHARS;

        /** Adds a tool. A second tool with the same name is an error. */
        public Builder register(Tool tool) {
            var name = tool.definition().name();
            if (tools.putIfAbsent(name, tool) != null) {
                throw new IllegalArgumentException("duplicate tool name " + name);
            }
            return this;
        }

        public Builder registerAll(Collection<? extends Tool> list) {
            list.forEach(this::register);
            return this;
        }

        /** Sets the timeout of one tool. */
        public Builder timeout(String tool, Duration timeout) {
            timeouts.put(tool, timeout);
            return this;
        }

        public Builder defaultTimeout(Duration timeout) {
            this.defaultTimeout = timeout;
            return this;
        }

        public Builder maxOutputChars(int maxChars) {
            this.maxChars = maxChars;
            return this;
        }

        public ToolRegistry build() {
            return new ToolRegistry(tools, timeouts, defaultTimeout, new OutputLimiter(maxChars));
        }
    }
}
