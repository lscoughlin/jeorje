package org.github.lscoughlin.jeorje.core.state;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The directories whose context an agent got already. The set is immutable. */
public final class DirectoryContextSet {
    private static final DirectoryContextSet EMPTY = new DirectoryContextSet(Set.of());

    private final Set<Path> directories;

    private DirectoryContextSet(Set<Path> directories) {
        this.directories = directories;
    }

    public static DirectoryContextSet empty() { return EMPTY; }

    public boolean contains(Path directory) { return directories.contains(directory); }

    public int size() { return directories.size(); }

    public DirectoryContextSet with(Path directory) {
        if (directories.contains(directory)) return this;
        var copy = new HashSet<>(directories);
        copy.add(directory);
        return new DirectoryContextSet(Set.copyOf(copy));
    }

    /** A set without the directory. The agent gets its context again after this. */
    public DirectoryContextSet without(Path directory) {
        if (!directories.contains(directory)) return this;
        var copy = new HashSet<>(directories);
        copy.remove(directory);
        return new DirectoryContextSet(Set.copyOf(copy));
    }

    /** A set with the directory updates applied in order. Other updates are ignored. */
    public DirectoryContextSet apply(List<StateUpdate> updates) {
        var result = this;
        for (var update : updates) {
            if (update instanceof StateUpdate.DirectoryDelivered delivered) {
                result = result.with(delivered.directory());
            }
        }
        return result;
    }
}
