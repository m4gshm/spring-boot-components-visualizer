package io.github.m4gshm.connections;

import io.github.m4gshm.connections.model.CallPoint;
import io.github.m4gshm.connections.model.Component;
import io.github.m4gshm.connections.model.MethodId;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.client.Utils.getBootstrapMethods;
import static io.github.m4gshm.connections.eval.bytecode.EvalBytecodeUtils.instructionHandleStream;
import static io.github.m4gshm.connections.eval.bytecode.InvokeDynamicUtils.getInvokeDynamicUsedMethodInfo;
import static io.github.m4gshm.connections.model.MethodId.newMethodId;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

public class CallPointsHelper {
    public static List<CallPoint> getCallsHierarchy(Component component,
                                                    Map<Component, List<CallPoint>> callPointsCache) {


        if (callPointsCache.containsKey(component)) {
            return callPointsCache.get(component);
        } else {
            callPointsCache.put(component, List.of());
        }

        var componentType = component.getType();
        var javaClasses = ComponentsExtractor.getClassHierarchy(componentType);

        List<CallPoint> points = javaClasses.stream().filter(javaClass -> !isObject(javaClass)
        ).flatMap(javaClass -> getMethods(javaClass, componentType)).filter(Objects::nonNull).collect(toList());
        callPointsCache.put(component, points);
        return points;
    }

    private static Stream<? extends CallPoint> getMethods(JavaClass javaClass, Class<?> componentType) {
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

    private static boolean isObject(JavaClass javaClass) {
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
            if (instruction instanceof INVOKEVIRTUAL || instruction instanceof INVOKEINTERFACE || instruction instanceof INVOKEDYNAMIC) {
                var invokeInstruction = (InvokeInstruction) instruction;
                var callPoint = ofNullable(newInvokeDynamicCallPoint(instructionHandle1, bootstrapMethods, constantPoolGen))
                        .orElseGet(() -> newInvokeCallPoint(instructionHandle1, invokeInstruction, constantPoolGen));
                if (callPoint != null) {
                    callPoints.add(callPoint);
                }
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

}
