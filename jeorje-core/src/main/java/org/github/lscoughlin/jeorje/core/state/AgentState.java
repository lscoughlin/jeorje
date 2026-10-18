package org.github.lscoughlin.jeorje.core.state;

import java.util.List;

/**
 * The state that one agent owns for the file tools: the file-state table and the directory
 * context set. Tools read it. The agent loop applies the updates of tool results in order.
 */
public final class AgentState {
    private volatile FileStateTable files = FileStateTable.empty();
    private volatile DirectoryContextSet directories = DirectoryContextSet.empty();

    public FileStateTable files() { return files; }

    public DirectoryContextSet directories() { return directories; }

    /** Applies the updates in order. */
    public synchronized void apply(List<StateUpdate> updates) {
        if (updates.isEmpty()) return;
        files = files.apply(updates);
        directories = directories.apply(updates);
    }

    /** Makes the agent get the context of a directory again. */
    public synchronized void forgetDirectory(java.nio.file.Path directory) {
        directories = directories.without(directory);
    }
}
