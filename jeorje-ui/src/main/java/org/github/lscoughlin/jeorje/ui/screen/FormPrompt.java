package org.github.lscoughlin.jeorje.ui.screen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.github.lscoughlin.jeorje.core.form.Form;
import org.github.lscoughlin.jeorje.core.form.FormAnswer;
import org.github.lscoughlin.jeorje.core.form.FormAnswer.QuestionAnswer;
import org.github.lscoughlin.jeorje.core.form.FormQuestion;
import org.github.lscoughlin.jeorje.core.model.AgentId;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * The form box. It is a state machine over key names, as {@link PermissionPrompt} is, and it has no terminal code.
 *
 * <p>The form shows one question at a time and then a review page. A question has option rows, an optional
 * "other" row, and the row "Chat about this". Key names: {@code up down left right enter esc tab shift-tab space
 * backspace delete home end ctrl-u}, and one printable character as a text (for example {@code "a"} or
 * {@code "é"}).
 */
public final class FormPrompt {
    private enum Mode { NAV, EDIT, NOTE, DECLINE }

    private enum Target { OTHER, TEXT, NOTE }

    private enum RowType { OPTION, OTHER, TEXT, CHAT }

    private record Row(RowType type, int option) {}

    /** The answer state of one question. */
    private static final class State {
        final Set<Integer> selected = new LinkedHashSet<>();
        String other = "";
        boolean otherOn;
        String text = "";
    }

    private static final AttributedStyle BORDER = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
    private static final AttributedStyle BOLD = AttributedStyle.DEFAULT.bold();
    private static final AttributedStyle DIM = AttributedStyle.DEFAULT.faint();
    private static final AttributedStyle ERROR = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
    private static final String[] REVIEW_ACTIONS = {"Submit", "Edit answers", "Decline"};

    private final AgentId agent;
    private final Form form;
    private final List<State> states = new ArrayList<>();
    private int page;
    private int cursor;
    private Mode mode = Mode.NAV;
    private Target target;
    private StringBuilder buffer = new StringBuilder();
    private int caret;
    private String note = "";
    private String message = "";
    private int cursorRow = -1;
    private int cursorColumn;
    private List<Integer> lineRows = List.of();

    public FormPrompt(AgentId agent, Form form) {
        this.agent = agent;
        this.form = form;
        for (var q : form.questions()) {
            var s = new State();
            if (q.defaultValue() != null) {
                if (q.kind() == FormQuestion.Kind.TEXT) s.text = q.defaultValue();
                else if (q.kind() == FormQuestion.Kind.SINGLE) {
                    var i = q.labels().indexOf(q.defaultValue());
                    if (i >= 0) s.selected.add(i);
                }
            }
            states.add(s);
        }
        enterPage(0);
    }

    public AgentId agent() { return agent; }

    public Form form() { return form; }

    /** The index of the page: a question number, or the number of questions for the review page. */
    public int page() { return page; }

    /** The row of the terminal cursor in the last render, or -1 when no text field has the cursor. */
    public int cursorRow() { return cursorRow; }

    /** The column of the terminal cursor in the last render. */
    public int cursorColumn() { return cursorColumn; }

    /** The message in the footer, or an empty text. */
    public String message() { return message; }

    /** True while a text field has the keys (the other answer, a text answer, or the note). */
    public boolean editing() { return mode == Mode.EDIT || mode == Mode.NOTE; }

    /**
     * Handles a click on a line of the last render (0 is the top border). A click on a row moves the cursor to it
     * and acts on it, as the space key does. A click on a review action runs it.
     */
    public Optional<FormAnswer> click(int renderLine) {
        if (mode != Mode.NAV || renderLine < 0 || renderLine >= lineRows.size()) return Optional.empty();
        var row = lineRows.get(renderLine);
        if (row < 0) return Optional.empty();
        message = "";
        cursor = row;
        if (review()) return reviewKey("enter");
        var r = rows().get(row);
        if (r.type() == RowType.CHAT) startNote(); else activate(r, false);
        return Optional.empty();
    }

