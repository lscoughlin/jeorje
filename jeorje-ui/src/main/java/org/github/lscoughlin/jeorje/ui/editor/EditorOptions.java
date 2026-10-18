package org.github.lscoughlin.jeorje.ui.editor;

/**
 * The editor settings that the Options and View menus change.
 *
 * @param tabWidth the width of a tab stop
 * @param useSpaces true when the Tab key inserts spaces
 * @param lineNumbers true when the window shows line numbers
 * @param wrap true when long lines wrap
 * @param showWhitespace true when the window shows spaces and tabs
 * @param syntaxColors true when the window shows syntax colors
 * @param wheelLines the lines for each wheel step
 * @param noColor true when the palette uses no colors
 */
public record EditorOptions(int tabWidth, boolean useSpaces, boolean lineNumbers, boolean wrap,
                            boolean showWhitespace, boolean syntaxColors, int wheelLines, boolean noColor) {

    public EditorOptions {
        tabWidth = Math.clamp(tabWidth, 1, 16);
        wheelLines = Math.max(1, wheelLines);
    }

    public static EditorOptions defaults() {
        return new EditorOptions(4, true, false, false, false, true, 3, System.getenv("NO_COLOR") != null);
    }

    public EditorOptions withTabWidth(int value) {
        return new EditorOptions(value, useSpaces, lineNumbers, wrap, showWhitespace, syntaxColors, wheelLines,
                noColor);
    }

    public EditorOptions withUseSpaces(boolean value) {
        return new EditorOptions(tabWidth, value, lineNumbers, wrap, showWhitespace, syntaxColors, wheelLines,
                noColor);
    }

    public EditorOptions withLineNumbers(boolean value) {
        return new EditorOptions(tabWidth, useSpaces, value, wrap, showWhitespace, syntaxColors, wheelLines,
                noColor);
    }

    public EditorOptions withWrap(boolean value) {
        return new EditorOptions(tabWidth, useSpaces, lineNumbers, value, showWhitespace, syntaxColors, wheelLines,
                noColor);
    }

    public EditorOptions withShowWhitespace(boolean value) {
        return new EditorOptions(tabWidth, useSpaces, lineNumbers, wrap, value, syntaxColors, wheelLines, noColor);
    }

    public EditorOptions withSyntaxColors(boolean value) {
        return new EditorOptions(tabWidth, useSpaces, lineNumbers, wrap, showWhitespace, value, wheelLines,
                noColor);
    }

    public EditorOptions withWheelLines(int value) {
        return new EditorOptions(tabWidth, useSpaces, lineNumbers, wrap, showWhitespace, syntaxColors, value,
                noColor);
    }

    public EditorOptions withNoColor(boolean value) {
        return new EditorOptions(tabWidth, useSpaces, lineNumbers, wrap, showWhitespace, syntaxColors, wheelLines,
                value);
    }
}
