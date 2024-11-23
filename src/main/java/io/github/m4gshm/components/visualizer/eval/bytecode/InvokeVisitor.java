package io.github.m4gshm.components.visualizer.eval.bytecode;

import lombok.Data;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.util.Map.Entry;
import java.util.function.Predicate;

import static io.github.m4gshm.components.visualizer.client.Utils.getBootstrapMethods;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.*;
import static io.github.m4gshm.components.visualizer.eval.bytecode.InvokeDynamicUtils.getBootstrapMethodHandlerAndArguments;
import static java.util.stream.Collectors.toList;
import static lombok.AccessLevel.PRIVATE;
import static org.apache.bcel.generic.Type.getArgumentTypes;

@FieldDefaults(makeFinal = true, level = PRIVATE)
public class InvokeVisitor {

    JavaClass javaClass;
    ConstantPoolGen cpg;
    Method method;

    public InvokeVisitor(JavaClass javaClass, Method method) {
        this.javaClass = javaClass;
        this.cpg = new ConstantPoolGen(javaClass.getConstantPool());
        this.method = method;
    }

    private static Entry<JavaClass, Method> getJavaClassMethodEntry(MethodInfo sourceMethodInfo) {
        if (sourceMethodInfo != null) {
            var methodName = sourceMethodInfo.getName();
            var className = sourceMethodInfo.getClassName();
            var argumentTypes = getArgumentTypes(sourceMethodInfo.getSignature());
            return getClassAndMethodSource(getClassByName(className), methodName, argumentTypes);
        } else {
            //log
            return null;
        }
    }

    private static Entry<JavaClass, Method> getJavaClassMethodEntry(InvokeInstruction instruction, ConstantPoolGen cpg) {
        var className = instruction.getClassName(cpg);
        var methodName = instruction.getMethodName(cpg);
        var argumentTypes = instruction.getArgumentTypes(cpg);
        return getClassAndMethodSource(getClassByName(className), methodName, argumentTypes);
    }

    private static VisitResult findForward(JavaClass javaClass, Method method, Visitor visitor) {
        return new InvokeVisitor(javaClass, method).findForward(getInstructionHandle(method), visitor);
    }

    private static InstructionHandle getInstructionHandle(Method method) {
        return instructionHandleStream(method).findFirst().orElse(null);
    }

    public VisitResult findForward(InstructionHandle handle, Visitor visitor) {
        if (handle == null) {
            return null;
        }
        var instruction = handle.getInstruction();
        try {
            instruction.accept(visitor);
        } catch (Stop e) {
            return new VisitResult(this.javaClass, this.method, handle);
        }

        if (instruction instanceof INVOKEDYNAMIC) {
            //go deep
            var bootstrapMethods = getBootstrapMethods(javaClass);
            var bootstrapMethodAndArguments = getBootstrapMethodHandlerAndArguments(
                    (INVOKEDYNAMIC) instruction, bootstrapMethods, cpg);

            var classAndMethodSource = getJavaClassMethodEntry(bootstrapMethodAndArguments.getSourceMethodInfo());
            if (classAndMethodSource != null) {
                return findForward(classAndMethodSource.getKey(), classAndMethodSource.getValue(), visitor);
            } else {
                //log
            }
        } else if (instruction instanceof InvokeInstruction) {
            var classAndMethodSource = getJavaClassMethodEntry((InvokeInstruction) instruction, cpg);
            if (classAndMethodSource != null) {
                return findForward(classAndMethodSource.getKey(), classAndMethodSource.getValue(), visitor);
            } else {
                //log
            }
//        } else if (instruction instanceof GETFIELD) {
//            var getfield = (GETFIELD) instruction;
//            var fieldName = getfield.getFieldName(cpg);
//            //find put files
//            var findPutFiled = Arrays.stream(javaClass.getMethods()).map(method1 -> {
//                var instructionHandleStream = instructionHandleStream(method1);
//                return findForward(instructionHandleStream.findFirst().orElse(null), putFieldMatcher(fieldName));
//            }).filter(Objects::nonNull).findFirst().orElse(null);
//
        }
        return findForward(handle.getNext(), visitor);
    }

    private EmptyVisitor putFieldMatcher(String fieldName) {
        return new EmptyVisitor() {
            @Override
            public void visitPUTFIELD(PUTFIELD obj) {
                if (fieldName.equals(obj.getFieldName(cpg))) {
                    throw new Stop();
                }
            }
        };
    }

    private EmptyVisitor ConstructorMatcher(Predicate<INVOKESPECIAL> matcher) {
        return new EmptyVisitor() {
            @Override
            public void visitINVOKESPECIAL(INVOKESPECIAL obj) {
                if (matcher.test(obj)) {
                    throw new Stop();
                }
            }
        };
    }

    private VisitResult findFromReturn(Visitor visitor) {
        var instructionHandles = instructionHandleStream(method).collect(toList());
        var handle = !instructionHandles.isEmpty() ? instructionHandles.get(instructionHandles.size() - 1) : null;

        if (handle == null) {
            return null;
        }
        var instruction = handle.getInstruction();
        try {
            instruction.accept(visitor);
        } catch (Stop e) {
            return new VisitResult(this.javaClass, this.method, handle);
        }

        if (instruction instanceof INVOKEDYNAMIC) {
            //go deep
            var bootstrapMethods = getBootstrapMethods(javaClass);
            var bootstrapMethodAndArguments = getBootstrapMethodHandlerAndArguments(
                    (INVOKEDYNAMIC) instruction, bootstrapMethods, cpg);

            var classAndMethodSource = getJavaClassMethodEntry(bootstrapMethodAndArguments.getSourceMethodInfo());
            if (classAndMethodSource != null) {
                return findFromReturn(classAndMethodSource.getKey(), classAndMethodSource.getValue(), visitor);
            } else {
                //log
            }
        } else if (instruction instanceof InvokeInstruction) {
            var classAndMethodSource = getJavaClassMethodEntry((InvokeInstruction) instruction, cpg);
            if (classAndMethodSource != null) {
                return findFromReturn(classAndMethodSource.getKey(), classAndMethodSource.getValue(), visitor);
            } else {
                //log
            }
//        } else if (instruction instanceof GETFIELD) {
//            var getfield = (GETFIELD) instruction;
//            var fieldName = getfield.getFieldName(cpg);
//            //find put files
//            var findPutFiled = Arrays.stream(javaClass.getMethods()).map(method1 -> {
//                var instructionHandleStream = instructionHandleStream(method1);
//                return findForward(instructionHandleStream.findFirst().orElse(null), putFieldMatcher(fieldName));
//            }).filter(Objects::nonNull).findFirst().orElse(null);
//
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
    public static class Stop extends RuntimeException {
    }
}
