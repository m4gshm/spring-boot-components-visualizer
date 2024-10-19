package io.github.m4gshm.connections.eval.result;

import io.github.m4gshm.connections.eval.bytecode.EvalBytecode;
import io.github.m4gshm.connections.eval.bytecode.EvalBytecode.EvalArguments;
import io.github.m4gshm.connections.eval.bytecode.EvalBytecode.InvokeObject;
import io.github.m4gshm.connections.eval.bytecode.EvalBytecode.ParameterValue;
import io.github.m4gshm.connections.eval.bytecode.EvalBytecodeException;
import io.github.m4gshm.connections.eval.result.Delay.DelayFunction;
import io.github.m4gshm.connections.model.Component;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.LocalVariable;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.Type;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.getInstructionString;
import static io.github.m4gshm.connections.eval.result.Illegal.Status.notAccessible;
import static io.github.m4gshm.connections.eval.result.Illegal.Status.notFound;
import static io.github.m4gshm.connections.eval.result.Variable.VarType.LocalVar;
import static io.github.m4gshm.connections.eval.result.Variable.VarType.MethodArg;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static lombok.AccessLevel.PROTECTED;
import static org.apache.bcel.generic.Type.getType;

@Data
@RequiredArgsConstructor
@FieldDefaults(level = PROTECTED, makeFinal = true)
public abstract class Result implements ContextAware {
    InstructionHandle firstInstruction;
    InstructionHandle lastInstruction;

    public static Constant invoked(Object value, InstructionHandle invokeInstruction, InstructionHandle lastInstruction,
                                   Component component, Method method, List<ParameterValue> parameters) {
        var params = parameters.stream().map(ParameterValue::getParameter).collect(toList());
        return constant(value, invokeInstruction, lastInstruction, params, component, method);
    }

    public static Constant constant(Object value, InstructionHandle firstInstruction, InstructionHandle lastInstruction,
                                    Component component, Method method, Result... relations) {
        return constant(value, firstInstruction, lastInstruction, asList(relations), component, method);
    }

    public static Constant constant(Object value, InstructionHandle firstInstruction, InstructionHandle lastInstruction,
                                    List<Result> relations, Component component, Method method) {
        var notNullRelations = relations.stream().filter(Objects::nonNull).collect(toList());
        return new Constant(firstInstruction, lastInstruction, value, notNullRelations, component, method);
    }

    public static DelayLoadFromStore delayLoadFromStored(String description, InstructionHandle instructionHandle,
                                                         EvalBytecode evalContext, Result parent,
                                                         List<Result> storeInstructions,
                                                         DelayFunction<DelayLoadFromStore> delayFunction) {
        if (storeInstructions.isEmpty()) {
            throw new IllegalArgumentException("No store instructions found");
        }
//        var lastInstruction = storeInstructions.stream().map(Result::getLastInstruction)
//                .reduce((l, r) -> l.getPosition() <= r.getPosition() ? l : r).orElse(null);
        return new DelayLoadFromStore(instructionHandle, instructionHandle, evalContext, description, delayFunction, parent,
                storeInstructions);
    }


    public static Delay delay(String description, InstructionHandle instructionHandle, EvalBytecode evalContext,
                              Result parent, DelayFunction<Delay> delayFunction) {
        return new Delay(instructionHandle, instructionHandle, evalContext, description, delayFunction, parent, null, false, false);
    }

    public static DelayInvoke delayInvoke(InstructionHandle instructionHandle, EvalBytecode evalContext,
                                          Result parent, InvokeObject invokeObject, EvalArguments arguments,
                                          DelayFunction<DelayInvoke> delayFunction) {
        var lastInstruction = invokeObject != null
                ? invokeObject.getLastInstruction()
                : arguments.getLastArgInstruction();
        var object = invokeObject != null ? invokeObject.getObject() : null;
        var description = getInstructionString(instructionHandle, evalContext.getConstantPoolGen());
        return new DelayInvoke(instructionHandle, lastInstruction, evalContext, description,
                delayFunction, parent, object, arguments.getArguments());
    }

