package org.github.lscoughlin.jeorje.tools.shell;

/**
 * The result of {@link ShellRunner#run}.
 *
 * @param command the command that ran
 * @param output the output, shortened to the limits of the shell settings
 * @param exitCode the exit code, or -1 when the command did not start or did not finish
 * @param timedOut true when the command ran too long and the runner killed it
 */
public record ShellResult(String command, String output, int exitCode, boolean timedOut) {
    /** True when the command finished with the exit code 0. */
    public boolean ok() {
        return exitCode == 0;
    }
}