    private boolean review() { return page >= form.questions().size(); }

    private FormQuestion question() { return form.questions().get(page); }

    private State state() { return states.get(page); }

    private List<Row> rows() {
        var rows = new ArrayList<Row>();
        var q = question();
        if (q.kind() == FormQuestion.Kind.TEXT) {
            rows.add(new Row(RowType.TEXT, 0));
        } else {
            for (var i = 0; i < q.options().size(); i++) rows.add(new Row(RowType.OPTION, i));
            if (q.allowOther()) rows.add(new Row(RowType.OTHER, 0));
        }
        rows.add(new Row(RowType.CHAT, 0));
        return rows;
    }

    // ---- keys ----

    /** Handles one key. Returns the answer when the key ends the form. */
    public Optional<FormAnswer> handleKey(String key) {
        if (key == null || key.isEmpty()) return Optional.empty();
        return switch (mode) {
            case EDIT, NOTE -> editKey(key);
            case DECLINE -> {
                if (key.equalsIgnoreCase("y")) yield Optional.of(FormAnswer.declined());
                mode = Mode.NAV;
                message = "";
                yield Optional.empty();
            }
            case NAV -> review() ? reviewKey(key) : navKey(key);
        };
    }

    private Optional<FormAnswer> navKey(String key) {
        message = "";
        var rows = rows();
        var q = question();
        switch (key) {
            case "up", "k" -> cursor = (cursor + rows.size() - 1) % rows.size();
            case "down", "j" -> cursor = (cursor + 1) % rows.size();
            case "tab", "right" -> forward();
            case "shift-tab", "left" -> back();
            case "esc" -> {
                mode = Mode.DECLINE;
                message = "Decline this form? y/n";
            }
            case "c" -> startNote();
            case "space", " " -> activate(rows.get(cursor), false);
            case "enter" -> {
                var row = rows.get(cursor);
                if (row.type() == RowType.CHAT) startNote();
                else activate(row, true);
            }
            default -> {
                if (key.length() == 1 && key.charAt(0) >= '1' && key.charAt(0) <= '9') {
                    var i = key.charAt(0) - '1';
                    if (q.hasOptions() && i < q.options().size()) {
                        cursor = i;
                        activate(rows.get(i), false);
                    }
                } else if (q.kind() == FormQuestion.Kind.TEXT && rows.get(cursor).type() == RowType.TEXT
                    && isPrintable(key)) {
                    startEdit(Target.TEXT);
                    return editKey(key);
                }
            }
        }
        return Optional.empty();
    }

    /** Acts on the row under the cursor. {@code advance} is true for the enter key. */
    private void activate(Row row, boolean advance) {
        var q = question();
        var s = state();
        switch (row.type()) {
            case OPTION -> {
                if (q.kind() == FormQuestion.Kind.SINGLE) {
                    s.selected.clear();
                    s.selected.add(row.option());
                    s.otherOn = false;
                    if (advance) forward();
                } else if (q.kind() == FormQuestion.Kind.MULTI) {
                    if (s.selected.contains(row.option())) {
                        s.selected.remove(row.option());
                    } else if (q.maxChoices() > 0 && count(s) >= q.maxChoices()) {
                        message = "Choose at most " + q.maxChoices();
                    } else {
                        s.selected.add(row.option());
                    }
                    if (advance && message.isEmpty()) forward();
                }
            }
            case OTHER -> startEdit(Target.OTHER);
            case TEXT -> startEdit(Target.TEXT);
            case CHAT -> startNote();
        }
    }

    private void startEdit(Target t) {
        mode = Mode.EDIT;
        target = t;
        var s = state();
        buffer = new StringBuilder(switch (t) {
            case OTHER -> s.other;
            case TEXT -> s.text;
            case NOTE -> note;
        });
        caret = buffer.length();
    }

    private void startNote() {
        mode = Mode.NOTE;
        target = Target.NOTE;
        buffer = new StringBuilder(note);
        caret = buffer.length();
        message = "Add a note for the agent (optional). Enter sends. Esc goes back.";
    }

