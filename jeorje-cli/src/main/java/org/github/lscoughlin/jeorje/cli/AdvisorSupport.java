package org.github.lscoughlin.jeorje.cli;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.github.lscoughlin.jeorje.core.agent.Agent;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.model.LlmRequest;
import org.github.lscoughlin.jeorje.core.model.ToolDefinition;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.LlmClient;
import org.github.lscoughlin.jeorje.core.port.Tool;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.tools.memory.AdvisorContext;
import org.github.lscoughlin.jeorje.tools.memory.AdvisorTool;
import tools.jackson.databind.JsonNode;

/**
 * The glue for the {@code advisor} tool.
 *
 * <p>An agent keeps its tool list from the time it is built. The {@code /advisor} command can change the
 * advisor model at any time. So the agent always holds {@link #slot}, and the model client that {@link #tap}
 * returns hides the tool from the model while no advisor model is set. The tap also keeps the last request of
 * the agent. That request is the working context that the advisor sees.
 */
public final class AdvisorSupport {
    private static final Pattern GUIDANCE_LINE = Pattern.compile("(?m)^- " + AdvisorTool.NAME + ": .*\\R?");

    private final BooleanSupplier advisorOn;
    private final Supplier<LlmClient> advisorClient;
    private final AtomicReference<AdvisorContext> lastContext = new AtomicReference<>();

    /**
     * @param advisorOn true while an advisor model is set
     * @param advisorClient makes the client of the advisor model. It is called only while {@code advisorOn} is true.
     */
    public AdvisorSupport(BooleanSupplier advisorOn, Supplier<LlmClient> advisorClient) {
        this.advisorOn = advisorOn;
        this.advisorClient = advisorClient;
    }

    /** True when an advisor model is set. */
    public boolean enabled() {
        return advisorOn.getAsBoolean();
    }

    /**
     * The working context of an agent, as of its last model call. Each agent has its own: an advisor call of a
     * subagent never sees the context of the main agent.
     */
    public AdvisorContext context(AgentId agent) {
        return Agent.live(agent).flatMap(Agent::lastRequest)
                .map(request -> new AdvisorContext(request.system(), request.messages()))
                .orElseGet(() -> {
                    var last = lastContext.get();
                    return last == null ? new AdvisorContext(null, java.util.List.of()) : last;
                });
    }

    /** The tool for the agent. It answers with an error while no advisor model is set. */
    public Tool slot() {
        var definition = new AdvisorTool(null, null).definition();
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public boolean readOnly() {
                return true;
            }

            @Override
            public ToolResultData execute(JsonNode input, ToolContext context) throws Exception {
                if (!enabled()) return ToolResultData.error("The advisor is off. Use /advisor to turn it on.");
                LlmClient client;
                try {
                    client = advisorClient.get();
                } catch (RuntimeException e) {
                    return ToolResultData.error("The advisor is not available: " + e.getMessage());
                }
                return new AdvisorTool(client, AdvisorSupport.this::context).execute(input, context);
            }
        };
    }

    /** Wraps the client of the agent. */
    public LlmClient tap(LlmClient delegate) {
        return tap(delegate, this::enabled);
    }

    LlmClient tap(LlmClient delegate, BooleanSupplier on) {
        return (request, cancel) -> {
            lastContext.set(new AdvisorContext(request.system(), request.messages()));
            if (on.getAsBoolean()) return delegate.stream(request, cancel);
            var tools = request.tools().stream().filter(t -> !AdvisorTool.NAME.equals(t.name())).toList();
            var system = request.system() == null ? null : GUIDANCE_LINE.matcher(request.system()).replaceAll("");
            return delegate.stream(new LlmRequest(system, request.messages(), tools, request.maxOutputTokens()),
                    cancel);
        };
    }
}
