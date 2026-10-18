package org.github.lscoughlin.jeorje.ui.mouse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jline.terminal.MouseEvent;

/**
 * Keeps the screen rectangles of the visible regions. An event goes to the region under the pointer. A drag stays
 * with the region where the button went down, until the button is released. A region that is registered later
 * lies on top of an earlier region.
 */
public final class RegionRegistry {
    private final List<Region> regions = new CopyOnWriteArrayList<>();
    private Region captured;

    public void register(Region region) {
        regions.remove(region);
        regions.add(region);
    }

    public void unregister(Region region) {
        regions.remove(region);
        synchronized (this) {
            if (captured == region) captured = null;
        }
    }

    /** The topmost region at a cell. */
    public Optional<Region> regionAt(int x, int y) {
        var all = new ArrayList<>(regions);
        for (var i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).bounds().contains(x, y)) return Optional.of(all.get(i));
        }
        return Optional.empty();
    }

    /** The region that owns the current drag, if a button is down. */
    public synchronized Optional<Region> capturedRegion() {
        return Optional.ofNullable(captured);
    }

    /** Sends an event to the right region. Returns the region that got it. */
    public Optional<Region> dispatch(MouseEvent event, int clicks) {
        Region target;
        synchronized (this) {
            var hit = regionAt(event.getX(), event.getY()).orElse(null);
            switch (event.getType()) {
                case Pressed -> {
                    captured = hit;
                    target = hit;
                }
                case Dragged -> target = captured != null ? captured : hit;
                case Released -> {
                    target = captured != null ? captured : hit;
                    captured = null;
                }
                default -> target = hit;
            }
        }
        if (target == null) return Optional.empty();
        var b = target.bounds();
        target.onMouse(new RegionEvent(
                event.getType(),
                event.getButton(),
                event.getModifiers(),
                event.getX() - b.x(),
                event.getY() - b.y(),
                clicks));
        return Optional.of(target);
    }
}
