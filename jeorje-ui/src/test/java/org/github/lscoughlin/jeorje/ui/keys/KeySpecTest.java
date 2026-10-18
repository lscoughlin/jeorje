package org.github.lscoughlin.jeorje.ui.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KeySpecTest {

    @Test
    void parsesCharactersAndNames() {
        assertEquals("a", KeySpec.parse("a").toString());
        assertEquals("/", KeySpec.parse("/").toString());
        assertEquals("Enter", KeySpec.parse("enter").toString());
        assertEquals("PgUp", KeySpec.parse("PGUP").toString());
        assertEquals("F10", KeySpec.parse("f10").toString());
        assertEquals("-", KeySpec.parse("-").toString());
    }

    @Test
    void parsesAllNamedKeysAndFunctionKeys() {
        for (var name : List.of("Enter", "Esc", "Tab", "Space", "Backspace", "Del", "Ins", "Home", "End", "PgUp",
                "PgDn", "Up", "Down", "Left", "Right")) {
            assertEquals(name, KeySpec.parse(name).toString());
        }
        for (var i = 1; i <= 12; i++) {
            assertEquals("F" + i, KeySpec.parse("F" + i).toString());
        }
        assertThrows(IllegalArgumentException.class, () -> KeySpec.parse("F13"));
        assertThrows(IllegalArgumentException.class, () -> KeySpec.parse("Bogus"));
    }

    @Test
    void parsesModifiersInAnyCase() {
        assertEquals("Ctrl-Shift-Left", KeySpec.parse("ctrl-shift-left").toString());
        assertEquals("Ctrl-Alt-Shift-Left", KeySpec.parse("Shift-Alt-Ctrl-Left").toString());
        assertEquals("Alt-Enter", KeySpec.parse("ALT-ENTER").toString());
    }

    @Test
    void letterCaseRules() {
        assertEquals("a", KeySpec.parse("a").toString());
        assertEquals("A", KeySpec.parse("A").toString());
        assertNotEquals(KeySpec.parse("a"), KeySpec.parse("A"));
        assertEquals(KeySpec.parse("Ctrl-x"), KeySpec.parse("Ctrl-X"));
        assertEquals(KeySpec.parse("Alt-f"), KeySpec.parse("Alt-F"));
        assertEquals(KeySpec.parse("Shift-a"), KeySpec.parse("A"));
    }

    private static void assertNotEquals(Object a, Object b) {
        assertFalse(a.equals(b));
    }

    @Test
    void parsesChords() {
        var chord = KeySpec.parse("Ctrl-X a");
        assertEquals(2, chord.keys().size());
        assertEquals("Ctrl-X a", chord.toString());
        assertEquals("Ctrl-X Ctrl-F", KeySpec.parse("ctrl-x   ctrl-f").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ctrl-X a", "Alt-Shift-a", "Ctrl-Shift-Left", "F5", "Ctrl-Alt-Del", "Alt-Enter",
            "Shift-Tab", "Ctrl-Space", "Ctrl-Q f", "A"})
    void printingRoundTrips(String text) {
        var spec = KeySpec.parse(text);
        assertEquals(spec, KeySpec.parse(spec.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ctrl-I", "ctrl-m", "Ctrl-[", "Ctrl-H", "Ctrl-X Ctrl-I"})
    void rejectsAliasForms(String text) {
        var e = assertThrows(IllegalArgumentException.class, () -> KeySpec.parse(text));
        assertTrue(e.getMessage().contains("Use '"), e.getMessage());
    }

    @Test
    void aliasErrorNamesTheKey() {
        assertTrue(message("Ctrl-I").contains("Tab"));
        assertTrue(message("Ctrl-M").contains("Enter"));
        assertTrue(message("Ctrl-[").contains("Esc"));
        assertTrue(message("Ctrl-H").contains("Backspace"));
    }

    private static String message(String key) {
        return assertThrows(IllegalArgumentException.class, () -> KeySpec.parse(key)).getMessage();
    }

    @Test
    void rejectsEmptyAndDuplicateModifiers() {
        assertThrows(IllegalArgumentException.class, () -> KeySpec.parse(""));
        assertThrows(IllegalArgumentException.class, () -> KeySpec.parse("  "));
        assertThrows(IllegalArgumentException.class, () -> KeySpec.parse("Ctrl-Ctrl-a"));
    }

    @Test
    void legacySequences() {
        assertEquals(List.of("\030a"), KeySpec.parse("Ctrl-X a").sequences(false));
        assertEquals(List.of("\r"), KeySpec.parse("Enter").sequences(false));
        assertEquals(List.of("\033\r"), KeySpec.parse("Alt-Enter").sequences(false));
        assertEquals(List.of("\003"), KeySpec.parse("Ctrl-C").sequences(false));
        assertEquals(List.of("\033f"), KeySpec.parse("Alt-F").sequences(false));
        assertEquals(List.of("\033[5~"), KeySpec.parse("PgUp").sequences(false));
        assertEquals(List.of("\033[2;5~"), KeySpec.parse("Ctrl-Ins").sequences(false));
        assertEquals(List.of("\033[3;2~"), KeySpec.parse("Shift-Del").sequences(false));
        assertEquals(List.of("\033[1;5D"), KeySpec.parse("Ctrl-Left").sequences(false));
        assertEquals(List.of("\033[Z"), KeySpec.parse("Shift-Tab").sequences(false));
        assertEquals(List.of("\033OP"), KeySpec.parse("F1").sequences(false));
        assertEquals(List.of("\033[21~"), KeySpec.parse("F10").sequences(false));
        assertTrue(KeySpec.parse("Left").sequences(false).contains("\033[D"));
    }

    @Test
    void kittySequencesAddExtraForms() {
        assertEquals(List.of("\033[13;3u"), KeySpec.parse("Alt-Enter").sequences(true).subList(1, 2));
        assertTrue(KeySpec.parse("Ctrl-X a").sequences(true).contains("\033[120;5ua"));
        assertTrue(KeySpec.parse("Esc").sequences(true).contains("\033[27u"));
    }

    @Test
    void keyWithoutLegacySequenceNeedsKitty() {
        var key = KeySpec.parse("Ctrl-Shift-a");
        assertTrue(key.sequences(false).isEmpty());
        assertEquals(List.of("\033[97;6u"), key.sequences(true));
        assertTrue(KeySpec.parse("Ctrl-Enter").sequences(false).isEmpty());
    }

    @Test
    void prefixTest() {
        assertTrue(KeySpec.parse("Ctrl-X").isPrefixOf(KeySpec.parse("Ctrl-X a")));
        assertFalse(KeySpec.parse("Ctrl-X a").isPrefixOf(KeySpec.parse("Ctrl-X")));
        assertFalse(KeySpec.parse("Ctrl-Q").isPrefixOf(KeySpec.parse("Ctrl-X a")));
    }
}
