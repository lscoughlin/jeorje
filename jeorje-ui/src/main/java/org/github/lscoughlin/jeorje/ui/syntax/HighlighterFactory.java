package org.github.lscoughlin.jeorje.ui.syntax;

import org.jline.builtins.SyntaxHighlighter;
import org.jline.utils.AttributedString;

/** Creates the highlighter for a syntax. A test can supply a fake. */
@FunctionalInterface
public interface HighlighterFactory {

    /**
     * Writes a one-line main config that includes the syntax file.
     *
     * <p>{@code SyntaxHighlighter.build(Path, String)} reads a main config ({@code jnanorc}) and follows its
     * {@code include} lines. It does not read a syntax file directly.
     */
    private static java.nio.file.Path wrapperFor(SyntaxInfo syntax) throws java.io.IOException {
        var dir = WrapperDir.get();
        var name = Integer.toHexString(syntax.file().toAbsolutePath().toString().hashCode()) + ".jnanorc";
        var wrapper = dir.resolve(name);
        var content = "include \"" + syntax.file().toAbsolutePath() + "\"\n";
        java.nio.file.Files.writeString(wrapper, content);
        return wrapper;
    }

    LineHighlighter create(SyntaxInfo syntax) throws Exception;

    /** The factory that uses the JLine engine. */
    static HighlighterFactory jline() {
        return syntax -> {
            var engine = SyntaxHighlighter.build(wrapperFor(syntax), syntax.name());
            return new LineHighlighter() {
                @Override
                public AttributedString highlight(String line) {
                    return engine.highlight(new AttributedString(line));
                }

                @Override
                public void reset() {
                    engine.reset();
                }
            };
        };
    }
}

/** The temporary directory of the wrapper configs. It lives for the run. */
final class WrapperDir {
    private static java.nio.file.Path dir;

    private WrapperDir() {
    }

    static synchronized java.nio.file.Path get() throws java.io.IOException {
        if (dir == null) {
            var created = java.nio.file.Files.createTempDirectory("jeorje-syntax-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (var walk = java.nio.file.Files.walk(created)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (java.io.IOException e) {
                    // The system temporary directory cleans up later.
                }
            }));
            dir = created;
        }
        return dir;
    }
}
