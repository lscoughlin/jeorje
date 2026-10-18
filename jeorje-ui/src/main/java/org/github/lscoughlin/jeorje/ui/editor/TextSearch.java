package org.github.lscoughlin.jeorje.ui.editor;

/** Plain text search for Find and Change. */
final class TextSearch {

    private TextSearch() {
    }

    static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** True when the needle matches at a position under the options. */
    static boolean matchesAt(CharSequence text, int index, String needle, boolean matchCase, boolean wholeWord) {
        var n = needle.length();
        if (n == 0 || index < 0 || index + n > text.length()) {
            return false;
        }
        for (var i = 0; i < n; i++) {
            var a = text.charAt(index + i);
            var b = needle.charAt(i);
            if (a != b && (matchCase || Character.toLowerCase(a) != Character.toLowerCase(b))) {
                return false;
            }
        }
        if (wholeWord) {
            if (index > 0 && isWordChar(text.charAt(index - 1)) && isWordChar(needle.charAt(0))) {
                return false;
            }
            var end = index + n;
            if (end < text.length() && isWordChar(text.charAt(end)) && isWordChar(needle.charAt(n - 1))) {
                return false;
            }
        }
        return true;
    }

    /** The first match at or after {@code from}, or -1. */
    static int find(CharSequence text, String needle, int from, boolean matchCase, boolean wholeWord) {
        if (needle.isEmpty()) {
            return -1;
        }
        for (var i = Math.max(0, from); i + needle.length() <= text.length(); i++) {
            if (matchesAt(text, i, needle, matchCase, wholeWord)) {
                return i;
            }
        }
        return -1;
    }
}
