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

    /** Runs before the first draw. */
    default void begin() {}

    /** Runs after the last draw. */
    default void end() {}
}