    private Optional<FormAnswer> editKey(String key) {
        message = mode == Mode.NOTE ? message : "";
        switch (key) {
            case "enter" -> {
                return finishEdit();
            }
            case "esc" -> {
                if (mode == Mode.NOTE) message = "";
                mode = Mode.NAV;
            }
            case "left" -> caret = caret > 0 ? buffer.offsetByCodePoints(caret, -1) : 0;
            case "right" -> caret = caret < buffer.length() ? buffer.offsetByCodePoints(caret, 1) : caret;
            case "home" -> caret = 0;
            case "end" -> caret = buffer.length();
            case "backspace" -> {
                if (caret > 0) {
                    var from = buffer.offsetByCodePoints(caret, -1);
                    buffer.delete(from, caret);
                    caret = from;
                }
            }
            case "delete" -> {
                if (caret < buffer.length()) buffer.delete(caret, buffer.offsetByCodePoints(caret, 1));
            }
            case "ctrl-u" -> {
                buffer.setLength(0);
                caret = 0;
            }
            case "space" -> insert(" ");
            case "up", "down", "tab", "shift-tab" -> {
                if (mode == Mode.EDIT) {
                    commitEdit();
                    mode = Mode.NAV;
                    return navKey(key);
                }
            }
            default -> {
                if (isPrintable(key)) insert(key);
            }
        }
        return Optional.empty();
    }

    private void insert(String text) {
        buffer.insert(caret, text);
        caret += text.length();
    }

    private static boolean isPrintable(String key) {
        if (key.codePointCount(0, key.length()) != 1) return false;
        var cp = key.codePointAt(0);
        return cp >= 32 && cp != 127 && !Character.isISOControl(cp);
    }

    private void commitEdit() {
        var s = state();
        var text = buffer.toString();
        switch (target) {
            case OTHER -> {
                s.other = text;
                s.otherOn = !text.isBlank();
                if (s.otherOn && question().kind() == FormQuestion.Kind.SINGLE) s.selected.clear();
            }
            case TEXT -> s.text = text;
            case NOTE -> note = text;
        }
    }

    private Optional<FormAnswer> finishEdit() {
        if (mode == Mode.NOTE) {
            note = buffer.toString();
            return Optional.of(build(FormAnswer.Status.CHAT, note.isBlank() ? null : note.strip()));
        }
        var q = question();
        if (target == Target.OTHER && q.kind() == FormQuestion.Kind.MULTI && !buffer.toString().isBlank()
            && !state().otherOn && q.maxChoices() > 0 && count(state()) >= q.maxChoices()) {
            message = "Choose at most " + q.maxChoices();
            return Optional.empty();
        }
        commitEdit();
        mode = Mode.NAV;
        forward();
        return Optional.empty();
    }

    // ---- pages ----

    private void enterPage(int next) {
        page = next;
        cursor = 0;
        mode = Mode.NAV;
        if (!review()) {
            var q = question();
            if (q.kind() == FormQuestion.Kind.SINGLE && !state().selected.isEmpty()) {
                cursor = state().selected.iterator().next();
            } else if (q.kind() == FormQuestion.Kind.TEXT) {
                startEdit(Target.TEXT);
            }
        }
    }

    private int count(State s) { return s.selected.size() + (s.otherOn ? 1 : 0); }

    private boolean answered(int i) {
        var q = form.questions().get(i);
        var s = states.get(i);
        return q.kind() == FormQuestion.Kind.TEXT ? !s.text.isBlank() : count(s) > 0;
    }

    /** The reason that question {@code i} is not valid, or empty. */
    private Optional<String> problem(int i) {
        var q = form.questions().get(i);
        var s = states.get(i);
        if (!answered(i)) return q.required() ? Optional.of("Answer this question first.") : Optional.empty();
        if (q.kind() == FormQuestion.Kind.MULTI) {
            if (count(s) < q.minChoices()) return Optional.of("Choose at least " + q.minChoices());
            if (q.maxChoices() > 0 && count(s) > q.maxChoices()) return Optional.of("Choose at most " + q.maxChoices());
        }
        return Optional.empty();
    }

