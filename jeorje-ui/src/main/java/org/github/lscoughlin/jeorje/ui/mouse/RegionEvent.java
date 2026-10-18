package org.github.lscoughlin.jeorje.ui.mouse;

import java.util.Set;
import org.jline.terminal.MouseEvent;

/**
 * A mouse event for one region. The coordinates are relative to the top left cell of the region. They can be
 * negative or too large when a drag leaves the region.
 *
 * @param clicks the click count (1, 2, or 3) of the current button press, or 0 for a wheel or a move
 */
public record RegionEvent(
        MouseEvent.Type type,
        MouseEvent.Button button,
        Set<MouseEvent.Modifier> modifiers,
        int x,
        int y,
        int clicks) {
    public RegionEvent {
        modifiers = Set.copyOf(modifiers);
    }

    public boolean shift() {
        return modifiers.contains(MouseEvent.Modifier.Shift);
    }

    public boolean isWheel() {
        return type == MouseEvent.Type.Wheel;
    }
}
