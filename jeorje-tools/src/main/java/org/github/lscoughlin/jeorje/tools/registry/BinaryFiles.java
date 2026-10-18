package org.github.lscoughlin.jeorje.tools.registry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Finds binary content. A file is binary if it has a zero byte or bytes that are not valid UTF-8. */
public final class BinaryFiles {
    private static final int SAMPLE = 8_000;

    private BinaryFiles() {
    }

    /** Checks the first bytes of a file. */
    public static boolean isBinary(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            return isBinary(in.readNBytes(SAMPLE), true);
        }
    }

    /** Checks bytes. Set {@code truncated} if the bytes are only the start of a longer content. */
    public static boolean isBinary(byte[] bytes, boolean truncated) {
        var length = Math.min(bytes.length, SAMPLE);
        for (var i = 0; i < length; i++) {
            if (bytes[i] == 0) return true;
        }
        var cuts = truncated && length == SAMPLE ? 3 : 0;
        for (var cut = 0; cut <= cuts; cut++) {
            if (decodes(bytes, length - cut)) return false;
        }
        return true;
    }

    private static boolean decodes(byte[] bytes, int length) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes, 0, length));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
