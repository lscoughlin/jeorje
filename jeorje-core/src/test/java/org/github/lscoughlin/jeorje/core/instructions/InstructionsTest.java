package org.github.lscoughlin.jeorje.core.instructions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstructionsTest {

    @TempDir
    Path tmp;

    private Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    @Test
    void systemPromptHasUserThenProjectAndSkipsDirectoryFiles() throws IOException {
        var home = tmp.resolve("home");
        var root = tmp.resolve("proj");
        write(home.resolve(".jeorje/AGENTS.md"), "USER-A");
        write(home.resolve(".agents/AGENTS.md"), "USER-B");
        write(root.resolve("AGENTS.md"), "PROJECT");
        write(root.resolve("sub/AGENTS.md"), "SUB");
        var loader = new InstructionsLoader(home, 1000, 10_000);
        var text = loader.systemPrompt(root);
        assertTrue(text.indexOf("USER-A") < text.indexOf("USER-B"));
        assertTrue(text.indexOf("USER-B") < text.indexOf("PROJECT"));
        assertFalse(text.contains("SUB"));
    }

    @Test
    void directoryFilesRunFromRootDownWithClosestLastAndNoRootFile() throws IOException {
        var root = tmp.resolve("proj");
        write(root.resolve("AGENTS.md"), "ROOT");
        write(root.resolve("a/AGENTS.md"), "A");
        write(root.resolve("a/b/c/AGENTS.md"), "C");
        var loader = new InstructionsLoader(tmp.resolve("home"), 1000, 10_000);
        var files = loader.directoryFiles(root, root.resolve("a/b/c"));
        assertEquals(2, files.size());
        assertEquals("A", files.get(0).text());
        assertEquals("C", files.get(1).text());
        assertTrue(loader.directoryFiles(root, root).isEmpty());
        assertTrue(loader.directoryFiles(root, tmp).isEmpty());
        var all = loader.applicable(root, root.resolve("a"));
        assertEquals(List.of("ROOT", "A"), all.stream().map(InstructionsLoader.Instruction::text).toList());
    }

    @Test
    void fileIsCutAtMaxBytesWithNote() throws IOException {
        var root = tmp.resolve("proj");
        write(root.resolve("AGENTS.md"), "x".repeat(500));
        var loader = new InstructionsLoader(tmp.resolve("home"), 100, 10_000);
        var file = loader.systemPromptFiles(root).getFirst();
        assertTrue(file.truncated());
        assertTrue(file.text().contains("cut at 100 bytes"));
        assertTrue(file.text().length() < 200);
    }

    @Test
    void totalIsLimited() throws IOException {
        var root = tmp.resolve("proj");
        var home = tmp.resolve("home");
        write(home.resolve(".jeorje/AGENTS.md"), "u".repeat(300));
        write(root.resolve("AGENTS.md"), "p".repeat(300));
        var loader = new InstructionsLoader(home, 1000, 400);
        var text = loader.systemPrompt(root);
        assertTrue(text.getBytes().length < 520);
        assertTrue(text.contains("total size limit"));
    }

    @Test
    void reloadsOnlyWhenModificationTimeChanges() throws IOException {
        var root = tmp.resolve("proj");
        var file = write(root.resolve("AGENTS.md"), "ONE");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000));
        var loader = new InstructionsLoader(tmp.resolve("home"), 1000, 10_000);
        assertTrue(loader.systemPrompt(root).contains("ONE"));
        loader.systemPrompt(root);
        assertEquals(1, loader.diskReads());
        Files.writeString(file, "TWO!");
        Files.setLastModifiedTime(file, FileTime.fromMillis(2_000_000));
        assertTrue(loader.systemPrompt(root).contains("TWO!"));
        assertEquals(2, loader.diskReads());
        Files.delete(file);
        assertEquals("", loader.systemPrompt(root));
    }
}
