package io.github.m4gshm.connections;

import io.github.m4gshm.connections.bytecode.EvalBytecode;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
import io.github.m4gshm.connections.model.CallPoint;
import io.github.m4gshm.connections.model.Component;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.util.*;

import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.*;
import static io.github.m4gshm.connections.bytecode.InvokeDynamicUtils.getInvokeDynamicUsedMethodInfo;
import static io.github.m4gshm.connections.bytecode.MethodInfo.newMethodInfo;
import static io.github.m4gshm.connections.client.JmsOperationsUtils.getBootstrapMethods;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

public class CallPointsHelper {
    public static List<CallPoint> getCallsHierarchy(Component component,
                                                    Map<Component, List<Component>> dependencyToDependentMap,
                                                    Map<Component, List<CallPoint>> callPointsCache, Result parent) {


        if (callPointsCache.containsKey(component)) {
            return callPointsCache.get(component);
        } else {
            callPointsCache.put(component, List.of());
        }

        var componentType = component.getType();
        var javaClasses = ComponentsExtractor.getClassHierarchy(componentType);

        var points = javaClasses.stream().filter(javaClass -> !isObject(javaClass)
        ).flatMap(javaClass -> {
            var constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
            var methods = javaClass.getMethods();
            return stream(methods).map(method -> newCallPoint(component, componentType, method, javaClass,
                    dependencyToDependentMap, constantPoolGen, callPointsCache, parent));
        }).collect(toList());
        callPointsCache.put(component, points);
        return points;
    }

    private static boolean isObject(JavaClass javaClass) {
        return "java.lang.Object".equals(javaClass.getClassName());
    }

    public static CallPoint newCallPoint(Component component, Class<?> componentType,
                                         Method method, JavaClass javaClass,
                                         Map<Component, List<Component>> dependencyToDependentMap,
                                         ConstantPoolGen constantPoolGen,
                                         Map<Component, List<CallPoint>> callPointsCache, Result parent) {
        var code = method.getCode();
        var bootstrapMethods = getBootstrapMethods(javaClass);
        var instructionHandles = instructionHandleStream(code).collect(toList());
//        var instructionHandle = !instructionHandles.isEmpty()
//                ? instructionHandles.get(instructionHandles.size() - 1) : null;
//
//        var callPoints1 = new ArrayList<CallPoint>();
//        while (instructionHandle != null) {
//            var instruction = instructionHandle.getInstruction();
//            if (instruction instanceof INVOKEVIRTUAL || instruction instanceof INVOKEINTERFACE
//                    || instruction instanceof INVOKEDYNAMIC) {
//
//                var instrCallPoints = new LinkedHashSet<CallPoint>();
//                var eval = new EvalBytecode(component, dependencyToDependentMap, constantPoolGen,
//                        bootstrapMethods, method, callPointsCache);
//
//                var invokeInstruction = (InvokeInstruction) instruction;
//                var argumentTypes = invokeInstruction.getArgumentTypes(constantPoolGen);
//                var evalArguments = eval.evalArguments(instructionHandle, argumentTypes.length, parent);
//                var invokeObject = eval.evalInvokeObject(invokeInstruction, evalArguments, parent);
//
//                var invokeDynamics = findInvokeDynamicCalls(
//                        instructionHandle,
//                        invokeObject.getLastInstruction(),
//                        invokeObject.getFirstInstruction(),
//                        bootstrapMethods, constantPoolGen
//                );
//                if (!invokeDynamics.isEmpty()) {
//                    instrCallPoints.addAll(invokeDynamics);
//                } else {
//                    var invokeCallPoint = newInvokeCallPoint(instructionHandle,
//                            (InvokeInstruction) instruction, constantPoolGen);
//                    instrCallPoints.add(invokeCallPoint);
//                    var invokeCalls = findInvokeCalls(
//                            invokeObject.getLastInstruction(),
//                            invokeObject.getFirstInstruction(),
//                            constantPoolGen
//                    );
//                    instrCallPoints.addAll(invokeCalls);
//                }
//
//                var arguments = evalArguments.getArguments();
//                for (var argument : arguments) {
//                    var invokeDynamicCalls = findInvokeDynamicCalls(
//                            instructionHandle,
//                            argument.getLastInstruction(),
//                            argument.getFirstInstruction(),
//                            bootstrapMethods, constantPoolGen);
//                    if (!invokeDynamicCalls.isEmpty()) {
//                        instrCallPoints.addAll(invokeDynamicCalls);
//                    } else {
//                        var invokeCalls = findInvokeCalls(
//                                argument.getLastInstruction(),
//                                argument.getFirstInstruction(),
//                                constantPoolGen
//                        );
//                        instrCallPoints.addAll(invokeCalls);
//                    }
//                }
//
//                callPoints1.addAll(instrCallPoints);
//
//                var lastArgInstructionHandle = invokeObject.getLastInstruction();
//                instructionHandle = lastArgInstructionHandle != null ? lastArgInstructionHandle.getPrev() : null;
//            } else {
//                instructionHandle = instructionHandle.getPrev();
//            }
//        }

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
//                .callPoints(callPoints1)
                .callPoints(callPoints)
                .build();
    }

    public static List<CallPoint> findInvokeDynamicCalls(InstructionHandle parent,
                                                         InstructionHandle toLast, InstructionHandle fromFirst,
                                                         BootstrapMethods bootstrapMethods, ConstantPoolGen constantPoolGen) {
        var dest = new ArrayList<CallPoint>();
        ofNullable(newInvokeDynamicCallPoint(parent, bootstrapMethods, constantPoolGen)).ifPresent(dest::add);
        //reverse loop
        while (fromFirst != null && fromFirst.getPosition() >= toLast.getPosition()) {
            ofNullable(newInvokeDynamicCallPoint(fromFirst, bootstrapMethods, constantPoolGen)).ifPresent(dest::add);
            fromFirst = fromFirst.getPrev();
        }
        return dest;
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

    public static List<CallPoint> findInvokeCalls(InstructionHandle fromLast, InstructionHandle toFirst,
                                                  ConstantPoolGen constantPoolGen) {
        var dest = new ArrayList<CallPoint>();
        while (fromLast != null && fromLast.getPosition() >= toFirst.getPosition()) {
            var instruction = fromLast.getInstruction();
            if (instruction instanceof INVOKEVIRTUAL || instruction instanceof INVOKEINTERFACE) {
                var callPoint = newInvokeCallPoint(fromLast, (InvokeInstruction) instruction, constantPoolGen);
                dest.add(callPoint);
            }
            fromLast = fromLast.getPrev();
        }
        return dest;
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
