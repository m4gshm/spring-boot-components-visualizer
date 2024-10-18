package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.result.Result;
import lombok.Getter;

@Getter
public class NoCallException extends UnresolvedResultException {

    public NoCallException(Result result) {
        super("no call", result);
    }

}