    private void forward() {
        if (review()) return;
        var p = problem(page);
        if (p.isPresent()) {
            message = p.get();
            return;
        }
        enterPage(page + 1);
    }

    private void back() {
        if (page > 0) enterPage(page - 1);
    }

    private Optional<FormAnswer> reviewKey(String key) {
        message = "";
        switch (key) {
            case "up", "k" -> cursor = (cursor + REVIEW_ACTIONS.length - 1) % REVIEW_ACTIONS.length;
            case "down", "j" -> cursor = (cursor + 1) % REVIEW_ACTIONS.length;
            case "shift-tab", "left" -> back();
            case "esc" -> {
                mode = Mode.DECLINE;
                message = "Decline this form? y/n";
            }
            case "c" -> startNote();
            case "enter" -> {
                switch (cursor) {
                    case 0 -> {
                        for (var i = 0; i < form.questions().size(); i++) {
                            var p = problem(i);
                            if (p.isPresent()) {
                                enterPage(i);
                                message = p.get();
                                return Optional.empty();
                            }
                        }
                        return Optional.of(build(FormAnswer.Status.ANSWERED, null));
                    }
                    case 1 -> enterPage(0);
                    default -> {
                        mode = Mode.DECLINE;
                        message = "Decline this form? y/n";
                    }
                }
            }
            default -> { }
        }
        return Optional.empty();
    }

    private FormAnswer build(FormAnswer.Status status, String noteText) {
        var answers = new ArrayList<QuestionAnswer>();
        for (var i = 0; i < form.questions().size(); i++) {
            var q = form.questions().get(i);
            var s = states.get(i);
            if (!answered(i)) continue;
            if (q.kind() == FormQuestion.Kind.TEXT) {
                answers.add(new QuestionAnswer(q.id(), List.of(), null, s.text.strip()));
            } else {
                var labels = new ArrayList<String>();
                for (var index : s.selected.stream().sorted().toList()) labels.add(q.options().get(index).label());
                answers.add(new QuestionAnswer(q.id(), labels, s.otherOn ? s.other.strip() : null, null));
            }
        }
        return new FormAnswer(status, answers, noteText);
    }

    // ---- rendering ----

    /** One line of the box body. {@code cursorAt} is the index in the text where the terminal cursor goes, or -1. */
    private record Line(AttributedString text, boolean focus, int cursorAt, int row) {
        Line(AttributedString text, boolean focus, int cursorAt) { this(text, focus, cursorAt, -1); }
    }

    /** The box lines, each at most {@code width} columns wide, and at most {@code maxRows} lines. */
    public List<AttributedString> render(int width, int maxRows) {
        var inner = Math.max(1, width - 4);
        var head = new ArrayList<Line>();
        var body = new ArrayList<Line>();
        var foot = new ArrayList<Line>();
        var total = form.questions().size();
        var title = form.title() == null || form.title().isBlank() ? "" : form.title() + "  ";
        head.add(plain(title + progress(), BOLD));
        head.add(plain(review() ? "Review " + agent + "'s form" : "Question " + (page + 1) + " of " + total
            + " (from " + agent + ")", DIM));
        if (review()) reviewBody(body, inner); else questionBody(body, inner);
        if (!message.isEmpty()) foot.add(plain(message, mode == Mode.NAV || mode == Mode.EDIT ? ERROR : DIM));
        foot.add(plain(hint(), DIM));

        var focusAt = 0;
        for (var i = 0; i < body.size(); i++) if (body.get(i).focus()) focusAt = i;
        var fixed = head.size() + foot.size() + 2;
        var room = Math.max(1, maxRows - fixed);
        var from = 0;
        var to = body.size();
        if (body.size() > room) {
            from = Math.max(0, Math.min(focusAt - room / 2, body.size() - room));
            to = from + room;
        }
        var shown = new ArrayList<Line>(head);
        var firstBody = shown.size();
        shown.addAll(body.subList(from, to));
        if (from > 0 && room > 1) shown.set(firstBody, plain("^ more", DIM));
        if (to < body.size() && room > 1) shown.set(shown.size() - 1, plain("v more", DIM));
        shown.addAll(foot);

        var out = new ArrayList<AttributedString>();
        var rowsOfLines = new ArrayList<Integer>();
        out.add(new AttributedString("+" + "-".repeat(inner + 2) + "+", BORDER));
        rowsOfLines.add(-1);
        cursorRow = -1;
        for (var line : shown) {
            var cut = cut(line.text(), inner);
            var sb = new AttributedStringBuilder();
            sb.append("| ", BORDER).append(cut).append(" ".repeat(Math.max(0, inner - cut.columnLength())))
                .append(" |", BORDER);
            if (line.cursorAt() >= 0) {
                cursorRow = out.size();
                cursorColumn = 2 + Math.min(inner, line.cursorAt());
            }
            out.add(sb.toAttributedString());
            rowsOfLines.add(line.row());
        }
        out.add(new AttributedString("+" + "-".repeat(inner + 2) + "+", BORDER));
        rowsOfLines.add(-1);
        lineRows = rowsOfLines;
        while (out.size() > Math.max(3, maxRows)) {
            out.remove(out.size() - 2);
            if (cursorRow >= out.size() - 1) cursorRow = -1;
        }
        return out;
    }

