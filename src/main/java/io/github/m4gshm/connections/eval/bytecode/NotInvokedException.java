package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.result.Result;
import lombok.Getter;

@Getter
public class NotInvokedException extends UnresolvedResultException {

    public NotInvokedException(Result result) {
        super("not invoked", result);
    }

    public NotInvokedException(UnresolvedResultException cause, Result result) {
        super("not invoked", cause, result);
    }

}
