package org.github.lscoughlin.jeorje.ui.editor;

import java.util.List;

/** The menu bar data. */
final class Menus {

    /**
     * One item of a menu.
     *
     * @param label the text, with the highlighted letter at index {@code hot}
     * @param hot the index of the highlighted letter
     * @param action the command id
     * @param checked null for an item with no check mark
     * @param enabled false for an item that cannot run now
     * @param help the one-line help for the status bar
     */
    record Item(String label, int hot, String action, Boolean checked, boolean enabled, String help) {
        Item(String label, int hot, String action, String help) {
            this(label, hot, action, null, true, help);
        }

        char hotChar() {
            return hot >= 0 && hot < label.length() ? Character.toLowerCase(label.charAt(hot)) : 0;
        }
    }

    /** One menu of the bar. */
    record Menu(String title, int hot, String openAction, List<Item> items) {
        char hotChar() {
            return Character.toLowerCase(title.charAt(hot));
        }
    }

    private Menus() {
    }
}
