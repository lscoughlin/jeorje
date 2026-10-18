package org.github.lscoughlin.jeorje.ui.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.compaction.ModelRoles;
import org.github.lscoughlin.jeorje.core.instructions.AgentTypeRegistry;
import org.github.lscoughlin.jeorje.core.instructions.CommandRegistry;
import org.github.lscoughlin.jeorje.core.instructions.InstructionsLoader;
import org.github.lscoughlin.jeorje.core.instructions.Scopes;
import org.github.lscoughlin.jeorje.core.instructions.SkillRegistry;
import org.github.lscoughlin.jeorje.ui.keys.KeyBindings;

/** A fake backend that records calls. Registries are scanned from a temp project directory. */
final class FakeBackend implements CommandBackend {

    record Prompt(String text, String model) {}

    ModelRoles roles = new ModelRoles("vendor/default", "vendor/fast", null);
    final List<ModelRoles> applied = new ArrayList<>();
    final List<Prompt> prompts = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    boolean trusted;
    boolean quit;
    List<InstructionsLoader.Instruction> instructions = List.of();
    List<String> hits = List.of();
    SkillRegistry skills;
    CommandRegistry commands;
    AgentTypeRegistry agentTypes;

    FakeBackend(Path project, Set<String> builtIns) {
        rescan(project, builtIns);
    }

    void rescan(Path project, Set<String> builtIns) {
        skills = SkillRegistry.scan(Scopes.roots("skills", null, project, true, List.of(), List.of()));
        commands = CommandRegistry.scan(Scopes.roots("commands", null, project, true, List.of(), List.of()), builtIns);
        agentTypes = AgentTypeRegistry.scan(Scopes.roots("agents", null, project, true, List.of(), List.of()));
    }

    static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Override public ModelRoles roles() { return roles; }
    @Override public void applyRoles(ModelRoles r) { applied.add(r); roles = r; }
    @Override public String compactNow() { calls.add("compact"); return "Compacted."; }
    @Override public String pinLast() { calls.add("pin"); return "Pinned."; }
    @Override public List<String> recall(String query) { calls.add("recall:" + query); return hits; }
    @Override public KeyBindings keys() { return KeyBindings.defaults(); }
    @Override public List<InstructionsLoader.Instruction> instructions() { return instructions; }
    @Override public SkillRegistry skills() { return skills; }
    @Override public CommandRegistry commands() { return commands; }
    @Override public AgentTypeRegistry agentTypes() { return agentTypes; }
    @Override public boolean projectTrusted() { return trusted; }
    @Override public void trustProject() { trusted = true; calls.add("trust"); }
    @Override public void revokeTrust() { trusted = false; calls.add("revoke"); }
    @Override public void sendPrompt(String prompt, String model) { prompts.add(new Prompt(prompt, model)); }
    @Override public String cancel(String name) { calls.add("cancel:" + name); return "Cancelled."; }
    @Override public String back() { calls.add("back"); return "Back."; }
    @Override public void quit() { quit = true; }
}
