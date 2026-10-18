package org.github.lscoughlin.jeorje.ui.editor;

import java.nio.file.Path;
import org.github.lscoughlin.jeorje.core.state.FileState;

/** Tells the foreground agent about a file that the user saved, so that the file-state table stays correct. */
@FunctionalInterface
public interface FileStateSink {

    /** Called after a save with the new state of the file. */
    void fileSaved(Path path, FileState state);

    /** A sink that does nothing. */
    static FileStateSink none() {
        return (path, state) -> { };
    }
}
