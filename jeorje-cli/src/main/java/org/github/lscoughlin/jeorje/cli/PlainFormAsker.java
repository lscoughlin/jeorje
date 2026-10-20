package org.github.lscoughlin.jeorje.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.github.lscoughlin.jeorje.core.form.Form;
import org.github.lscoughlin.jeorje.core.form.FormAnswer;
import org.github.lscoughlin.jeorje.core.form.FormAnswer.QuestionAnswer;
import org.github.lscoughlin.jeorje.core.form.FormQuestion;

/**
 * Asks a form as plain text: it prints each question and reads one answer line at a time. It needs no cursor
 * control, so it works on any terminal and on a pipe.
 *
 * <p>An empty line accepts the default or skips an optional question. {@code c} or {@code ?} chooses to chat
 * about the question. {@code q} declines the form.
 */
final class PlainFormAsker {
    private final BufferedReader in;
    private final PrintStream out;

    PlainFormAsker(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    /** Asks the form. An end of the input declines it. */
    FormAnswer ask(Form form) {
        var answers = new ArrayList<QuestionAnswer>();
        if (form.title() != null && !form.title().isBlank()) out.println(form.title());
        for (var q : form.questions()) {
            while (true) {
                show(q);
                var line = read();
                if (line == null || line.strip().equalsIgnoreCase("q")) return FormAnswer.declined();
                var text = line.strip();
                if (text.equalsIgnoreCase("c") || text.equals("?")) return chat(answers);
                if (text.isEmpty()) {
                    if (q.defaultValue() != null) text = q.defaultValue();
                    else if (!q.required()) break;
                    else {
                        out.println("This question needs an answer.");
                        continue;
                    }
                }
                try {
                    answers.add(FormAnswers.fromValue(q, text));
                    break;
                } catch (IllegalArgumentException e) {
                    out.println(e.getMessage());
                }
            }
        }
        out.println(summary(form, answers));
        while (true) {
            out.print("Submit? [Y/n/c] ");
            out.flush();
            var line = read();
            if (line == null) return FormAnswer.declined();
            switch (line.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "", "y", "yes" -> {
                    return new FormAnswer(FormAnswer.Status.ANSWERED, answers, null);
                }
                case "n", "no", "q" -> {
                    return FormAnswer.declined();
                }
                case "c", "?" -> {
                    return chat(answers);
                }
                default -> out.println("Answer y, n, or c.");
            }
        }
    }

    private FormAnswer chat(List<QuestionAnswer> answers) {
        out.print("Note for the agent (optional): ");
        out.flush();
        var note = read();
        return new FormAnswer(FormAnswer.Status.CHAT, answers, note == null || note.isBlank() ? null : note.strip());
    }

    private void show(FormQuestion q) {
        out.println(q.prompt() + (q.required() ? "" : " (optional)"));
        for (var i = 0; i < q.options().size(); i++) {
            var o = q.options().get(i);
            out.println("  " + (i + 1) + ". " + o.label()
                    + (o.description() == null || o.description().isBlank() ? "" : " - " + o.description()));
        }
        var how = switch (q.kind()) {
            case SINGLE -> "Number or label" + (q.allowOther() ? ", or your own answer" : "");
            case MULTI -> "Numbers or labels, separated by commas" + (q.allowOther() ? ", or your own" : "");
            case TEXT -> "Your answer";
        };
        out.print(how + " (c = chat, q = decline): ");
        out.flush();
    }

    private static String summary(Form form, List<QuestionAnswer> answers) {
        var sb = new StringBuilder("Your answers:");
        for (var q : form.questions()) {
            var a = answers.stream().filter(x -> x.id().equals(q.id())).findFirst();
            sb.append("\n- ").append(q.prompt()).append(": ").append(a.map(QuestionAnswer::value).orElse("(no answer)"));
        }
        return sb.toString();
    }

    private String read() {
        try {
            return in.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
