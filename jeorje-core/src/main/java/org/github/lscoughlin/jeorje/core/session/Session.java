package org.github.lscoughlin.jeorje.core.session;

import java.nio.file.Path;

/** A session on disk: its id, its directory, and its manifest. */
public record Session(String id, Path dir, SessionManifest manifest) {
    public Path manifestPath() { return dir.resolve("manifest.json"); }

    public Path transcriptPath() { return dir.resolve("transcript.jsonl"); }

    public Path indexDir() { return dir.resolve("index"); }

    /** Returns the session with a new manifest. The manifest is not written. */
    public Session with(SessionManifest next) { return new Session(id, dir, next); }

    /** Writes the manifest with an atomic replace. */
    public void save() { manifest.write(manifestPath()); }
}
