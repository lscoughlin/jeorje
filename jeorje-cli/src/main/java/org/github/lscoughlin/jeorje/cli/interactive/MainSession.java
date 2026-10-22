package org.github.lscoughlin.jeorje.cli.interactive;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.github.lscoughlin.jeorje.core.agent.Agent;
import org.github.lscoughlin.jeorje.core.agent.AgentInput;
import org.github.lscoughlin.jeorje.core.compaction.Pruner;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.github.lscoughlin.jeorje.core.plan.PlanMode;
import org.github.lscoughlin.jeorje.core.port.ContextStore;
import org.github.lscoughlin.jeorje.core.session.Session;
import org.github.lscoughlin.jeorje.core.session.TranscriptWriter;
import org.github.lscoughlin.jeorje.core.subagent.SubagentRuntime;

/**
 * The state of one conversation. The process holds one of these for each session that it has open.
 *
 * <p>The fields here belong to one conversation: its {@link Session}, its context store, its transcript, its main
 * agent, the runtime of its subagents, its permission policy, its plan mode, and its busy flag. The screen, the
 * terminal, the model clients, and the other parts that one process shares stay in {@link InteractiveSession}.
 */
public final class MainSession {
    /** The id of the first session in a process. */
    public static final AgentId MAIN = new AgentId("main");

    private final AgentId id;
    private final String name;
    private final AtomicBoolean busy = new AtomicBoolean();

    private Session session;
    private ContextStore store;
    private TranscriptWriter transcript;
    private Agent agent;
    private SubagentRuntime runtime;
    private GatedPolicy policy;
    private final PlanMode planMode = new PlanMode();
    private Pruner pruner;
    private Consumer<AgentInput> input;
    private volatile boolean ended;

    /**
     * @param id the id of the main agent of this conversation
     * @param name the name that the user sees for it
     */
    public MainSession(AgentId id, String name) {
        this.id = id;
        this.name = name;
    }

    /** The first session of a process. Its id is {@code main} and its name is {@code main}. */
    public static MainSession first() {
        return new MainSession(MAIN, "main");
    }

    public AgentId id() { return id; }

    public String name() { return name; }

    /** True while the main agent runs a turn. */
    public AtomicBoolean busy() { return busy; }

    public Session session() { return session; }

    void session(Session session) { this.session = session; }

    public ContextStore store() { return store; }

    void store(ContextStore store) { this.store = store; }

    public TranscriptWriter transcript() { return transcript; }

    void transcript(TranscriptWriter transcript) { this.transcript = transcript; }

    public Agent agent() { return agent; }

    void agent(Agent agent) { this.agent = agent; }

    public SubagentRuntime runtime() { return runtime; }

    void runtime(SubagentRuntime runtime) { this.runtime = runtime; }

    public GatedPolicy policy() { return policy; }

    void policy(GatedPolicy policy) { this.policy = policy; }

    public PlanMode planMode() { return planMode; }

    /** The compaction support of this conversation. */
    Pruner pruner() { return pruner; }

    void pruner(Pruner pruner) { this.pruner = pruner; }

    /** Delivers an input to the main agent of this conversation. Other sessions use it to reply. */
    public Consumer<AgentInput> input() { return input; }

    void input(Consumer<AgentInput> input) { this.input = input; }

    /** True after the agent of this conversation closed. */
    public boolean ended() { return ended; }

    /** Marks this conversation as ended. The session stays in the list of sessions. */
    void end() { this.ended = true; }
}
