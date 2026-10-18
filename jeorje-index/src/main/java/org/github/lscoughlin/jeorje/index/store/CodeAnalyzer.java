package org.github.lscoughlin.jeorje.index.store;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.FlattenGraphFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/**
 * Analyzer for code and tool output. It splits camelCase, snake_case, and paths into parts, keeps the
 * original token, lowercases, and keeps stop words.
 */
public final class CodeAnalyzer extends Analyzer {
    private static final int FLAGS = WordDelimiterGraphFilter.GENERATE_WORD_PARTS
        | WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS
        | WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE
        | WordDelimiterGraphFilter.PRESERVE_ORIGINAL
        | WordDelimiterGraphFilter.STEM_ENGLISH_POSSESSIVE;

    private final boolean indexing;

    private CodeAnalyzer(boolean indexing) {
        this.indexing = indexing;
    }

    /** The analyzer for documents. Its token graph is flattened. */
    public static CodeAnalyzer forIndexing() {
        return new CodeAnalyzer(true);
    }

    /** The analyzer for queries. */
    public static CodeAnalyzer forQuery() {
        return new CodeAnalyzer(false);
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        var source = new StandardTokenizer();
        TokenStream stream = new WordDelimiterGraphFilter(source, FLAGS, null);
        if (indexing) stream = new FlattenGraphFilter(stream);
        stream = new LowerCaseFilter(stream);
        return new TokenStreamComponents(source, stream);
    }

    @Override
    protected TokenStream normalize(String fieldName, TokenStream in) {
        return new LowerCaseFilter(in);
    }
}
