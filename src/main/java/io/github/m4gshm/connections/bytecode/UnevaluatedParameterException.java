package io.github.m4gshm.connections.bytecode;

import lombok.Getter;

@Getter
public class UnevaluatedParameterException extends UnevaluatedResultException {

    private final int index;

    public UnevaluatedParameterException(UnevaluatedResultException e, int index) {
        super(e);
        this.index = index;
    }
}
