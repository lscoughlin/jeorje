package org.github.lscoughlin.jeorje.ui.screen;

import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.RegionEvent;

/**
 * A pane that takes the chat region, the keyboard, and the mouse until it has a result or the user cancels it.
 * {@link Screen#runPane} draws the pane and sends it the input.
 *
 * @param <T> the type of the result
 */
public interface Pane<T> {

    /** The title row of the pane. */
    String title();

    /** Tells the pane the area for its rows. The area is below the title row. */
    void resize(Rect bounds);

    /** The text rows for the area of the pane. */
    List<String> render();

    /** Handles a key. Key names are UP, DOWN, LEFT, RIGHT, ENTER, ESC, BACKSPACE, or a single character. */
    void key(String key);

    /** Handles a mouse event. The coordinates are relative to the area of the pane. */
    void mouse(RegionEvent event);

    /** Whether the pane has ended, with a result or by cancel. */
    boolean done();

    /** The result, or empty when the user cancelled. */
    Optional<T> result();

    /**
     * The key context whose action keys the screen reads for this pane, or null when the pane reads only the plain
     * key names. With a context, the screen turns the configured keys into {@link #action} calls.
     */
    default org.github.lscoughlin.jeorje.ui.keys.KeyContext keyContext() { return null; }

    /** Whether the pane reads typed text now. While it does, the screen sends every key as a key name. */
    default boolean textInput() { return false; }

    /** Handles the action of a configured key of the {@link #keyContext()}, for example {@code browserEdit}. */
    default void action(String actionId) {}

    /** The id of the chat action that closes this pane when the user presses its key again, or null. */
    default String toggleAction() { return null; }

    /** Runs before the first draw. */
    default void begin() {}

    /** Runs after the last draw. */
    default void end() {}
}
