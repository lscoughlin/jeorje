package org.github.lscoughlin.jeorje.ui.mouse;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.github.lscoughlin.jeorje.ui.screen.ChatView;
import org.jline.terminal.MouseEvent;

/**
 * The mouse regions of the interactive chat view: the text area, the scroll bar, and the follow notice. The
 * regions work on the {@link ChatView} of the foreground agent. Their rectangles come from suppliers, because the
 * chat area changes with the size of the terminal, the input line, and the permission box.
 */
public final class ChatMouse {

    private final Supplier<ChatView> chat;
    private final Clipboard clipboard;
    private final MouseConfig config;
    private final Consumer<String> status;
    private final Consumer<String> openPath;
    private final Runnable redraw;
    private final TextRegion text;
    private final BarRegion bar;
    private final NoticeRegion notice;

    /**
     * @param chat the chat view of the foreground agent
     * @param textArea the rectangle of the text area (without the scroll bar)
     * @param barArea the rectangle of the scroll bar, or an empty rectangle
     * @param noticeArea the rectangle of the follow notice, or an empty rectangle
     * @param clipboard receives the copied text
     * @param status receives a short message for the status line
     * @param openPath receives a file path that the user clicked
     * @param redraw draws the screen again after a change
     */
    public ChatMouse(
            Supplier<ChatView> chat,
            Supplier<Rect> textArea,
            Supplier<Rect> barArea,
            Supplier<Rect> noticeArea,
            Clipboard clipboard,
            MouseConfig config,
            Consumer<String> status,
            Consumer<String> openPath,
            Runnable redraw) {
        this.chat = chat;
        this.clipboard = clipboard;
        this.config = config;
        this.status = status;
        this.openPath = openPath;
        this.redraw = redraw;
        this.text = new TextRegion(textArea);
        this.bar = new BarRegion(barArea);
        this.notice = new NoticeRegion(noticeArea);
    }

    /** The regions, in the order of registration. */
    public List<Region> regions() {
        return List.of(text, bar, notice);
    }

    /** Copies the selection of the chat view (the {@code copySelection} action). Returns the copied text. */
    public String copySelection() {
        var selected = chat.get().selectedText();
        if (!selected.isEmpty()) status.accept(clipboard.copy(selected).status());
        return selected;
    }

    /** The text area region. */
    private final class TextRegion implements Region {
        private final Supplier<Rect> area;
        private boolean dragged;
        private boolean selecting;
        private int pressLine;
        private int pressCol;

        TextRegion(Supplier<Rect> area) { this.area = area; }

        @Override
        public Rect bounds() { return area.get(); }

        @Override
        public void onMouse(RegionEvent e) {
            var view = chat.get();
            switch (e.type()) {
                case Wheel -> {
                    var lines = e.shift() ? config.wheelLines() * 3 : config.wheelLines();
                    switch (e.button()) {
                        case WheelUp -> view.scrollUp(lines);
                        case WheelDown -> view.scrollDown(lines);
                        default -> { return; }
                    }
                    redraw.run();
                }
                case Pressed -> press(view, e);
                case Dragged -> drag(view, e);
                case Released -> release(view, e);
                default -> { }
            }
        }

        private void press(ChatView view, RegionEvent e) {
            if (e.button() != MouseEvent.Button.Button1) return;
            dragged = false;
            selecting = false;
            var line = view.lineAtRow(e.y());
            var col = Math.max(0, e.x());
            pressLine = line;
            pressCol = col;
            if (e.clicks() < 2) return;
            var plain = view.wrappedText(line);
            if (e.clicks() == 2) {
                var range = ChatRegion.wordAt(plain, col);
                view.selection().select(line, range[0], range[1]);
            } else {
                view.selection().select(line, 0, Math.max(0, plain.length() - 1));
            }
            dragged = true;
            redraw.run();
        }

        private void drag(ChatView view, RegionEvent e) {
            if (e.clicks() >= 2) return;
            if (!selecting) {
                view.selection().start(pressLine, pressCol);
                selecting = true;
            }
            dragged = true;
            view.selection().extend(view.lineAtRow(e.y()), Math.max(0, e.x()));
            redraw.run();
        }

        private void release(ChatView view, RegionEvent e) {
            if (!dragged && e.clicks() <= 1) {
                if (view.selection().isActive()) {
                    view.selection().clear();
                    redraw.run();
                }
                click(view, e);
                return;
            }
            if (config.copyOnSelect() && view.selection().isActive()) copySelection();
        }

        private void click(ChatView view, RegionEvent e) {
            if (e.button() != MouseEvent.Button.Button1) return;
            var ref = view.refAtRow(e.y(), Math.max(0, e.x()));
            if (ref == null) return;
            var collapsedResult = ref.text().stripLeading().startsWith("\u23bf");
            if (!collapsedResult) {
                var path = LineClick.FilePaths.pathAt(ref.text(), ref.column());
                if (path.isPresent()) {
                    openPath.accept(path.get());
                    return;
                }
            }
            if (ref.togglesResult() && ref.toolUseId() != null && view.toggleResult(ref.toolUseId())) redraw.run();
        }
    }

    /** The scroll bar region. */
    private final class BarRegion implements Region {
        private final Supplier<Rect> area;
        private ScrollBar bar;
        private Rect barRect;
        private ScrollModel barModel;

        BarRegion(Supplier<Rect> area) { this.area = area; }

        @Override
        public Rect bounds() { return area.get(); }

        @Override
        public void onMouse(RegionEvent e) {
            var view = chat.get();
            var model = view.barModel();
            var rect = area.get();
            if (model == null || rect.width() == 0) return;
            if (bar == null || !rect.equals(barRect) || model != barModel) {
                bar = new ScrollBar(rect, model);
                barRect = rect;
                barModel = model;
            }
            bar.onMouse(e);
            view.syncFromBar();
            redraw.run();
        }
    }

    /** The follow notice region. A click on the notice follows the new lines. */
    private final class NoticeRegion implements Region {
        private final Supplier<Rect> area;

        NoticeRegion(Supplier<Rect> area) { this.area = area; }

        @Override
        public Rect bounds() { return area.get(); }

        @Override
        public void onMouse(RegionEvent e) {
            if (e.type() == MouseEvent.Type.Pressed && e.button() == MouseEvent.Button.Button1) {
                chat.get().scrollToEnd();
                redraw.run();
            }
        }
    }
}
