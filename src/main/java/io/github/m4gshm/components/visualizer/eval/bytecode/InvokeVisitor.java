package io.github.m4gshm.components.visualizer.eval.bytecode;

import lombok.Data;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Predicate;

import static io.github.m4gshm.components.visualizer.client.Utils.getBootstrapMethods;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.*;
import static io.github.m4gshm.components.visualizer.eval.bytecode.InvokeDynamicUtils.getBootstrapMethodHandlerAndArguments;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static lombok.AccessLevel.PRIVATE;
import static org.apache.bcel.generic.Type.getArgumentTypes;

@FieldDefaults(makeFinal = true, level = PRIVATE)
public class InvokeVisitor {

    Object object;
    JavaClass javaClass;
    ConstantPoolGen cpg;
    Method method;

    public InvokeVisitor(Object object, JavaClass javaClass, Method method) {
        this.object = object;
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

    public static Visitor putFieldFinder(final Predicate<PUTFIELD> matcher) {
        return new EmptyVisitor() {
            @Override
            public void visitPUTFIELD(PUTFIELD obj) {
                if (matcher.test(obj)) {
                    throw new Stop();
                }
            }
        };
    }

    public static <T extends InvokeInstruction> Visitor constructorFinder(Predicate<? super InvokeInstruction> matcher) {
        return new EmptyVisitor() {
            @Override
            public void visitInvokeInstruction(InvokeInstruction obj) {
                if (matcher.test(obj)) {
                    throw new Stop();
                }
            }
        };
    }

    private static InstructionHandle getLast(List<InstructionHandle> instructionHandles) {
        return !instructionHandles.isEmpty() ? instructionHandles.get(instructionHandles.size() - 1) : null;
    }

    private static InstructionHandle getFirst(Method method) {
        return instructionHandleStream(method).findFirst().orElse(null);
    }

    public static Predicate<PUTFIELD> putFiledMatcher(String fieldName, Type fieldType, ConstantPoolGen cpg) {
        return putfield -> fieldName.equals(putfield.getFieldName(cpg))
                && fieldType.equals(putfield.getFieldType(cpg));
    }

    public static Predicate<InvokeInstruction> constructorMatcher(Type type, Type[] arguments, ConstantPoolGen cpg) {
        return invokeMatcher(INVOKESPECIAL.class, type, null, "<init>", arguments, cpg);
    }

    public static Predicate<InvokeInstruction> methodAnyNameMatcher(Type returnType, Type[] arguments, ConstantPoolGen cpg) {
        return invokeMatcher(InvokeInstruction.class, null, returnType, null, arguments, cpg);
    }

    public static <T extends InvokeInstruction> Predicate<T> invokeMatcher(Class<? extends T> instruction, Type objectType, Type returnType,
                                                                           String methodName, Type[] arguments,
                                                                           ConstantPoolGen cpg) {
        return invokeInstruction ->
                (instruction == null || instruction.isAssignableFrom(invokeInstruction.getClass())) &&
                        (returnType == null || returnType.equals(invokeInstruction.getType(cpg))) &&
                        (objectType == null || objectType.equals(invokeInstruction.getLoadClassType(cpg))) &&
                        (methodName == null || methodName.equals(invokeInstruction.getName(cpg))) &&
                        (arguments == null || Arrays.equals(arguments, invokeInstruction.getArgumentTypes(cpg)));
    }

    public VisitResult findForwardSequentially(JavaClass javaClass, Method method, Visitor visitor) {
        return newInvokeVisitor(javaClass, method).findForwardSequentially(getFirst(method), visitor);
    }

    public VisitResult findForwardSequentially(InstructionHandle handle, Visitor visitor) {
        if (handle == null) {
            return null;
        }
        var instruction = handle.getInstruction();
        try {
            instruction.accept(visitor);
        } catch (Stop e) {
            return new VisitResult(object, javaClass, method, handle);
        }

        if (instruction instanceof INVOKEDYNAMIC) {
            var classAndMethodSource = getJavaClassMethodEntry(getSourceMethodInfo((INVOKEDYNAMIC) instruction));
            if (classAndMethodSource != null) {
                return findForwardSequentially(classAndMethodSource.getKey(), classAndMethodSource.getValue(), visitor);
            } else {
                //log
                return null;
            }
        } else if (instruction instanceof InvokeInstruction) {
            var classAndMethodSource = getJavaClassMethodEntry((InvokeInstruction) instruction, cpg);
            if (classAndMethodSource != null) {
                return findForwardSequentially(classAndMethodSource.getKey(), classAndMethodSource.getValue(), visitor);
            } else {
                return null;
                //log
            }
        }
        return findForwardSequentially(handle.getNext(), visitor);
    }

    public VisitResult findForwardSequentially(Method method, Visitor visitor) {
        return findForwardSequentially(getFirst(method), visitor);
    }

    public VisitResult findBackTrace(Visitor visitor) {
        return findBackTrace(getLast(instructionHandleStream(method).collect(toList())), visitor);
    }

    public VisitResult findBackTrace(JavaClass javaClass, Method method, Visitor visitor) {
        return newInvokeVisitor(javaClass, method).findBackTrace(visitor);
    }

    private InvokeVisitor newInvokeVisitor(JavaClass javaClass, Method method) {
        return new InvokeVisitor(getNextObject(javaClass), javaClass, method);
    }

    @Deprecated
    private Object getNextObject(JavaClass javaClass) {
        return this.javaClass.equals(javaClass) ? this.object : null;
    }

    public VisitResult findBackTrace(InstructionHandle handle, Visitor visitor) {
        if (handle == null) {
            return null;
        }
        var instruction = handle.getInstruction();
        try {
            instruction.accept(visitor);
        } catch (Stop e) {
            return new VisitResult(object, javaClass, method, handle);
        }

        if (instruction instanceof INVOKEDYNAMIC) {
            var classAndMethodSource = getJavaClassMethodEntry(getSourceMethodInfo((INVOKEDYNAMIC) instruction));
            if (classAndMethodSource != null) {
                //eval and put object to the next findBackTrace call
                return findBackTrace(classAndMethodSource.getKey(), classAndMethodSource.getValue(), visitor);
            } else {
                //log
            }
        } else if (instruction instanceof InvokeInstruction) {
            var classAndMethodSource = getJavaClassMethodEntry((InvokeInstruction) instruction, cpg);
            if (classAndMethodSource != null) {
                return findBackTrace(classAndMethodSource.getKey(), classAndMethodSource.getValue(), visitor);
            } else {
                //log
            }
        } else if (instruction instanceof GETFIELD) {
            var getfield = (GETFIELD) instruction;
            var fieldName = getfield.getFieldName(cpg);
            var fieldType = getfield.getFieldType(cpg);

            //find put files
            var putFieldMatcher = putFieldFinder(putFiledMatcher(fieldName, fieldType, cpg));
            var putFieldInThisMethod = findForwardSequentially(method, putFieldMatcher);
            if (putFieldInThisMethod != null) {
                return putFieldInThisMethod;
            } else {
                return stream(javaClass.getMethods())
                        .map(method1 -> findForwardSequentially(method1, putFieldMatcher)).filter(Objects::nonNull)
                        .map(putFiled -> newInvokeVisitor(putFiled.getJavaClass(), putFiled.getMethod())
                                .findBackTrace(visitor)).filter(Objects::nonNull).findFirst().orElse(null);
            }
        }
        return null;
    }

    private MethodInfo getSourceMethodInfo(INVOKEDYNAMIC instruction) {
        return getBootstrapMethodHandlerAndArguments(
                instruction, getBootstrapMethods(javaClass), cpg).getSourceMethodInfo();
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public static class VisitResult {
        Object object;
        JavaClass javaClass;
        Method method;
        InstructionHandle handle;
    }

    @Getter
    public static class Stop extends RuntimeException {
        public Stop() {
            super("stop", null, false, false);
        }
    }
}
