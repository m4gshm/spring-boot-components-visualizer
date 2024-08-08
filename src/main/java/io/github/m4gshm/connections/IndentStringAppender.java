package io.github.m4gshm.connections;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class IndentStringAppender {

    private final StringBuilder stringBuilder;

    private final String intend;

    private int level;

    public void addIndent() {
        level++;
    }

    public void removeIndent() {
        level--;
    }

    public IndentStringAppender append(CharSequence text) {
        stringBuilder.append(intend.repeat(level)).append(text);
        return this;
    }

}