    private String progress() {
        var sb = new StringBuilder();
        for (var i = 0; i < form.questions().size(); i++) {
            sb.append(i == page ? "[>]" : answered(i) ? "[x]" : "[ ]");
        }
        sb.append(review() ? "[>]" : "[ ]").append(" review");
        return sb.toString();
    }

    private String hint() {
        if (mode == Mode.EDIT || mode == Mode.NOTE) return "Enter accepts. Esc goes back. Ctrl-U clears.";
        if (mode == Mode.DECLINE) return "y declines. Any other key goes back.";
        if (review()) return "Up/Down choose. Enter runs. Left goes back. c chat. Esc declines.";
        var multi = question().kind() == FormQuestion.Kind.MULTI;
        return "Up/Down move. " + (multi ? "Space toggles. " : "Space selects. ")
            + "Enter next. Tab skips. c chat. Esc declines.";
    }

    private void questionBody(List<Line> body, int inner) {
        var q = question();
        var s = state();
        for (var l : wrap(q.prompt(), inner)) body.add(plain(l, BOLD));
        if (q.kind() == FormQuestion.Kind.MULTI) {
            var limit = q.maxChoices() > 0 ? "choose " + q.minChoices() + " to " + q.maxChoices() : "choose any";
            body.add(plain("(" + limit + ")", DIM));
        }
        if (!q.required()) body.add(plain("(optional)", DIM));
        var rows = rows();
        for (var r = 0; r < rows.size(); r++) {
            var row = rows.get(r);
            var focus = r == cursor;
            var pointer = focus ? "> " : "  ";
            switch (row.type()) {
                case OPTION -> {
                    var on = s.selected.contains(row.option());
                    var mark = q.kind() == FormQuestion.Kind.SINGLE ? (on ? "(o) " : "( ) ") : (on ? "[x] " : "[ ] ");
                    var option = q.options().get(row.option());
                    var number = row.option() < 9 ? (row.option() + 1) + " " : "  ";
                    var lines = wrap(option.label(), Math.max(1, inner - 8));
                    for (var i = 0; i < lines.size(); i++) {
                        body.add(new Line(styled((i == 0 ? pointer + number + mark : " ".repeat(8)) + lines.get(i),
                            focus ? BOLD : AttributedStyle.DEFAULT), focus && i == 0, -1, r));
                    }
                    if (option.description() != null && !option.description().isBlank()) {
                        for (var l : wrap(option.description(), Math.max(1, inner - 10))) {
                            body.add(plain("          " + l, DIM));
                        }
                    }
                }
                case OTHER -> {
                    var on = s.otherOn;
                    var mark = q.kind() == FormQuestion.Kind.SINGLE ? (on ? "(o) " : "( ) ") : (on ? "[x] " : "[ ] ");
                    var editing = mode == Mode.EDIT && target == Target.OTHER;
                    var prefix = pointer + "  " + mark + "Other: ";
                    body.add(fieldLine(prefix, editing ? buffer.toString() : s.other, editing, focus, inner, r));
                }
                case TEXT -> {
                    var editing = mode == Mode.EDIT && target == Target.TEXT;
                    body.add(fieldLine(pointer + "> ", editing ? buffer.toString() : s.text, editing, focus, inner, r));
                }
                case CHAT -> {
                    body.add(new Line(styled(pointer + "  Chat about this", focus ? BOLD : DIM), focus, -1, r));
                    if (mode == Mode.NOTE) {
                        body.add(fieldLine("    Note: ", buffer.toString(), true, true, inner, -1));
                    }
                }
            }
        }
    }

