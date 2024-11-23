package io.github.m4gshm.components.visualizer.eval.bytecode;

import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.util.function.Predicate;

import static io.github.m4gshm.components.visualizer.client.Utils.getBootstrapMethods;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.*;
import static io.github.m4gshm.components.visualizer.eval.bytecode.InvokeDynamicUtils.getBootstrapMethodHandlerAndArguments;
import static java.util.stream.Collectors.toList;
import static lombok.AccessLevel.PRIVATE;
import static org.apache.bcel.generic.Type.getArgumentTypes;

@FieldDefaults(makeFinal = true, level = PRIVATE)
public class InvokeVisitor extends EmptyVisitor {

    JavaClass javaClass;
    ConstantPoolGen cpg;
    Method method;
    Predicate<INVOKESPECIAL> matcher;

    public InvokeVisitor(JavaClass javaClass, Method method, Predicate<INVOKESPECIAL> matcher) {
        this.javaClass = javaClass;
        this.cpg = new ConstantPoolGen(javaClass.getConstantPool());
        this.method = method;
        this.matcher = matcher;
    }

    public VisitResult findForward(InstructionHandle handle) {
        if (handle == null) {
            return null;
        }
        var instruction = handle.getInstruction();
        try {
            instruction.accept(this);
        } catch (Stop e) {
            return new VisitResult(this.javaClass, this.method, handle);
        }

        if (instruction instanceof INVOKEDYNAMIC) {
            //go deep
            var bootstrapMethods = getBootstrapMethods(javaClass);
            var bootstrapMethodAndArguments = getBootstrapMethodHandlerAndArguments(
                    (INVOKEDYNAMIC) instruction, bootstrapMethods, cpg);

            var sourceMethodInfo = bootstrapMethodAndArguments.getSourceMethodInfo();
            if (sourceMethodInfo != null) {
                var methodName = sourceMethodInfo.getName();
                var className = sourceMethodInfo.getClassName();
                var argumentTypes = getArgumentTypes(sourceMethodInfo.getSignature());

                var classAndMethodSource = getClassAndMethodSource(getClassByName(className), methodName, argumentTypes);
                if (classAndMethodSource != null) {
                    JavaClass javaClass1 = classAndMethodSource.getKey();
                    Method method1 = classAndMethodSource.getValue();
                    return findForward(javaClass1, method1);
                } else {
                    //log
                }
            } else {
                //log
            }
        } else if (instruction instanceof InvokeInstruction) {
            var invokeInstruction = (InvokeInstruction) instruction;
            var className = invokeInstruction.getClassName(cpg);
            var methodName = invokeInstruction.getMethodName(cpg);
            var argumentTypes = invokeInstruction.getArgumentTypes(cpg);
            var classAndMethodSource = getClassAndMethodSource(getClassByName(className), methodName, argumentTypes);
            if (classAndMethodSource != null) {
                JavaClass javaClass1 = classAndMethodSource.getKey();
                Method method1 = classAndMethodSource.getValue();
                return findForward(javaClass1, method1);
            } else {
                //log
            }
        }
        return findForward(handle.getNext());
    }

    private VisitResult findInCallTree(InstructionHandle handle) {
        handle.get
        return null;
    }

    private VisitResult findForward(JavaClass javaClass, Method method) {
        var first = instructionHandleStream(method).findFirst().orElse(null);
        return new InvokeVisitor(javaClass, method, matcher).findForward(first);
    }

    private VisitResult findFromReturn(JavaClass javaClass, Method method) {
        var instructionHandles = instructionHandleStream(method).collect(toList());
        var last = !instructionHandles.isEmpty() ? instructionHandles.get(instructionHandles.size() - 1) : null;
        return new InvokeVisitor(javaClass, method, matcher).findInCallTree(last);
    }

    @Override
    public void visitINVOKESPECIAL(INVOKESPECIAL obj) {
        if (matcher.test(obj)) {
            throw new Stop(obj);
        }
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class VisitResult {
        JavaClass javaClass;
        Method method;
        InstructionHandle handle;

    }

    @Getter
    @RequiredArgsConstructor
    public static class Stop extends RuntimeException {
        INVOKESPECIAL invokespecial;
    }
}
