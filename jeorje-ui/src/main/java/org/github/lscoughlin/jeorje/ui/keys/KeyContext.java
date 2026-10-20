package org.github.lscoughlin.jeorje.ui.keys;

/**
 * A place where keys are active. Two actions conflict only when they are in the same context. The file browser
 * and the chat screen share a scope, but not a context, so both can use {@code Enter}.
 */
public enum KeyContext {
    CHAT(KeyScope.UI, "chat screen"),
    BROWSER(KeyScope.UI, "file browser"),
    FORM(KeyScope.UI, "form"),
    EDITOR(KeyScope.EDITOR, "editor");

    private final KeyScope scope;
    private final String title;

    KeyContext(KeyScope scope, String title) {
        this.scope = scope;
        this.title = title;
    }

    public KeyScope scope() {
        return scope;
    }

    public String title() {
        return title;
    }
}
