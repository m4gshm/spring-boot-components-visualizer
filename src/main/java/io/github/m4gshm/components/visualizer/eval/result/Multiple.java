package io.github.m4gshm.components.visualizer.eval.result;

import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.IllegalMultipleResultsInvocationException;
import io.github.m4gshm.components.visualizer.eval.result.Result.RelationsAware;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.generic.InstructionHandle;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static java.util.stream.Collectors.toList;
import static lombok.AccessLevel.PRIVATE;

@Getter
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class Multiple extends Result implements RelationsAware {
    List<Result> results;
    Eval eval;
    List<Result> relations;

    public Multiple(List<Result> results, Eval eval, List<Result> relations) {
        super(
                getInstructions(results, Result::getFirstInstructions),
                getInstructions(results, Result::getLastInstructions)
        );
        this.results = results;
        this.eval = eval;

        this.relations = relations;
    }

    public static List<InstructionHandle> getInstructions(List<Result> results, Function<Result, List<InstructionHandle>> resultListFunction) {
        return results.stream().map(resultListFunction).flatMap(Collection::stream).collect(toList());
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

    @Override
    public boolean equals(Object object) {
        if (object == null || getClass() != object.getClass()) return false;
        if (!super.equals(object)) return false;
        Multiple multiple = (Multiple) object;
        return Objects.equals(results, multiple.results) && Objects.equals(eval, multiple.eval) && Objects.equals(relations, multiple.relations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), results, eval, relations);
    }
}
