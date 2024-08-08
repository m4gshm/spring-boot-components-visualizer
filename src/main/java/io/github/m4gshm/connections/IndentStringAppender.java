package io.github.m4gshm.connections;

import lombok.RequiredArgsConstructor;

import java.util.StringTokenizer;

@RequiredArgsConstructor
public class IndentStringAppender {

    private final StringBuilder out;
    private final String intend;
    private final String lineBreak;

    public IndentStringAppender(StringBuilder out, String intend) {
        this(out, intend, "\n");
    }

    private int level;
    private boolean newLine;

    public void addIndent() {
        level++;
    }

    public void removeIndent() {
        level--;
    }

    public IndentStringAppender append(String text) {
        if (text != null && !text.isEmpty()) {
            var intends = intend.repeat(level);
            var tokenizer = new StringTokenizer(text, lineBreak, true);
            while (tokenizer.hasMoreTokens()) {
                var line = tokenizer.nextToken();
                if (newLine) {
                    out.append(intends);
                    newLine = false;
                }
                out.append(line);
                if (line.endsWith(lineBreak)) {
                    newLine = true;
                }
            }
        }
        return this;
    }
}