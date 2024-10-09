package io.github.m4gshm.connections.bytecode;

import lombok.Getter;

@Getter
public class UnresolvedParameterException extends UnresolvedResultException {
    private final int index;

    public UnresolvedParameterException(UnresolvedResultException e, int index) {
        super(e);
        this.index = index;
    }
}
