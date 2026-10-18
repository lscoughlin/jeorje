package org.github.lscoughlin.jeorje.ui.keys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One binding key: a chord of one or more keys. Example: {@code Ctrl-X a} is a chord of two keys.
 *
 * @param keys the keys of the chord in press order
 */
public record KeySpec(List<Key> keys) {

    public KeySpec {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("A key needs at least one key.");
        }
        keys = List.copyOf(keys);
    }

    /** One key with its modifiers. {@code base} is a canonical name (such as {@code Enter}) or one character. */
    public record Key(String base, boolean ctrl, boolean alt, boolean shift) {

        private static final Map<String, String> NAMES = new LinkedHashMap<>();
        // Final character of the xterm sequence for keys of the form ESC [ 1 ; m X.
        private static final Map<String, Character> LETTER_KEYS = Map.of(
                "Up", 'A', "Down", 'B', "Right", 'C', "Left", 'D', "Home", 'H', "End", 'F');
        private static final Map<String, Character> SS3_KEYS = Map.of("F1", 'P', "F2", 'Q', "F3", 'R', "F4", 'S');
        // Number n of the xterm sequence for keys of the form ESC [ n ; m ~.
        private static final Map<String, Integer> TILDE_KEYS = new LinkedHashMap<>();

        static {
            for (var name : List.of("Enter", "Esc", "Tab", "Space", "Backspace", "Del", "Ins", "Home", "End",
                    "PgUp", "PgDn", "Up", "Down", "Left", "Right")) {
                NAMES.put(name.toLowerCase(Locale.ROOT), name);
            }
            for (var i = 1; i <= 12; i++) {
                NAMES.put("f" + i, "F" + i);
            }
            TILDE_KEYS.putAll(Map.of("Ins", 2, "Del", 3, "PgUp", 5, "PgDn", 6));
            var fn = new int[] {15, 17, 18, 19, 20, 21, 23, 24};
            for (var i = 0; i < fn.length; i++) {
                TILDE_KEYS.put("F" + (i + 5), fn[i]);
            }
        }

        public boolean isNamed() {
            return base.codePointCount(0, base.length()) > 1;
        }

        private boolean isLetter() {
            return !isNamed() && Character.isLetter(base.codePointAt(0));
        }

        /** The xterm modifier number: 1 plus shift (1), alt (2), and ctrl (4). */
        private int modifierNumber() {
            return 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0);
        }

        private boolean modified() {
            return ctrl || alt || shift;
        }

        /** The text form of the key, for example {@code Ctrl-Shift-Left}. */
        @Override
        public String toString() {
            var sb = new StringBuilder();
            var showShift = shift;
            if (ctrl) {
                sb.append("Ctrl-");
            }
            if (alt) {
                sb.append("Alt-");
            }
            // Alt with a capital letter has no Shift flag, so the text form must say Shift.
            var capital = alt && !ctrl && isLetter() && !base.equals(base.toLowerCase(Locale.ROOT));
            if (showShift || capital) {
                sb.append("Shift-");
            }
            sb.append(isLetter() && (ctrl || alt) ? base.toUpperCase(Locale.ROOT) : base);
            return sb.toString();
        }

        /**
         * The byte sequences that a terminal without the Kitty protocol sends for this key. The list is empty when
         * such a terminal has no sequence for the key.
         */
        public List<String> legacySequences() {
            var out = new LinkedHashSet<String>();
            if (isNamed()) {
                namedSequences(out);
            } else {
                charSequences(out);
            }
            return List.copyOf(out);
        }

        /** The sequences that a terminal with the Kitty keyboard protocol (disambiguate mode) can send. */
        public List<String> kittySequences() {
            var out = new LinkedHashSet<String>();
            var code = kittyCode();
            if (code > 0 && (modified() || base.equals("Esc"))) {
                out.add("\033[" + code + (modified() ? ";" + modifierNumber() : "") + "u");
            }
            return List.copyOf(out);
        }

        private int kittyCode() {
            if (isNamed()) {
                return switch (base) {
                    case "Enter" -> 13;
                    case "Esc" -> 27;
                    case "Tab" -> 9;
                    case "Space" -> 32;
                    case "Backspace" -> 127;
                    default -> 0;
                };
            }
            return base.toLowerCase(Locale.ROOT).codePointAt(0);
        }

        private void namedSequences(java.util.Set<String> out) {
            var m = modifierNumber();
            var plain = !modified();
            switch (base) {
                case "Enter" -> {
                    if (plain) {
                        out.add("\r");
                    } else if (alt && !ctrl && !shift) {
                        out.add("\033\r");
                    }
                }
                case "Esc" -> {
                    if (plain) {
                        out.add("\033");
                    } else if (alt && !ctrl && !shift) {
                        out.add("\033\033");
                    }
                }
                case "Tab" -> {
                    if (plain) {
                        out.add("\t");
                    } else if (shift && !ctrl && !alt) {
                        out.add("\033[Z");
                    } else if (alt && !ctrl && !shift) {
                        out.add("\033\t");
                    }
                }
                case "Space" -> {
                    if (plain) {
                        out.add(" ");
                    } else if (ctrl && !alt && !shift) {
                        out.add("\0");
                    } else if (alt && !ctrl && !shift) {
                        out.add("\033 ");
                    }
                }
                case "Backspace" -> {
                    if (plain) {
                        out.add("\177");
                    } else if (alt && !ctrl && !shift) {
                        out.add("\033\177");
                    }
                }
                default -> {
                    var letter = LETTER_KEYS.get(base);
                    if (letter != null) {
                        if (plain) {
                            out.add("\033[" + letter);
                            out.add("\033O" + letter);
                            if (base.equals("Home")) {
                                out.add("\033[1~");
                            } else if (base.equals("End")) {
                                out.add("\033[4~");
                            }
                        } else {
                            out.add("\033[1;" + m + letter);
                        }
                        return;
                    }
                    var tilde = TILDE_KEYS.get(base);
                    if (tilde != null) {
                        out.add("\033[" + tilde + (plain ? "" : ";" + m) + "~");
                        return;
                    }
                    // F1 to F4 use the SS3 form without modifiers.
                    var ss3 = SS3_KEYS.get(base);
                    if (ss3 != null) {
                        out.add(plain ? "\033O" + ss3 : "\033[1;" + m + ss3);
                    }
                }
            }
        }

        private void charSequences(java.util.Set<String> out) {
            var c = base.codePointAt(0);
            var text = base;
            if (shift) {
                // Shift on a non-letter, or Ctrl with Shift, has no legacy form.
                return;
            }
            if (ctrl) {
                var code = controlCode(c);
                if (code < 0) {
                    return;
                }
                text = String.valueOf((char) code);
            }
            out.add(alt ? "\033" + text : text);
        }

        private static int controlCode(int c) {
            if (c >= 'a' && c <= 'z') {
                return c - 'a' + 1;
            }
            return switch (c) {
                case '@' -> 0;
                case '\\' -> 0x1c;
                case ']' -> 0x1d;
                case '^' -> 0x1e;
                case '_' -> 0x1f;
                default -> -1;
            };
        }
    }

    /** Parses a key in the notation of the configuration: for example {@code Ctrl-X a} or {@code Alt-Enter}. */
    public static KeySpec parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A key must not be empty. Use 'none' to remove all keys.");
        }
        var keys = new ArrayList<Key>();
        for (var token : text.trim().split("\\s+")) {
            keys.add(parseKey(token));
        }
        return new KeySpec(keys);
    }

    private static Key parseKey(String token) {
        var rest = token;
        boolean ctrl = false;
        boolean alt = false;
        boolean shift = false;
        while (true) {
            var lower = rest.toLowerCase(Locale.ROOT);
            if (lower.startsWith("ctrl-") && rest.length() > 5) {
                ctrl = checkOnce(ctrl, "Ctrl", token);
                rest = rest.substring(5);
            } else if (lower.startsWith("alt-") && rest.length() > 4) {
                alt = checkOnce(alt, "Alt", token);
                rest = rest.substring(4);
            } else if (lower.startsWith("shift-") && rest.length() > 6) {
                shift = checkOnce(shift, "Shift", token);
                rest = rest.substring(6);
            } else {
                break;
            }
        }
        if (rest.codePointCount(0, rest.length()) > 1) {
            var name = Key.NAMES.get(rest.toLowerCase(Locale.ROOT));
            if (name == null) {
                throw new IllegalArgumentException("Unknown key name '" + rest + "' in '" + token + "'.");
            }
            return new Key(name, ctrl, alt, shift);
        }
        var base = rest;
        var letter = Character.isLetter(base.codePointAt(0));
        if (letter && (ctrl || alt)) {
            // Letters are not case sensitive with Ctrl or Alt. Alt with Shift gives a capital letter.
            base = base.toLowerCase(Locale.ROOT);
            if (alt && !ctrl && shift) {
                base = base.toUpperCase(Locale.ROOT);
                shift = false;
            }
        } else if (letter && shift) {
            base = base.toUpperCase(Locale.ROOT);
            shift = false;
        }
        if (ctrl) {
            var alias = switch (base) {
                case "i" -> "Tab";
                case "m" -> "Enter";
                case "[" -> "Esc";
                case "h" -> "Backspace";
                default -> null;
            };
            if (alias != null) {
                var others = (alt ? "Alt-" : "") + (shift ? "Shift-" : "");
                throw new IllegalArgumentException("The key '" + token + "' is the same code as '" + others + alias
                        + "' in a terminal. Use '" + others + alias + "' instead.");
            }
        }
        return new Key(base, ctrl, alt, shift);
    }

    private static boolean checkOnce(boolean seen, String modifier, String token) {
        if (seen) {
            throw new IllegalArgumentException("The modifier " + modifier + " is twice in '" + token + "'.");
        }
        return true;
    }

    /** The text form of the chord. Parsing this text returns an equal key. */
    @Override
    public String toString() {
        return keys.stream().map(Key::toString).collect(Collectors.joining(" "));
    }

    /** Whether this chord is a shorter chord that starts a longer chord, or the two chords are equal. */
    public boolean isPrefixOf(KeySpec other) {
        return keys.size() <= other.keys.size() && other.keys.subList(0, keys.size()).equals(keys);
    }

    /**
     * All full byte sequences for the chord. A chord with several key forms gives the product of the forms.
     *
     * @param kitty whether to include the forms of the Kitty keyboard protocol
     */
    public List<String> sequences(boolean kitty) {
        List<String> acc = List.of("");
        for (var key : keys) {
            var forms = new ArrayList<>(key.legacySequences());
            if (kitty) {
                forms.addAll(key.kittySequences());
            }
            var next = new ArrayList<String>();
            for (var prefix : acc) {
                for (var form : forms) {
                    next.add(prefix + form);
                }
            }
            acc = next;
        }
        return acc;
    }
}
