package org.github.lscoughlin.jeorje.core.state;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The file states of one agent. The table is immutable. Each change gives a new table. */
public final class FileStateTable {
    private static final FileStateTable EMPTY = new FileStateTable(Map.of());

    private final Map<Path, FileState> states;

    private FileStateTable(Map<Path, FileState> states) {
        this.states = states;
    }

    public static FileStateTable empty() { return EMPTY; }

    public Optional<FileState> get(Path path) {
        return Optional.ofNullable(states.get(path));
    }

    public int size() { return states.size(); }

    /** A table with the state of one file set. */
    public FileStateTable with(Path path, FileState state) {
        var copy = new HashMap<>(states);
        copy.put(path, state);
        return new FileStateTable(Map.copyOf(copy));
    }

    /** A table without one file. */
    public FileStateTable without(Path path) {
        if (!states.containsKey(path)) return this;
        var copy = new HashMap<>(states);
        copy.remove(path);
        return new FileStateTable(Map.copyOf(copy));
    }

    /** A table with the file updates applied in order. Other updates are ignored. */
    public FileStateTable apply(List<StateUpdate> updates) {
        var result = this;
        for (var update : updates) {
            if (update instanceof StateUpdate.FileRecorded recorded) {
                result = result.with(recorded.path(), recorded.state());
            }
        }
        return result;
    }
}
