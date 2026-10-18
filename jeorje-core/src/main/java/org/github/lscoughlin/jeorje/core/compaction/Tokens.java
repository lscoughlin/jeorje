package org.github.lscoughlin.jeorje.core.compaction;

import java.util.Set;
import java.util.regex.Pattern;

/** Token estimates and text patterns that the compaction code shares. */
final class Tokens {
    /** A file path with a directory part or a file extension. */
    static final Pattern PATH = Pattern.compile("(?:[\\w.~-]+/)+[\\w.-]+|\\b[\\w-]+\\.(?:java|xml|md|json|ya?ml|properties|txt|kt|py|js|ts|sh|toml|gradle)\\b");
    static final Pattern ERROR_WORD = Pattern.compile("(?i)\\b(error|exception|failed)\\b");
    static final Pattern NUMBER = Pattern.compile("\\d");
    static final Pattern WORD = Pattern.compile("\\S+");

    static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "and", "or", "but", "of", "to", "in", "on", "at",
        "by", "for", "with", "from", "as", "is", "are", "was", "were", "be", "been", "being", "it", "its", "this",
        "that", "these", "those", "then", "than", "so", "if", "do", "does", "did", "has", "have", "had", "will",
        "would", "can", "could", "should", "there", "their", "we", "i", "you", "he", "she", "they", "not", "no",
        "which", "who", "what", "when", "where", "also", "very", "just", "into", "about", "over", "under");

    private Tokens() {}

    /** Estimates the token count: one token for four characters. */
    static int estimate(String text) { return (text.length() + 3) / 4; }
}
