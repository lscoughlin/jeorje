package org.github.lscoughlin.jeorje.core.state;

import java.nio.file.Path;
import java.util.Objects;

/** A change that a tool asks the agent to make in the state that the agent owns. */
public sealed interface StateUpdate {

    /** The agent read or wrote a file and must remember its state. */
    record FileRecorded(Path path, FileState state) implements StateUpdate {
        public FileRecorded {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(state, "state");
        }
    }

    /** The tool gave the context of a directory to the agent. */
    record DirectoryDelivered(Path directory) implements StateUpdate {
        public DirectoryDelivered { Objects.requireNonNull(directory, "directory"); }
    }
}
