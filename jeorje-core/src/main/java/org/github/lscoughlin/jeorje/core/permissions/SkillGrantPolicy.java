package org.github.lscoughlin.jeorje.core.permissions;

import java.nio.file.FileSystems;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.github.lscoughlin.jeorje.core.instructions.AllowedTools;
import org.github.lscoughlin.jeorje.core.instructions.Skill;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the {@code allowed-tools} field of loaded skills ({@code skills.honorAllowedTools}).
 *
 * <p>The mapping: after the model loads a skill, a call that the skill names and that the base policy
 * would ask about becomes {@code ALLOW}. A {@code DENY} of the base policy always stays, and an
 * {@code ALLOW} stays. A grant without a pattern covers every call of the tool. A {@code bash} grant
 * {@code Bash(git:*)} covers the commands that start with {@code git }. Another pattern is a glob pattern on the main
 * argument of the call (the path, the command, or the URL).
 */
public final class SkillGrantPolicy implements PermissionPolicy {

    private static final Logger log = LoggerFactory.getLogger(SkillGrantPolicy.class);

    private final PermissionPolicy base;
    private final boolean honor;
    private final List<AllowedTools.Grant> grants;

    /**
     * @param base the policy that decides first
     * @param honor false to ignore all grants ({@code skills.honorAllowedTools} is off)
     */
    public SkillGrantPolicy(PermissionPolicy base, boolean honor) {
        this(base, honor, new CopyOnWriteArrayList<>());
    }

    private SkillGrantPolicy(PermissionPolicy base, boolean honor, List<AllowedTools.Grant> grants) {
        this.base = base;
        this.honor = honor;
        this.grants = grants;
    }

    /**
     * The same grants over another base policy. The result shares the grant list with this policy, so a skill that
     * the model loads later also counts for the result. A subagent in a worktree uses it.
     */
    public SkillGrantPolicy over(PermissionPolicy otherBase) {
        return new SkillGrantPolicy(otherBase, honor, grants);
    }

    /** Records the grants of a skill that the model loaded. */
    public void skillLoaded(Skill skill) {
        if (!honor || skill == null) return;
        var parsed = AllowedTools.parse(skill.allowedTools());
        if (parsed.grants().isEmpty()) return;
        grants.addAll(parsed.grants());
        log.atInfo().log(() -> "Skill " + skill.name() + " grants " + parsed.grants().size() + " tool pattern(s).");
    }

    /** The grants that are active now. */
    public List<AllowedTools.Grant> grants() {
        return List.copyOf(grants);
    }

    @Override
    public Decision check(ToolUse action) {
        var decision = base.check(action);
        if (decision != Decision.ASK || grants.isEmpty()) return decision;
        for (var grant : grants) {
            if (matches(grant, action)) return Decision.ALLOW;
        }
        return decision;
    }

    static boolean matches(AllowedTools.Grant grant, ToolUse action) {
        if (!grant.tool().equals(action.tool())) return false;
        if (grant.pattern() == null) return true;
        var argument = mainArgument(action);
        if (argument == null) return false;
        if (grant.bashPrefix()) return argument.stripLeading().startsWith(grant.pattern())
                || argument.strip().equals(grant.pattern().strip());
        try {
            var matcher = FileSystems.getDefault().getPathMatcher("glob:" + grant.pattern());
            return matcher.matches(java.nio.file.Path.of(argument));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String mainArgument(ToolUse action) {
        var input = action.input();
        if (input == null) return null;
        for (var key : new String[] {"command", "path", "url", "pattern", "query"}) {
            var node = input.get(key);
            if (node != null && node.isString()) return node.asString();
        }
        return null;
    }
}