    private Line fieldLine(String prefix, String text, boolean editing, boolean focus, int inner, int row) {
        if (!editing) {
            return new Line(styled(prefix + text, focus ? BOLD : AttributedStyle.DEFAULT), focus, -1, row);
        }
        var room = Math.max(1, inner - prefix.length() - 1);
        var cursorChars = text.codePointCount(0, Math.min(caret, text.length()));
        var cps = text.codePoints().toArray();
        var start = Math.max(0, cursorChars - room);
        var end = Math.min(cps.length, start + room);
        var shown = new String(cps, start, end - start);
        return new Line(styled(prefix + shown, AttributedStyle.DEFAULT), true,
            prefix.length() + (cursorChars - start), row);
    }

    private void reviewBody(List<Line> body, int inner) {
        for (var i = 0; i < form.questions().size(); i++) {
            var q = form.questions().get(i);
            var s = states.get(i);
            var value = answered(i) ? valueOf(q, s) : "(no answer)";
            var lines = wrap(q.prompt() + ": " + value, inner - 2);
            for (var l : lines) body.add(plain("  " + l, answered(i) ? AttributedStyle.DEFAULT : DIM));
        }
        body.add(plain("", DIM));
        for (var i = 0; i < REVIEW_ACTIONS.length; i++) {
            var focus = i == cursor;
            body.add(new Line(styled((focus ? "> " : "  ") + REVIEW_ACTIONS[i], focus ? BOLD : AttributedStyle.DEFAULT),
                focus, -1, i));
        }
    }

    private static String valueOf(FormQuestion q, State s) {
        if (q.kind() == FormQuestion.Kind.TEXT) return s.text.strip();
        var parts = new ArrayList<String>();
        for (var i : s.selected.stream().sorted().toList()) parts.add(q.options().get(i).label());
        if (s.otherOn) parts.add(s.other.strip());
        return String.join("; ", parts);
    }

    // ---- text helpers ----

    private static Line plain(String text, AttributedStyle style) {
        return new Line(styled(text, style), false, -1);
    }

    private static AttributedString styled(String text, AttributedStyle style) {
        return new AttributedString(text.replace('\n', ' ').replace('\t', ' '), style);
    }

    private static AttributedString cut(AttributedString s, int width) {
        if (s.columnLength() <= width) return s;
        if (width <= 1) return s.columnSubSequence(0, width);
        return new AttributedStringBuilder().append(s.columnSubSequence(0, width - 1)).append("…").toAttributedString();
    }

    /** Wraps a text at spaces. A word longer than the width is cut. */
    static List<String> wrap(String text, int width) {
        var w = Math.max(1, width);
        var out = new ArrayList<String>();
        for (var paragraph : text.split("\n", -1)) {
            var line = new StringBuilder();
            for (var word : paragraph.split(" +")) {
                while (word.codePointCount(0, word.length()) > w) {
                    if (!line.isEmpty()) {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                    var end = word.offsetByCodePoints(0, w);
                    out.add(word.substring(0, end));
                    word = word.substring(end);
                }
                var len = line.codePointCount(0, line.length());
                if (!line.isEmpty() && len + 1 + word.codePointCount(0, word.length()) > w) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            }
            out.add(line.toString());
        }
        return out;
    }
}
