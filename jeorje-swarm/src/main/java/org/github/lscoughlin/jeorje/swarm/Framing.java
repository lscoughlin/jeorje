package org.github.lscoughlin.jeorje.swarm;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Length-prefixed frames: a 4-byte big-endian length, then the bytes of one JSON object. */
final class Framing {
    /** Thrown when a frame is larger than the limit. */
    static final class FrameTooLargeException extends IOException {
        FrameTooLargeException(String message) {
            super(message);
        }
    }

    private Framing() {}

    static void write(DataOutputStream out, byte[] payload, int maxBytes) throws IOException {
        if (payload.length > maxBytes) {
            throw new FrameTooLargeException("frame of " + payload.length + " bytes is above " + maxBytes);
        }
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    static byte[] read(DataInputStream in, int maxBytes) throws IOException {
        var length = in.readInt();
        if (length < 0 || length > maxBytes) {
            throw new FrameTooLargeException("frame of " + length + " bytes is above " + maxBytes);
        }
        var bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
}
