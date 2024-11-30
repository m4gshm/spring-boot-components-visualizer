package io.github.m4gshm.components.visualizer.eval.bytecode;

import io.github.m4gshm.components.visualizer.eval.result.Delay;
import io.github.m4gshm.components.visualizer.eval.result.Result;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Getter
public class NotInvokedException extends UnresolvedResultException {

    private final Reason reason;
    private final List<Result> parameters;


    public NotInvokedException(Reason reason, Delay result, List<Result> parameters) {
        super(reason.message, result);
        this.parameters = parameters;
        this.reason = reason;
    }

    public NotInvokedException(Reason reason, List<? extends EvalException> causes, Delay result) {
        super(reason.message, causes.get(0), result);
        addSuppressed(causes);
        this.parameters = null;
        this.reason = reason;
    }

    private void addSuppressed(List<? extends EvalException> causes) {
        if (causes.size() > 1) {
            for (var i = 1; i < causes.size(); ++i) {
                this.addSuppressed(causes.get(i));
            }
        }
    }

    @Override
    public Delay getResult() {
        return (Delay) super.getResult();
    }

    @RequiredArgsConstructor
    public enum Reason {
        badEval("bad eval"),
        noCalls("no calls"),
        unresolvedVariables("unresolved variables"),
        noParameterVariants("no parameter variants"),
        badParameterVariants("bad parameter variants");
        private final String message;
    }

}
