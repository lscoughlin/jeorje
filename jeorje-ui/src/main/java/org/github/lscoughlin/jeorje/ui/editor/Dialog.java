package org.github.lscoughlin.jeorje.ui.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.jeorje.ui.mouse.Rect;

/**
 * A box in the middle of the screen with text fields, check boxes, a list, and buttons. {@code Tab} moves between
 * the parts. The dialog does not read keys itself. The editor sends the keys to the methods of the dialog.
 */
final class Dialog {

    /** A button. The button with {@code cancel} set also takes {@code Esc}. */
    record Button(String id, String label, boolean isDefault, boolean cancel, boolean keepOpen) {
        static Button okButton(String id, String label) {
            return new Button(id, label, true, false, false);
        }

        static Button cancelButton() {
            return new Button("cancel", "Cancel", false, true, false);
        }

        static Button plainButton(String id, String label) {
            return new Button(id, label, false, false, false);
        }

        Button staysOpen() {
            return new Button(id, label, isDefault, cancel, true);
        }
    }

    /** A line of text that the user edits. */
    static final class Field {
        final String id;
        final String label;
        final StringBuilder value;
        int cursor;
        int scroll;
        int x;
        int y;
        int width;

        Field(String id, String label, String initial) {
            this.id = id;
            this.label = label;
            this.value = new StringBuilder(initial);
            this.cursor = initial.length();
        }
    }

    /** A check box. */
    static final class Check {
        final String id;
        final String label;
        boolean on;
        int x;
        int y;

        Check(String id, String label, boolean on) {
            this.id = id;
            this.label = label;
            this.on = on;
        }
    }

    /** The values of a dialog when the user presses a button. */
    record Result(String button, Map<String, String> values, Map<String, Boolean> checks, int selected) { }

    /** What is at a cell of the dialog. */
    enum Part { NONE, FIELD, CHECK, LIST, BUTTON, OUTSIDE }

    /** A hit test result. {@code index} is the field, check box, list row, or button. */
    record Hit(Part part, int index) { }

    /** Decides what happens when the user presses a button. It returns true when the dialog must close. */
    interface Handler {
        boolean handle(Result result);
    }

    final String title;
    final List<String> message = new ArrayList<>();
    final List<Field> fields = new ArrayList<>();
    final List<Check> checks = new ArrayList<>();
    final List<Button> buttons = new ArrayList<>();
    final Handler handler;
    List<String> items = List.of();
    int selected;
    int listTop;
    int listRows;
    int focus;
    Rect rect = new Rect(0, 0, 0, 0);
    private int listX;
    private int listY;
    private int listW;
    private final List<Rect> buttonRects = new ArrayList<>();
    private int listScrollbarX;

    Dialog(String title, Handler handler) {
        this.title = title;
        this.handler = handler;
    }

    Dialog message(String... lines) {
        message.addAll(List.of(lines));
        return this;
    }

    Dialog field(String id, String label, String initial) {
        fields.add(new Field(id, label, initial));
        return this;
    }

    Dialog check(String id, String label, boolean on) {
        checks.add(new Check(id, label, on));
        return this;
    }

    Dialog list(List<String> entries, int selectedIndex) {
        items = List.copyOf(entries);
        selected = Math.clamp(selectedIndex, 0, Math.max(0, items.size() - 1));
        listRows = Math.min(8, Math.max(1, items.size()));
        return this;
    }

    Dialog button(Button button) {
        buttons.add(button);
        return this;
    }

    private int parts() {
        return fields.size() + checks.size() + (items.isEmpty() ? 0 : 1) + buttons.size();
    }

    private int firstButton() {
        return fields.size() + checks.size() + (items.isEmpty() ? 0 : 1);
    }

    Field focusedField() {
        return focus < fields.size() ? fields.get(focus) : null;
    }

    boolean listFocused() {
        return !items.isEmpty() && focus == fields.size() + checks.size();
    }

    int focusedButton() {
        return focus >= firstButton() ? focus - firstButton() : -1;
    }

    void next() {
        focus = (focus + 1) % Math.max(1, parts());
    }

    void previous() {
        focus = (focus - 1 + Math.max(1, parts())) % Math.max(1, parts());
    }

