package org.github.lscoughlin.jeorje.index.embed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** BERT uncased WordPiece tokenizer in pure JDK code. */
public final class WordPieceTokenizer {

    private static final int MAX_WORD_CHARS = 100;

    private final Map<String, Integer> vocab;
    private final int unkId;
    private final int clsId;
    private final int sepId;

    public WordPieceTokenizer(List<String> vocabLines) {
        vocab = new HashMap<>();
        for (var i = 0; i < vocabLines.size(); i++) {
            vocab.putIfAbsent(vocabLines.get(i).strip(), i);
        }
        unkId = require("[UNK]");
        clsId = require("[CLS]");
        sepId = require("[SEP]");
    }

    public static WordPieceTokenizer load(Path vocabFile) throws IOException {
        return new WordPieceTokenizer(Files.readAllLines(vocabFile, StandardCharsets.UTF_8));
    }

    private int require(String token) {
        var id = vocab.get(token);
        if (id == null) {
            throw new IllegalArgumentException("Vocabulary has no " + token + " token.");
        }
        return id;
    }

    public int padId() {
        return vocab.getOrDefault("[PAD]", 0);
    }

    /** Returns the ids with [CLS] first and [SEP] last. The result has at most maxTokens ids. */
    public int[] encode(String text, int maxTokens) {
        var limit = Math.max(2, maxTokens) - 2;
        var ids = new ArrayList<Integer>();
        ids.add(clsId);
        var added = 0;
        outer:
        for (var word : basicTokens(text)) {
            for (var id : wordPieces(word)) {
                if (added >= limit) {
                    break outer;
                }
                ids.add(id);
                added++;
            }
        }
        ids.add(sepId);
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    List<String> basicTokens(String text) {
        var cleaned = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) {
                return;
            }
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                cleaned.append(' ');
            } else if (isCjk(cp)) {
                cleaned.append(' ').appendCodePoint(cp).append(' ');
            } else {
                cleaned.appendCodePoint(cp);
            }
        });
        var lower = cleaned.toString().toLowerCase(java.util.Locale.ROOT);
        var nfd = Normalizer.normalize(lower, Normalizer.Form.NFD);
        var tokens = new ArrayList<String>();
        var current = new StringBuilder();
        for (var i = 0; i < nfd.length(); ) {
            var cp = nfd.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.getType(cp) == Character.NON_SPACING_MARK) {
                continue;
            }
            if (cp == ' ') {
                flush(current, tokens);
            } else if (isPunctuation(cp)) {
                flush(current, tokens);
                tokens.add(new String(Character.toChars(cp)));
            } else {
                current.appendCodePoint(cp);
            }
        }
        flush(current, tokens);
        return tokens;
    }

    private static void flush(StringBuilder current, List<String> tokens) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private List<Integer> wordPieces(String word) {
        if (word.length() > MAX_WORD_CHARS) {
            return List.of(unkId);
        }
        var pieces = new ArrayList<Integer>();
        var start = 0;
        while (start < word.length()) {
            var end = word.length();
            Integer found = null;
            while (start < end) {
                var piece = word.substring(start, end);
                if (start > 0) {
                    piece = "##" + piece;
                }
                var id = vocab.get(piece);
                if (id != null) {
                    found = id;
                    break;
                }
                end--;
            }
            if (found == null) {
                return List.of(unkId);
            }
            pieces.add(found);
            start = end;
        }
        return pieces;
    }

    private static boolean isControl(int cp) {
        if (cp == '\t' || cp == '\n' || cp == '\r') {
            return false;
        }
        var type = Character.getType(cp);
        return type == Character.CONTROL || type == Character.FORMAT;
    }

    private static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION,
                    Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x20000 && cp <= 0x2A6DF) || (cp >= 0x2A700 && cp <= 0x2EBEF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }
}
