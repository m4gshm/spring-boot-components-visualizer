package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.result.Delay;
import io.github.m4gshm.connections.eval.result.Result;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Getter
public class NotInvokedException extends UnresolvedResultException {

    private final Reason reason;
    private final List<? extends EvalException> causes;
    private final List<Result> parameters;

    public NotInvokedException(Reason reason, Delay result) {
        this(reason, result, null);
    }

    public NotInvokedException(Reason reason, Delay result, List<Result> parameters) {
        super(reason.message, result);
        causes = null;
        this.parameters = parameters;
        this.reason = reason;
    }

    public NotInvokedException(Reason reason, List<? extends EvalException> causes, Delay result) {
        super(reason.message, causes.get(0), result);
        this.causes = causes;
        this.parameters = null;
        this.reason = reason;
    }

    @Override
    public Delay getResult() {
        return (Delay) super.getResult();
    }

    @RequiredArgsConstructor
    public enum Reason {
        noCalls("no calls"),
        unresolvedVariables("unresolved variables"),
        noParameterVariants("no parameter variants");
        private final String message;
    }

}
