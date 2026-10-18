package org.github.lscoughlin.jeorje.ui.commands;

import java.util.List;
import org.github.lscoughlin.jeorje.core.compaction.ModelRoles;
import org.github.lscoughlin.jeorje.core.instructions.AgentTypeRegistry;
import org.github.lscoughlin.jeorje.core.instructions.CommandRegistry;
import org.github.lscoughlin.jeorje.core.instructions.InstructionsLoader;
import org.github.lscoughlin.jeorje.core.instructions.SkillRegistry;
import org.github.lscoughlin.jeorje.ui.keys.KeyBindings;

/** The session services that slash commands use. The UI supplies the real one. Tests supply a fake. */
public interface CommandBackend {

    /** The current model roles. */
    ModelRoles roles();

    /**
     * Applies new roles to the session and saves them in the session manifest. The backend never writes
     * the config file.
     */
    void applyRoles(ModelRoles roles);

    /** Runs compaction now and returns a one-line result. */
    String compactNow();

    /** Pins the last message and returns a one-line result. */
    String pinLast();

    /** Searches the history and returns one line for each hit. */
    List<String> recall(String query);

    KeyBindings keys();

    /** The AGENTS.md files that apply to the working directory, in the order Jeorje uses them. */
    List<InstructionsLoader.Instruction> instructions();

    SkillRegistry skills();

    CommandRegistry commands();

    AgentTypeRegistry agentTypes();

    boolean projectTrusted();

    void trustProject();

    void revokeTrust();

    /** Sends a prompt to the foreground agent. {@code model} is null for the default model. */
    void sendPrompt(String prompt, String model);

    /** Cancels the foreground agent, or the named subagent when {@code name} is not null. */
    String cancel(String name);

    /** Returns to the parent agent. */
    String back();

    void quit();
}
