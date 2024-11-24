package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.IllegalMultipleResultsInvocationException;
import io.github.m4gshm.components.visualizer.eval.result.Result.RelationsAware;
import io.github.m4gshm.components.visualizer.model.Component;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;

import java.util.List;

import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Multiple extends Result implements RelationsAware {
    List<Result> results;
    Eval eval;
    List<Result> relations;

    public Multiple(InstructionHandle firstInstruction, InstructionHandle lastInstruction,
                    List<Result> results, Eval eval, List<Result> relations) {
        super(firstInstruction, lastInstruction);
        this.results = results;
        this.eval = eval;

        this.relations = relations;
    }

    private void checkState() {
        if (results.isEmpty()) {
            throw new IllegalStateException("empty multiple values");
        }
    }

    public List<Result> getResults() {
        checkState();
        return results;
    }

    @Override
    public Object getValue() {
        throw new IllegalMultipleResultsInvocationException(this);
    }

    @Override
    public boolean isResolved() {
        checkState();
        return results.stream().allMatch(Result::isResolved);
    }

    @Override
    public String toString() {
        return "multiple" + results;
    }
}
