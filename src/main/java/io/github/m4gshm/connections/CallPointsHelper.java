package io.github.m4gshm.connections;

import io.github.m4gshm.connections.bytecode.EvalBytecode;
import io.github.m4gshm.connections.bytecode.EvalBytecode.Result;
import io.github.m4gshm.connections.bytecode.MethodInfo;
import io.github.m4gshm.connections.model.CallPoint;
import io.github.m4gshm.connections.model.Component;
import org.apache.bcel.classfile.ConstantMethodHandle;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.util.*;
import java.util.function.Function;

import static io.github.m4gshm.connections.bytecode.EvalBytecodeUtils.*;
import static io.github.m4gshm.connections.bytecode.MethodInfo.newMethodInfo;
import static io.github.m4gshm.connections.client.JmsOperationsUtils.getBootstrapMethods;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

public class CallPointsHelper {
    public static List<CallPoint> getCallsHierarchy(Component component,
                                                    Map<Component, List<Component>> dependencyToDependentMap,
                                                    Function<Result, Result> unevaluatedHandler) {
        var componentType = component.getType();
        var javaClasses = ComponentsExtractor.getClassHierarchy(componentType);
        return javaClasses.stream().filter(javaClass -> !"java.lang.Object".equals(javaClass.getClassName())).flatMap(javaClass -> {
            var constantPoolGen = new ConstantPoolGen(javaClass.getConstantPool());
            var methods = javaClass.getMethods();
            return stream(methods).map(method -> newCallPoint(component, componentType, method, javaClass,
                    dependencyToDependentMap, unevaluatedHandler, constantPoolGen));
        }).collect(toList());
    }

