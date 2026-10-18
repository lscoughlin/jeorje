package org.github.lscoughlin.jeorje.tools.shell;

/** Receives the output of a shell command while the command runs. */
@FunctionalInterface
public interface OutputListener {
    /** A listener that ignores all output. */
    OutputListener NONE = (id, text) -> {};

    /**
     * Called for each piece of output.
     *
     * @param processId the id of the process: the id of the tool call, or the background id
     * @param text the new output
     */
    void onOutput(String processId, String text);
}
