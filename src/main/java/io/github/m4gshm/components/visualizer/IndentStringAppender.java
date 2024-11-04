package io.github.m4gshm.components.visualizer;

import lombok.RequiredArgsConstructor;
import lombok.var;

import java.util.StringTokenizer;

import static org.apache.commons.lang3.StringUtils.repeat;

@RequiredArgsConstructor
public class IndentStringAppender {

    private final StringBuilder out;
    private final String intend;
    private final String lineBreak;
    private int level;
    private boolean newLine;

    public IndentStringAppender(StringBuilder out, String intend) {
        this(out, intend, "\n");
    }

    public void addIndent() {
        level++;
    }

    public void removeIndent() {
        level--;
    }


    public IndentStringAppender append(String text, boolean splitLines) {
        if (text != null && !text.isEmpty()) {
            var intends = repeat(intend, level);
            if (splitLines) {
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
            } else {
                if (newLine) {
                    out.append(intends);
                    newLine = false;
                }
                out.append(text);
                if (text.endsWith(lineBreak)) {
                    newLine = true;
                }
            }
        }
        return this;
    }

    public IndentStringAppender append(String text) {
        return append(text, true);
    }

    @Override
    public String toString() {
        return out.toString();
    }
}