package org.github.lscoughlin.jeorje.ui.editor;

import org.github.lscoughlin.jeorje.ui.mouse.Clipboard;

/** The clipboard of the editor. */
public interface EditorClipboard {

    /** Copies text. Returns a short status text that says where the text went. */
    String copy(String text);

    /** The text that a paste inserts. It is empty when nothing was copied. */
    String paste();

    /** A clipboard that sends text to the terminal with OSC 52 and keeps an internal copy. */
    static EditorClipboard of(Clipboard clipboard) {
        return new EditorClipboard() {
            @Override
            public String copy(String text) {
                return clipboard.copy(text).status();
            }

            @Override
            public String paste() {
                return clipboard.internalText();
            }
        };
    }

    /** A clipboard that only keeps the text in memory. */
    static EditorClipboard memory() {
        return new EditorClipboard() {
            private String text = "";

            @Override
            public String copy(String value) {
                text = value;
                return "Copied to the internal clipboard.";
            }

            @Override
            public String paste() {
                return text;
            }
        };
    }
}