    public static Variable methodArg(EvalBytecode evalContext, LocalVariable localVariable,
                                     InstructionHandle lastInstruction, Result parent) {
        int startPC = localVariable.getStartPC();
        if (startPC > 0) {
            var componentType = evalContext.getComponent().getType();
            var method = evalContext.getMethod();
            throw new EvalBytecodeException("argument's variable ust has 0 startPC, " +
                    localVariable.getName() + ", " + componentType.getName() + "." +
                    method.getName() + method.getSignature());
        }
        var type = getType(localVariable.getSignature());
        int index = localVariable.getIndex();
        var name = localVariable.getName();
        return methodArg(evalContext, index, name, type, lastInstruction, parent);
    }

    public static Variable methodArg(EvalBytecode evalContext, int index, String name,
                                     Type type, InstructionHandle lastInstruction, Result parent) {
        return new Variable(lastInstruction, lastInstruction, MethodArg, evalContext, index, name, type, parent);
    }

    public static Variable variable(EvalBytecode evalContext, LocalVariable localVariable,
                                    InstructionHandle lastInstruction, Result parent) {
        var type = getType(localVariable.getSignature());
        int index = localVariable.getIndex();
        var name = localVariable.getName();
        return variable(evalContext, index, name, type, lastInstruction, parent);
    }

    public static Variable variable(EvalBytecode evalContext, int index, String name, Type type,
                                    InstructionHandle lastInstruction, Result parent) {
        return new Variable(lastInstruction, lastInstruction, LocalVar, evalContext, index, name, type, parent);
    }

    public static Illegal notAccessible(Object element, InstructionHandle callInstruction, Result source) {
        return new Illegal(callInstruction, callInstruction, Set.of(notAccessible), element, source);
    }

    public static Illegal notFound(Object element, InstructionHandle callInstruction, Result source) {
        return new Illegal(callInstruction, callInstruction, Set.of(notFound), element, source);
    }

    public static Result multiple(List<? extends Result> values, InstructionHandle firstInstruction,
                                  InstructionHandle lastInstruction, Component component, Method method) {
        var flatValues = values.stream().flatMap(v -> v instanceof Multiple
                ? ((Multiple) v).getResults().stream()
                : Stream.of(v)).distinct().collect(toList());
        if (flatValues.isEmpty()) {
            throw new IllegalArgumentException("unresolved multiple values");
        } else if (flatValues.size() == 1) {
            return flatValues.get(0);
        } else {
            return new Multiple(firstInstruction, lastInstruction, flatValues, component, method);
        }
    }

    public static Result stub(Result value, Component component, Method method, Resolver resolver, EvalBytecode eval) {
        if (resolver != null) {
            //log
            return resolver.resolve(value, null);
        }
        return new Stub(method, component, value);
    }

    public static Result getWrapped(Result result) {
        if (result instanceof Wrapper) {
            var wrapped = ((Wrapper) result).wrapped();
            var touched = new HashSet<Result>();
            touched.add(wrapped);
            while (wrapped instanceof Wrapper) {
                var result1 = getWrapped(wrapped);
                if (touched.add(result1)) {
                    wrapped = result1;
                } else {
                    break;
                }
            }
            return wrapped;
        }
        return null;
    }

    public static Result noCallVariants(Delay current) {
        return new NoCall(current);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        var that = (Result) o;
        return Objects.equals(getComponent(), that.getComponent())
                && Objects.equals(getMethod(), that.getMethod())
                && Objects.equals(getInstruction(firstInstruction), getInstruction(that.firstInstruction))
                && Objects.equals(getInstruction(lastInstruction), getInstruction(that.lastInstruction));
    }

    private Instruction getInstruction(InstructionHandle instruction) {
        return instruction != null ? instruction.getInstruction() : null;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getComponent(), getMethod(), getInstruction(firstInstruction), getInstruction(lastInstruction));
    }

    public List<Object> getValue(Resolver resolver) {
        try {
            return singletonList(getValue());
        } catch (EvalBytecodeException e) {
            if (resolver != null) {
                var resolved = resolver.resolve(this, e);
                var values = EvalBytecode.expand(resolved).stream().map(Result::getValue).collect(toList());
                return values;
            }
            throw e;
        }
    }

    /**
     * use getValue(Resolver resolver)
     */
    @Deprecated
    public abstract Object getValue();

    public abstract boolean isResolved();

    public interface RelationsAware {
        List<Result> getRelations();
    }

    public interface PrevAware {
        Result getPrev();
    }

    public interface Wrapper {
        Result wrapped();
    }

}
