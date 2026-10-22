package org.github.lscoughlin.jeorje.swarm;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Length-prefixed frames on the streams of an SSH channel. A frame has a 4-byte big-endian length and the
 * bytes of one JSON object. The framing is the same as on the TCP transport. A frame above the size limit
 * fails with an {@link IOException}.
 */
public final class SshFrames {
    private SshFrames() {}

    /** Writes one frame and flushes the stream. */
    public static void write(OutputStream out, byte[] payload, int maxBytes) throws IOException {
        Framing.write(new DataOutputStream(out), payload, maxBytes);
    }

    /** Reads one frame. It throws {@link java.io.EOFException} at the end of the stream. */
    public static byte[] read(InputStream in, int maxBytes) throws IOException {
        return Framing.read(new DataInputStream(in), maxBytes);
    }
}
