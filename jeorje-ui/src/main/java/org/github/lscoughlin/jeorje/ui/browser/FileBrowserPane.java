package org.github.lscoughlin.jeorje.ui.browser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.github.lscoughlin.jeorje.ui.keys.KeyBindings;
import org.github.lscoughlin.jeorje.ui.keys.KeyContext;
import org.github.lscoughlin.jeorje.ui.keys.KeyScope;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;
import org.github.lscoughlin.jeorje.ui.mouse.RegionEvent;
import org.github.lscoughlin.jeorje.ui.screen.Pane;

/**
 * The file browser as a pane. Enter on a file, or a double click on a file, returns the path. Enter on a
 * directory opens or closes it. {@code Esc} cancels. In filter mode, {@code Esc} first clears the filter.
 *
 * <p>A pane made with {@link KeyBindings} reads the {@code browser*} actions of the config. A pane made with no
 * bindings reads the fixed default keys.
 */
public final class FileBrowserPane implements Pane<Path> {
    private static final int RESERVED_ROWS = 1;
    private final FileBrowser browser;
    private final KeyBindings bindings;
    private Path picked;

    public FileBrowserPane(FileBrowser browser) {
        this(browser, null);
    }

    /**
     * @param browser the browser
     * @param bindings the effective key bindings, or null for the fixed default keys
     */
    public FileBrowserPane(FileBrowser browser, KeyBindings bindings) {
        this.browser = browser;
        this.bindings = bindings;
    }

    private String shown(String id, String fallback) {
        if (bindings == null) return fallback;
        var text = bindings.display(KeyScope.UI, id);
        return text.equals("none") ? "-" : text;
    }

    @Override
    public String title() {
        return "Files" + (browser.filter().isEmpty() ? "" : " [filter: " + browser.filter() + "]")
                + " - Up/Down move, " + shown("browserOpen", "Right") + "/" + shown("browserClose", "Left")
                + " open/close, " + shown("browserEdit", "Enter") + " pick, " + shown("browserFilter", "/")
                + " filter, " + shown("browserHidden", ".") + " hidden, " + shown("browserExit", "Esc") + " cancel";
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
    public void resize(Rect bounds) {
        browser.setReservedRows(RESERVED_ROWS);
        browser.setBounds(bounds);
    }

    @Override
    public List<String> render() {
        var tree = browser.render();
        var height = Math.max(1, browser.bounds().height());
        var rows = new ArrayList<>(tree.subList(0, Math.min(tree.size(), height - RESERVED_ROWS)));
        while (rows.size() < height - RESERVED_ROWS) rows.add("");
        rows.add(keysRow());
        return rows;
    }

    private String keysRow() {
        return "Up/Down move  " + shown("browserOpen", "Right") + "  " + shown("browserClose", "Left") + "  "
                + shown("browserEdit", "Enter") + "  " + shown("browserAdd", "a") + "  "
                + shown("browserFilter", "/") + "  " + shown("browserExit", "Esc");
    }

    @Override
    public KeyContext keyContext() { return bindings == null ? null : KeyContext.BROWSER; }

    @Override
    public boolean textInput() { return browser.isFiltering(); }

    @Override
    public void key(String key) {
        if (bindings == null || browser.isFiltering() || key.equals("UP") || key.equals("DOWN")) {
            browser.handleKey(key);
        }
    }

    @Override
    public void action(String actionId) {
        switch (actionId) {
            case "browserOpen" -> browser.right();
            case "browserClose" -> browser.left();
            case "browserEdit" -> browser.enter();
            case "browserAdd" -> browser.addSelected();
            case "browserFilter" -> browser.startFilter();
            case "browserHidden" -> browser.toggleHidden();
            case "browserExit" -> browser.close();
            default -> { }
        }
    }

    @Override
    public String toggleAction() { return bindings == null ? null : "files"; }

    @Override
    public void mouse(RegionEvent event) { browser.onMouse(event); }

    @Override
    public boolean done() { return picked != null || !browser.isOpen(); }

    @Override
    public Optional<Path> result() { return Optional.ofNullable(picked); }
}
