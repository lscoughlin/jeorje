package org.github.lscoughlin.jeorje.ui.screen;

/**
 * Receives the key actions of the chat screen. The action ids are the ones of the {@code chat} context:
 * {@code send}, {@code newline}, {@code interrupt}, {@code exit}, {@code agents}, {@code files},
 * {@code expandResult}, {@code redraw}, {@code scrollUp}, {@code scrollDown}, {@code follow}, and
 * {@code copySelection}. The screen runs the built-in effect of an action, such as the scroll, first.
 */
public interface ActionHandler {

    /** Runs after the key of an action. The caller can open a pane here with {@link Screen#runPane}. */
    void handle(String actionId);

    /** Runs when the user ends the session with the {@code exit} action. The terminal is already restored. */
    default void exit() {}

    /** A handler that does nothing. */
    ActionHandler NONE = actionId -> {};
}
