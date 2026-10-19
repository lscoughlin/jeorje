package org.github.lscoughlin.jeorje.cli.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionMentionsTest {
    @TempDir Path tmp;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tmp.resolve("README.md"), "# readme\n");
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/Main.java"), "class Main {}\n");
    }

    private List<String> paths(String line) {
        var into = new ArrayList<String>();
        SessionMentions.addPaths(line, tmp, into);
        return into;
    }

    @Test
    void aMentionAddsTheFile() {
        assertEquals(List.of("README.md"), paths("please read @README.md now"));
    }

    @Test
    void aWordThatDoesNotResolveAddsNothing() {
        assertTrue(paths("@nope.txt").isEmpty());
    }

    @Test
    void aRepeatedMentionAddsOneEntry() {
        assertEquals(List.of("README.md"), paths("@README.md @README.md"));
    }

    @Test
    void aCommaEndsAWord() {
        assertEquals(List.of("src/Main.java"), paths("@src/Main.java, and then go"));
    }

    @Test
    void anEmailAddressAndABareAtAddNothing() {
        assertTrue(paths("email@@host").isEmpty());
        assertTrue(paths("a bare @ alone").isEmpty());
    }

    @Test
    void aPathOutsideTheWorkingDirectoryAddsNothing() {
        assertTrue(paths("@../escape.txt").isEmpty());
    }
}
