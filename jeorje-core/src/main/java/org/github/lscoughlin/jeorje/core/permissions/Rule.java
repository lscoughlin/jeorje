package org.github.lscoughlin.jeorje.core.permissions;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.github.lscoughlin.jeorje.core.config.JeorjeConfig;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy.Decision;

/**
 * A permission rule. The tool is a tool name or {@code *}. The pattern is optional. A pattern
 * that starts with {@code re:} is a regular expression. A pattern with {@code *} or {@code ?} is
 * a glob. Any other pattern is a prefix. For {@code webfetch}, the pattern is a domain.
 */
public record Rule(String tool, String pattern, Decision decision) {

    public Rule {
        if (tool == null || tool.isBlank()) throw new IllegalArgumentException("rule tool is blank");
        if (decision == null) throw new IllegalArgumentException("rule decision is null");
        if (pattern != null && pattern.isBlank()) pattern = null;
    }

    /** Makes a rule from a config rule. */
    public static Rule from(JeorjeConfig.PermissionRule rule) {
        return new Rule(rule.tool(), rule.pattern(),
                Decision.valueOf(rule.decision().toUpperCase(Locale.ROOT)));
    }

    /** True if the rule applies to the tool and to one of the subjects. */
    public boolean matches(String toolName, List<String> subjects) {
        if (!tool.equals("*") && !tool.equals(toolName)) return false;
        if (pattern == null) return true;
        for (var subject : subjects) {
            if (matchesSubject(subject, toolName)) return true;
        }
        return false;
    }

    /** A higher number is more specific. */
    public int specificity() {
        var score = tool.equals("*") ? 0 : 1000;
        if (pattern != null) score += 100 + pattern.length();
        return score;
    }

    private boolean matchesSubject(String subject, String toolName) {
        if (pattern.startsWith("re:")) {
            try {
                return Pattern.compile(pattern.substring(3)).matcher(subject).matches();
            } catch (PatternSyntaxException e) {
                return false;
            }
        }
        if (toolName.equals("webfetch")) {
            return NetworkPolicy.domainMatches(subject, pattern);
        }
        if (pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0) {
            try {
                return FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(Path.of(subject));
            } catch (RuntimeException e) {
                return false;
            }
        }
        return subject.startsWith(pattern);
    }

    /** Picks the winning decision of the matching rules, or null if none match. */
    public static Decision resolve(List<Rule> rules, String toolName, List<String> subjects) {
        Rule best = null;
        for (var rule : rules) {
            if (!rule.matches(toolName, subjects)) continue;
            if (best == null || rule.specificity() > best.specificity()
                    || (rule.specificity() == best.specificity() && rule.decision().compareTo(best.decision()) > 0)) {
                best = rule;
            }
        }
        return best == null ? null : best.decision();
    }

    /** The stricter of two decisions. DENY is above ASK, and ASK is above ALLOW. */
    public static Decision strictest(Decision a, Decision b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
