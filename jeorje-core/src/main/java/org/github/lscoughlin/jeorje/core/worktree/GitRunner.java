package org.github.lscoughlin.jeorje.core.worktree;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Runs the {@code git} program. A non-zero exit code is not an error: it is in the {@link GitResult}. A git call
 * that cannot run throws {@link GitException}. Tests replace this interface with a fake.
 */
public interface GitRunner {
    /** The oldest git version with {@code git worktree remove}. */
    int[] MINIMUM_VERSION = {2, 17};

    /** The default timeout for one call. */
    Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /** Runs git in a directory with extra environment variables and a timeout. */
    GitResult run(Path directory, List<String> args, Map<String, String> environment, Duration timeout);

    /** Runs git in a directory with the default timeout. */
    default GitResult run(Path directory, String... args) {
        return run(directory, List.of(args), Map.of(), DEFAULT_TIMEOUT);
    }

    /** Runs git with extra environment variables. */
    default GitResult runWith(Path directory, Map<String, String> environment, String... args) {
        return run(directory, List.of(args), environment, DEFAULT_TIMEOUT);
    }

    /** The runner that starts the {@code git} program. */
    static GitRunner process() { return new ProcessGitRunner(); }

    /**
     * Checks that git exists and that its version is 2.17 or newer.
     *
     * @return the version text, for example {@code 2.56.0}
     * @throws GitException if git is missing or too old
     */
    default String checkVersion() {
        var result = run(Path.of(System.getProperty("user.dir")), "--version");
        if (!result.ok()) throw new GitException("git --version failed: " + result.text());
        var matcher = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(result.out());
        if (!matcher.find()) throw new GitException("Cannot read the git version from: " + result.trimmed());
        var major = Integer.parseInt(matcher.group(1));
        var minor = Integer.parseInt(matcher.group(2));
        if (major < MINIMUM_VERSION[0] || (major == MINIMUM_VERSION[0] && minor < MINIMUM_VERSION[1])) {
            throw new GitException("git " + matcher.group() + " is too old. Worktrees need git "
                + MINIMUM_VERSION[0] + "." + MINIMUM_VERSION[1] + " or newer.");
        }
        return matcher.group();
    }
}
