package org.github.lscoughlin.jeorje.tools.diff;

import java.nio.file.Files;
import java.nio.file.Path;
import org.github.lscoughlin.jeorje.core.diff.SplitText;
import org.github.lscoughlin.jeorje.core.diff.TextDiff;
import org.github.lscoughlin.jeorje.core.diff.TextDiffException;
import org.github.lscoughlin.jeorje.core.model.ToolResultData;
import org.github.lscoughlin.jeorje.core.port.PermissionPolicy;
import org.github.lscoughlin.jeorje.core.port.ToolContext;
import org.github.lscoughlin.jeorje.core.model.ToolUse;
import org.github.lscoughlin.jeorje.tools.registry.Describe;
import org.github.lscoughlin.jeorje.tools.registry.RecordTool;
import tools.jackson.databind.json.JsonMapper;

/** The {@code diff} tool. It compares two files, or a file and a text. It never changes a file. */
public final class DiffTool extends RecordTool<DiffTool.Input> {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The input of {@code diff}. */
    public record Input(
            @Describe("Path of the first file.") String a,
            @Describe("Path of the second file. Use b or bContent.") String b,
            @Describe("Text to compare with a, in place of a second file.") String bContent,
            @Describe("Lines of context around each change. Default 3.") Integer context,
            @Describe("none (default), trailing, or all.") String ignoreWhitespace,
            @Describe("myers (default) or myers-linear.") String algorithm,
            @Describe("unified (default) or inline (changed words marked).") String format) {}

    private final PermissionPolicy policy;
    private final TextDiff.Options defaults;
    private final String defaultAlgorithm;

    public DiffTool(PermissionPolicy policy) {
        this(policy, TextDiff.Options.defaults(), "myers");
    }

    /**
     * @param defaults the options when the call gives none (the config {@code tools.diff.context} and
     *        {@code tools.diff.maxLines})
     * @param algorithm the algorithm when the call gives none ({@code tools.diff.algorithm})
     */
    public DiffTool(PermissionPolicy policy, TextDiff.Options defaults, String algorithm) {
        super("diff", "Compare two files, or a file and a text, and return a unified diff. Read-only.",
                Input.class, true);
        this.policy = policy;
        this.defaults = defaults;
        this.defaultAlgorithm = algorithm == null ? "myers" : algorithm;
    }

    @Override
    protected ToolResultData run(Input input, ToolContext context) throws Exception {
        if (input.a() == null) return ToolResultData.error("a is required.");
        if ((input.b() == null) == (input.bContent() == null)) {
            return ToolResultData.error("Use exactly one of b and bContent.");
        }
        var options = defaults;
        if (input.context() != null) {
            if (input.context() < 0) return ToolResultData.error("context must be 0 or more.");
            options = options.withContext(input.context());
        }
        var whitespace = input.ignoreWhitespace() == null ? "none" : input.ignoreWhitespace();
        switch (whitespace) {
            case "none" -> options = options.withWhitespace(TextDiff.Whitespace.NONE);
            case "trailing" -> options = options.withWhitespace(TextDiff.Whitespace.TRAILING);
            case "all" -> options = options.withWhitespace(TextDiff.Whitespace.ALL);
            default -> { return ToolResultData.error("ignoreWhitespace must be none, trailing, or all."); }
        }
        var algorithm = input.algorithm() == null ? defaultAlgorithm : input.algorithm();
        switch (algorithm) {
            case "myers" -> options = options.withAlgorithm(TextDiff.Algorithm.MYERS);
            case "myers-linear" -> options = options.withAlgorithm(TextDiff.Algorithm.MYERS_LINEAR);
            default -> { return ToolResultData.error("algorithm must be myers or myers-linear."); }
        }
        var format = input.format() == null ? "unified" : input.format();
        if (!format.equals("unified") && !format.equals("inline")) {
            return ToolResultData.error("format must be unified or inline.");
        }
        try {
            var base = context.workingDirectory().toAbsolutePath().normalize();
            var pathA = resolve(input.a(), base, context);
            var textA = load(pathA, input.a());
            String nameB;
            SplitText textB;
            if (input.b() != null) {
                var pathB = resolve(input.b(), base, context);
                textB = load(pathB, input.b());
                nameB = display(base, pathB);
            } else {
                textB = SplitText.split(input.bContent());
                nameB = display(base, pathA);
            }
            var nameA = display(base, pathA);
            if (format.equals("inline")) {
                var rows = TextDiff.inline(textA, textB, options);
                return ToolResultData.ok(rows.isEmpty() ? "no differences\n" : TextDiff.renderInline(rows));
            }
            var result = TextDiff.unified("a/" + nameA, "b/" + nameB, textA, textB, options);
            if (result.identical()) return ToolResultData.ok("no differences\n");
            return ToolResultData.ok("+" + result.added() + " -" + result.removed() + "\n" + result.unified());
        } catch (Refused e) {
            return ToolResultData.error(e.getMessage());
        } catch (TextDiffException e) {
            return ToolResultData.error("Cannot compare: " + e.getMessage());
        }
    }

    private static final class Refused extends Exception {
        Refused(String message) { super(message); }
    }

    private Path resolve(String path, Path base, ToolContext context) throws Refused {
        if (path.isBlank()) throw new Refused("The path is empty.");
        var resolved = base.resolve(path).normalize();
        if (policy != null) {
            var input = JSON.createObjectNode().put("a", resolved.toString());
            if (policy.check(new ToolUse("file-diff", "diff", input)) == PermissionPolicy.Decision.DENY) {
                throw new Refused("Refused: the permission policy does not allow diff on " + path + ".");
            }
        }
        return resolved;
    }

    private static SplitText load(Path file, String shown) throws Refused {
        if (!Files.isRegularFile(file)) throw new Refused("File does not exist: " + shown);
        try {
            return SplitText.decode(Files.readAllBytes(file));
        } catch (java.io.IOException e) {
            throw new Refused("Cannot read " + shown + ": " + e.getMessage());
        }
    }

    private static String display(Path base, Path file) {
        return file.startsWith(base) && !file.equals(base)
                ? base.relativize(file).toString().replace('\\', '/') : file.toString();
    }
}
