package org.github.lscoughlin.jeorje.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HtmlToMarkdownTest {
    @Test
    void headingsAndParagraphs() {
        assertEquals("# One\n\n## Two\n\nText here.",
                HtmlToMarkdown.convert("<h1>One</h1><h2>Two</h2><p>Text\n  here.</p>"));
    }

    @Test
    void linksAndEmphasis() {
        assertEquals("See [docs](http://a/b) and **bold** and *it* and `code`.",
                HtmlToMarkdown.convert("<p>See <a href=\"http://a/b\">docs</a> and <b>bold</b> and <em>it</em> and <code>code</code>.</p>"));
    }

    @Test
    void lists() {
        assertEquals("- a\n- b\n  - c\n\n1. x\n2. y",
                HtmlToMarkdown.convert("<ul><li>a</li><li>b<ul><li>c</li></ul></li></ul><ol><li>x</li><li>y</li></ol>"));
    }

    @Test
    void codeBlockKeepsWhitespace() {
        assertEquals("```\nint a;\n  int b;\n```",
                HtmlToMarkdown.convert("<pre><code>int a;\n  int b;</code></pre>"));
    }

    @Test
    void tables() {
        assertEquals("| A | B |\n| --- | --- |\n| 1 | 2 |",
                HtmlToMarkdown.convert("<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>"));
    }

    @Test
    void dropsScriptsStylesAndComments() {
        assertEquals("Hi",
                HtmlToMarkdown.convert("<head><style>p{}</style></head><script>var a='<b>';</script><!-- c --><p>Hi</p>"));
    }

    @Test
    void decodesEntities() {
        assertEquals("a < b & c é", HtmlToMarkdown.convert("<p>a &lt; b &amp; c &#233;</p>"));
    }
}
