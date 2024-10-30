package io.github.m4gshm.connections;

import io.github.m4gshm.connections.model.CallPoint;
import io.github.m4gshm.connections.model.Component;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.client.Utils.getBootstrapMethods;
import static io.github.m4gshm.connections.eval.bytecode.EvalUtils.instructionHandleStream;
import static io.github.m4gshm.connections.eval.bytecode.InvokeDynamicUtils.getInvokeDynamicUsedMethodInfo;
import static java.util.stream.Collectors.toList;

public class CallPointsHelper {

    public static Stream<? extends CallPoint> getMethods(JavaClass javaClass, Class<?> componentType) {
        var constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
        var methods = getMethods(javaClass);
        return methods != null
                ? Stream.of(methods).map(method -> newCallPoint(componentType, method, javaClass, constantPoolGen))
                : Stream.of();
    }

    private static Method[] getMethods(JavaClass javaClass) {
        Method[] methods;
        try {
            methods = javaClass.getMethods();
        } catch (NoClassDefFoundError e) {
            methods = null;
        }
        return methods;
    }

    public static boolean isObject(JavaClass javaClass) {
        return "java.lang.Object".equals(javaClass.getClassName());
    }

    public static CallPoint newCallPoint(Class<?> componentType, Method method, JavaClass javaClass,
                                         ConstantPoolGen constantPoolGen) {
        BootstrapMethods bootstrapMethods;
        try {
            bootstrapMethods = getBootstrapMethods(javaClass);
        } catch (NoClassDefFoundError e) {
            //log
            return null;
        }
        var code = method.getCode();
        var instructionHandles = instructionHandleStream(code).collect(toList());

        var callPoints = new ArrayList<CallPoint>();
        for (var instructionHandle1 : instructionHandles) {
            var instruction = instructionHandle1.getInstruction();
            var callPoint = (instruction instanceof INVOKEDYNAMIC)
                    ? newInvokeDynamicCallPoint(instructionHandle1, bootstrapMethods, constantPoolGen)
                    : instruction instanceof INVOKEVIRTUAL || instruction instanceof INVOKEINTERFACE
                    ? newInvokeCallPoint(instructionHandle1, (InvokeInstruction) instruction, constantPoolGen)
                    : null;
            if (callPoint != null) {
                callPoints.add(callPoint);
            }
        }
        return CallPoint.builder()
                .methodName(method.getName())
                .ownerClass(componentType)
                .argumentTypes((method.getArgumentTypes()))
                .method(method)
                .javaClass(javaClass)
                .callPoints(callPoints)
                .build();
    }

    private static CallPoint newInvokeDynamicCallPoint(InstructionHandle instructionHandle,
                                                       BootstrapMethods bootstrapMethods,
                                                       ConstantPoolGen constantPoolGen) {
        var instruction = instructionHandle.getInstruction();
        if (instruction instanceof INVOKEDYNAMIC) {
            var methodInfo = getInvokeDynamicUsedMethodInfo((INVOKEDYNAMIC) instruction, bootstrapMethods, constantPoolGen);
            if (methodInfo != null) {
                var argumentTypes = Type.getArgumentTypes(methodInfo.getSignature());
                var methodName = methodInfo.getName();
                var ownerClassName = methodInfo.getObjectType().getName();
                var referenceKind = methodInfo.getReferenceKind();
                return CallPoint.builder()
                        .methodName(methodName)
                        .ownerClassName(ownerClassName)
                        .argumentTypes(argumentTypes)
                        .instruction(instructionHandle)
                        .invokeDynamic(true)
                        .referenceKind(referenceKind)
                        .build();
            }
        }
        return null;
    }

    private static CallPoint newInvokeCallPoint(InstructionHandle instructionHandle, InvokeInstruction instruction,
                                                ConstantPoolGen constantPoolGen) {
        var methodName = instruction.getMethodName(constantPoolGen);
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        return CallPoint.builder()
                .methodName(methodName)
                .ownerClassName(instruction.getClassName(constantPoolGen))
                .argumentTypes(argumentTypes)
                .instruction(instructionHandle)
                .build();
    }

    public interface CallPointsProvider extends Function<Component, List<CallPoint>> {

    }

}