    /** Moves the focus to the first button that is the default, so that a later Enter presses it. */
    void focusDefault() {
        for (var i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).isDefault()) {
                focus = firstButton() + i;
                return;
            }
        }
    }

    Button defaultButton() {
        return buttons.stream().filter(Button::isDefault).findFirst().orElse(buttons.isEmpty() ? null : buttons.get(0));
    }

    Button cancelButton() {
        return buttons.stream().filter(Button::cancel).findFirst().orElse(null);
    }

    Result result(Button button) {
        var values = new LinkedHashMap<String, String>();
        fields.forEach(f -> values.put(f.id, f.value.toString()));
        var flags = new LinkedHashMap<String, Boolean>();
        checks.forEach(c -> flags.put(c.id, c.on));
        return new Result(button.id(), values, flags, selected);
    }

    /** Inserts text in the focused field. */
    void type(String text) {
        var field = focusedField();
        if (field == null) {
            return;
        }
        var clean = text.replace("\r\n", "\n").replace('\n', ' ').replace("\t", " ");
        field.value.insert(field.cursor, clean);
        field.cursor += clean.length();
    }

    void backspace() {
        var field = focusedField();
        if (field != null && field.cursor > 0) {
            field.value.deleteCharAt(--field.cursor);
        }
    }

    void delete() {
        var field = focusedField();
        if (field != null && field.cursor < field.value.length()) {
            field.value.deleteCharAt(field.cursor);
        }
    }

    void left() {
        var field = focusedField();
        if (field != null) {
            field.cursor = Math.max(0, field.cursor - 1);
        } else if (focusedButton() > 0) {
            focus--;
        }
    }

    void right() {
        var field = focusedField();
        if (field != null) {
            field.cursor = Math.min(field.value.length(), field.cursor + 1);
        } else if (focusedButton() >= 0 && focusedButton() < buttons.size() - 1) {
            focus++;
        }
    }

    void home() {
        var field = focusedField();
        if (field != null) {
            field.cursor = 0;
        } else if (listFocused()) {
            selectRow(0);
        }
    }

    void end() {
        var field = focusedField();
        if (field != null) {
            field.cursor = field.value.length();
        } else if (listFocused()) {
            selectRow(items.size() - 1);
        }
    }

    void up() {
        if (listFocused()) {
            selectRow(selected - 1);
        } else {
            previous();
        }
    }

    void down() {
        if (listFocused()) {
            selectRow(selected + 1);
        } else {
            next();
        }
    }

    void selectRow(int row) {
        if (items.isEmpty()) {
            return;
        }
        selected = Math.clamp(row, 0, items.size() - 1);
        if (selected < listTop) {
            listTop = selected;
        } else if (selected >= listTop + listRows) {
            listTop = selected - listRows + 1;
        }
    }

    /** Selects the next row whose text starts with the char. */
    void typeAhead(char c) {
        var lower = Character.toLowerCase(c);
        for (var k = 1; k <= items.size(); k++) {
            var i = (selected + k) % items.size();
            var item = items.get(i);
            if (!item.isEmpty() && Character.toLowerCase(item.charAt(0)) == lower) {
                selectRow(i);
                return;
            }
        }
    }

    /** Scrolls the list. The selected row does not change. */
    void scrollList(int rows) {
        listTop = Math.clamp(listTop + rows, 0, Math.max(0, items.size() - listRows));
    }

    /** Toggles the focused check box. Returns false when the focus is not on a check box. */
    boolean toggle() {
        var index = focus - fields.size();
        if (index >= 0 && index < checks.size()) {
            checks.get(index).on = !checks.get(index).on;
            return true;
        }
        return false;
    }

    /** Sets the size and the position of the dialog on a screen. */
    void layout(int screenWidth, int screenHeight) {
        var labelWidth = fields.stream().mapToInt(f -> f.label.length()).max().orElse(0);
        var inner = Math.max(36, title.length() + 4);
        for (var line : message) {
            inner = Math.max(inner, line.length() + 2);
        }
        if (!fields.isEmpty()) {
            inner = Math.max(inner, labelWidth + 24);
        }
        for (var c : checks) {
            inner = Math.max(inner, c.label.length() + 6);
        }
        for (var item : items) {
            inner = Math.max(inner, item.length() + 6);
        }
        var buttonsWidth = buttons.stream().mapToInt(b -> b.label().length() + 4).sum() + 2 * Math.max(0, buttons.size() - 1);
        inner = Math.max(inner, buttonsWidth + 2);
        inner = Math.min(inner, Math.max(10, screenWidth - 4));
        var height = 2 + message.size() + (message.isEmpty() ? 0 : 1) + fields.size() + checks.size()
                + (items.isEmpty() ? 0 : listRows) + 1 + 1;
        var width = inner + 2;
        var x = Math.max(0, (screenWidth - width) / 2);
        var y = Math.max(0, (screenHeight - height) / 2);
        rect = new Rect(x, y, Math.min(width, screenWidth), Math.min(height, screenHeight));
        var row = y + 1 + message.size() + (message.isEmpty() ? 0 : 1);
        for (var f : fields) {
            f.x = x + 2 + labelWidth + 1;
            f.y = row++;
            f.width = Math.max(4, inner - labelWidth - 4);
        }
        for (var c : checks) {
            c.x = x + 2;
            c.y = row++;
        }
        if (!items.isEmpty()) {
            listX = x + 2;
            listY = row;
            listW = inner - 3;
            listScrollbarX = x + width - 2;
            row += listRows;
        }
        row++;
        buttonRects.clear();
        var cursor = x + (width - buttonsWidth) / 2;
        for (var b : buttons) {
            var w = b.label().length() + 4;
            buttonRects.add(new Rect(cursor, row, w, 1));
            cursor += w + 2;
        }
    }

    /** What is at a screen cell. */
    Hit hit(int x, int y) {
        if (!rect.contains(x, y)) {
            return new Hit(Part.OUTSIDE, -1);
        }
        for (var i = 0; i < fields.size(); i++) {
            var f = fields.get(i);
            if (y == f.y && x >= f.x && x < f.x + f.width) {
                return new Hit(Part.FIELD, i);
            }
        }
        for (var i = 0; i < checks.size(); i++) {
            var c = checks.get(i);
            if (y == c.y && x >= c.x && x < c.x + 4 + c.label.length()) {
                return new Hit(Part.CHECK, i);
            }
        }
        if (!items.isEmpty() && y >= listY && y < listY + listRows && x >= listX && x < listX + listW) {
            var row = listTop + (y - listY);
            return row < items.size() ? new Hit(Part.LIST, row) : new Hit(Part.NONE, -1);
        }
        for (var i = 0; i < buttonRects.size(); i++) {
            if (buttonRects.get(i).contains(x, y)) {
                return new Hit(Part.BUTTON, i);
            }
        }
        return new Hit(Part.NONE, -1);
    }

    /** Puts the cursor of a field at the cell of a click. */
    void clickField(int index, int x) {
        var f = fields.get(index);
        focus = index;
        f.cursor = Math.clamp(f.scroll + (x - f.x), 0, f.value.length());
    }

    void clickCheck(int index) {
        focus = fields.size() + index;
        checks.get(index).on = !checks.get(index).on;
    }

    void clickList(int row) {
        focus = fields.size() + checks.size();
        selectRow(row);
    }

    /** Draws the dialog. */
    void draw(Canvas canvas, Palette palette) {
        var x = rect.x();
        var y = rect.y();
        canvas.fill(x, y, rect.width(), rect.height(), ' ', palette.dialog());
        canvas.box(x, y, rect.width(), rect.height(), palette.dialog());
        var shown = " " + title + " ";
        canvas.put(x + Math.max(1, (rect.width() - shown.length()) / 2), y, shown, palette.title());
        var row = y + 1;
        for (var line : message) {
            canvas.put(x + 2, row++, line, palette.dialog());
        }
        for (var i = 0; i < fields.size(); i++) {
            var f = fields.get(i);
            canvas.put(x + 2, f.y, f.label, palette.dialog());
            if (f.cursor < f.scroll) {
                f.scroll = f.cursor;
            } else if (f.cursor >= f.scroll + f.width) {
                f.scroll = f.cursor - f.width + 1;
            }
            canvas.fill(f.x, f.y, f.width, 1, ' ', palette.field());
            var text = f.value.substring(Math.min(f.scroll, f.value.length()));
            canvas.put(f.x, f.y, text.length() > f.width ? text.substring(0, f.width) : text, palette.field());
        }
        for (var i = 0; i < checks.size(); i++) {
            var c = checks.get(i);
            var style = focus == fields.size() + i ? palette.buttonFocus() : palette.dialog();
            canvas.put(c.x, c.y, (c.on ? "[x] " : "[ ] ") + c.label, style);
        }
        if (!items.isEmpty()) {
            for (var r = 0; r < listRows; r++) {
                var index = listTop + r;
                canvas.fill(listX, listY + r, listW, 1, ' ', palette.field());
                if (index < items.size()) {
                    var style = index == selected ? palette.menuSelected() : palette.field();
                    canvas.fill(listX, listY + r, listW, 1, ' ', style);
                    var item = items.get(index);
                    canvas.put(listX, listY + r, item.length() > listW ? item.substring(0, listW) : item, style);
                }
            }
            var model = new org.github.lscoughlin.jeorje.ui.mouse.ScrollModel(1);
            model.setContent(items.size(), listRows);
            model.scrollTo(listTop);
            var bar = new org.github.lscoughlin.jeorje.ui.mouse.ScrollBar(new Rect(listScrollbarX, listY, 1, listRows), model);
            var rows = bar.render();
            for (var r = 0; r < rows.size(); r++) {
                canvas.put(listScrollbarX, listY + r, rows.get(r), palette.scrollBar());
            }
        }
        for (var i = 0; i < buttons.size(); i++) {
            var r = buttonRects.get(i);
            var focused = focus == firstButton() + i;
            var b = buttons.get(i);
            canvas.put(r.x(), r.y(), "< " + b.label() + " >", focused ? palette.buttonFocus() : palette.button());
        }
        var cursorField = focusedField();
        if (cursorField != null) {
            cursorX = cursorField.x + cursorField.cursor - cursorField.scroll;
            cursorY = cursorField.y;
        } else {
            cursorX = -1;
            cursorY = -1;
        }
    }

    int cursorX = -1;
    int cursorY = -1;
}
