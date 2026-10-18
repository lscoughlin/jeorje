package org.github.lscoughlin.jeorje.ui.browser;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.RegionEvent;
import org.github.lscoughlin.jeorje.ui.screen.Pane;

/**
 * The file browser as a pane. Enter on a file, or a double click on a file, returns the path. Enter on a
 * directory opens or closes it. {@code Esc} cancels. In filter mode, {@code Esc} first clears the filter.
 */
public final class FileBrowserPane implements Pane<Path> {
    private final FileBrowser browser;
    private Path picked;

    public FileBrowserPane(FileBrowser browser) {
        this.browser = browser;
    }

    @Override
    public String title() {
        return "Files - Up/Down move, Right/Left open/close, Enter pick, / filter, . ignored, Esc cancel"
                + (browser.filter().isEmpty() ? "" : " [filter: " + browser.filter() + "]");
    }

    @Override
    public void begin() {
        picked = null;
        browser.onPick(path -> picked = path);
        browser.open();
    }

    @Override
    public void end() {
        browser.onPick(null);
        browser.close();
    }

    @Override
    public void resize(Rect bounds) { browser.setBounds(bounds); }

    @Override
    public List<String> render() { return browser.render(); }

    @Override
    public void key(String key) { browser.handleKey(key); }

    @Override
    public void mouse(RegionEvent event) { browser.onMouse(event); }

    @Override
    public boolean done() { return picked != null || !browser.isOpen(); }

    @Override
    public Optional<Path> result() { return Optional.ofNullable(picked); }
}