    public static CallPoint newCallPoint(Component component, Class<?> componentType,
                                         Method method, JavaClass javaClass,
                                         Map<Component, List<Component>> dependencyToDependentMap,
                                         Function<Result, Result> unevaluatedHandler,
                                         ConstantPoolGen constantPoolGen) {
        var code = method.getCode();

        var callPoints = new ArrayList<CallPoint>();
        var callPoints1 = new ArrayList<CallPoint>();

        var instructionHandles = instructionHandleStream(code).collect(toList());
        var instructionHandle = !instructionHandles.isEmpty()
                ? instructionHandles.get(instructionHandles.size() - 1) : null;

        while (instructionHandle != null) {
            var instruction = instructionHandle.getInstruction();
            if (instruction instanceof INVOKEVIRTUAL || instruction instanceof INVOKEINTERFACE
                    || instruction instanceof INVOKEDYNAMIC) {

                var instrCallPoints = new LinkedHashSet<CallPoint>();
                var eval = new EvalBytecode(component, dependencyToDependentMap, constantPoolGen,
                        getBootstrapMethods(javaClass), method);

                //debug info
                var instructionString = instruction.toString(constantPoolGen.getConstantPool());

                var invokeInstruction = (InvokeInstruction) instruction;
                var argumentTypes = invokeInstruction.getArgumentTypes(constantPoolGen);
                var evalArguments = eval.evalArguments(instructionHandle, argumentTypes, unevaluatedHandler);
                var invokeObject = eval.evalInvokeObject(invokeInstruction, evalArguments, unevaluatedHandler);

                var invokeDynamics = findInvokeDynamicCalls(
                        instructionHandle,
                        invokeObject.getLastInstruction(),
                        invokeObject.getFirstInstruction(),
                        javaClass, constantPoolGen
                );
                if (!invokeDynamics.isEmpty()) {
                    instrCallPoints.addAll(invokeDynamics);
                } else {
                    var invokeCallPoint = newInvokeCallPoint(instructionHandle, (InvokeInstruction) instruction, constantPoolGen);
                    instrCallPoints.add(invokeCallPoint);
                    var invokeCalls = findInvokeCalls(
                            invokeObject.getLastInstruction(),
                            invokeObject.getFirstInstruction(),
                            constantPoolGen
                    );
                    instrCallPoints.addAll(invokeCalls);
                }

                var arguments = evalArguments.getArguments();
                for (var argument : arguments) {
                    var invokeDynamicCalls = findInvokeDynamicCalls(
                            instructionHandle,
                            argument.getLastInstruction(),
                            argument.getFirstInstruction(),
                            javaClass, constantPoolGen);
                    if (!invokeDynamics.isEmpty()) {
                        instrCallPoints.addAll(invokeDynamicCalls);
                    } else  {
                        var invokeCalls = findInvokeCalls(
                                argument.getLastInstruction(),
                                argument.getFirstInstruction(),
                                constantPoolGen
                        );
                        instrCallPoints.addAll(invokeCalls);
                    }
                }

                callPoints1.addAll(instrCallPoints);

                var lastArgInstructionHandle = invokeObject.getLastInstruction();
                instructionHandle = lastArgInstructionHandle != null ? lastArgInstructionHandle.getPrev() : null;
            } else {
                instructionHandle = instructionHandle.getPrev();
            }
        }

        for (var iterator = instructionHandles.iterator(); iterator.hasNext(); ) {
            var instructionHandle1 = iterator.next();
            var instruction = instructionHandle1.getInstruction();
            if (instruction instanceof INVOKEVIRTUAL || instruction instanceof INVOKEINTERFACE || instruction instanceof INVOKEDYNAMIC) {
                var invokeInstruction = (InvokeInstruction) instruction;
                var methodName = invokeInstruction.getMethodName(constantPoolGen);
                var argumentTypes = /*getArgumentTypes*/(invokeInstruction.getArgumentTypes(constantPoolGen));

                final CallPoint callPoint;
                if (instruction instanceof INVOKEDYNAMIC) {
                    var methodInfo = getInvokeDynamicUsedMethodInfo((INVOKEDYNAMIC) instruction,
                            constantPoolGen, javaClass);
                    InstructionHandle next = instructionHandle1.getNext();
                    if (methodInfo != null) {
                        var argumentTypes1 = Type.getArgumentTypes(methodInfo.getSignature());
                        callPoint = CallPoint.builder()
                                .methodName(methodInfo.name)
                                .ownerClassName(methodInfo.objectType.getName())
                                .argumentTypes(argumentTypes1)
                                .instruction(instructionHandle1)
                                .build();
                    } else {
                        //log
                        callPoint = null;
                    }
                } else {
                    callPoint = CallPoint.builder()
                            .methodName(methodName)
                            .ownerClassName(invokeInstruction.getClassName(constantPoolGen))
                            .argumentTypes(argumentTypes)
                            .instruction(instructionHandle1)
                            .build();
                }
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
                .callPoints(callPoints1)
//                    .jumpsTo(jumpsTo)
                .build();
    }

    public static CallPoint newCallPoint(InstructionHandle instructionHandle, InvokeInstruction instruction, ConstantPoolGen constantPoolGen) {
        return CallPoint.builder()
                .methodName(instruction.getMethodName(constantPoolGen))
                .ownerClassName(instruction.getClassName(constantPoolGen))
                .argumentTypes((instruction.getArgumentTypes(constantPoolGen)))
                .instruction(instructionHandle)
                .build();
    }

    public static List<CallPoint> findInvokeDynamicCalls(InstructionHandle parent, InstructionHandle fromLast,
                                                         InstructionHandle toFirst, JavaClass javaClass,
                                                         ConstantPoolGen constantPoolGen) {
        var dest = new ArrayList<CallPoint>();
        ofNullable(newInvokeDynamicCallPoint(parent, parent, javaClass, constantPoolGen)).ifPresent(dest::add);
        while (fromLast != null && fromLast.getPosition() >= toFirst.getPosition()) {
            ofNullable(newInvokeDynamicCallPoint(parent, fromLast, javaClass, constantPoolGen)).ifPresent(dest::add);
            fromLast = fromLast.getPrev();
        }
        return dest;
    }

    private static CallPoint newInvokeDynamicCallPoint(InstructionHandle parent, InstructionHandle next,
                                                       JavaClass javaClass, ConstantPoolGen constantPoolGen) {
        var instruction = next.getInstruction();
        if (instruction instanceof INVOKEDYNAMIC) {
            var methodInfo = getInvokeDynamicUsedMethodInfo((INVOKEDYNAMIC) instruction, constantPoolGen, javaClass);
            if (methodInfo != null) {
                var argumentTypes = Type.getArgumentTypes(methodInfo.getSignature());
                return CallPoint.builder()
                        .methodName(methodInfo.name)
                        .ownerClassName(methodInfo.objectType.getName())
                        .argumentTypes(argumentTypes)
                        .instruction(parent)
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

    private static MethodInfo getInvokeDynamicUsedMethodInfo(INVOKEDYNAMIC instruction,
                                                             ConstantPoolGen constantPoolGen,
                                                             JavaClass javaClass) {
        var cp = constantPoolGen.getConstantPool();
        var constantInvokeDynamic = getConstantInvokeDynamic(instruction, cp);
        var bootstrapMethodAttrIndex = constantInvokeDynamic.getBootstrapMethodAttrIndex();
        var bootstrapMethods = getBootstrapMethods(javaClass);
        var bootstrapMethod = bootstrapMethods.getBootstrapMethods()[bootstrapMethodAttrIndex];
        var bootstrapMethodArguments = getBootstrapMethodArguments(bootstrapMethod, cp);
        return bootstrapMethodArguments.stream().map(constant -> constant instanceof ConstantMethodHandle
                ? newMethodInfo((ConstantMethodHandle) constant, cp) : null
        ).filter(Objects::nonNull).findFirst().orElse(null);
    }
}
