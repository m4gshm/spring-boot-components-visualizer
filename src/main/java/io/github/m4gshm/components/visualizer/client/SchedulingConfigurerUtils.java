package io.github.m4gshm.components.visualizer.client;

import io.github.m4gshm.components.visualizer.ComponentsExtractor.ScheduledMethod;
import io.github.m4gshm.components.visualizer.ComponentsExtractor.ScheduledMethod.TriggerType;
import io.github.m4gshm.components.visualizer.eval.bytecode.CallCacheKey;
import io.github.m4gshm.components.visualizer.eval.bytecode.Eval;
import io.github.m4gshm.components.visualizer.eval.bytecode.EvalContextFactory;
import io.github.m4gshm.components.visualizer.eval.result.DelayInvoke;
import io.github.m4gshm.components.visualizer.eval.result.DelayLoadFromStore;
import io.github.m4gshm.components.visualizer.eval.result.Resolver;
import io.github.m4gshm.components.visualizer.eval.result.Result;
import io.github.m4gshm.components.visualizer.model.Component;
import io.github.m4gshm.components.visualizer.model.MethodId;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.experimental.FieldDefaults;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.github.m4gshm.components.visualizer.Utils.classByName;
import static io.github.m4gshm.components.visualizer.client.Utils.getBootstrapMethods;
import static io.github.m4gshm.components.visualizer.eval.bytecode.EvalUtils.*;
import static io.github.m4gshm.components.visualizer.eval.bytecode.InvokeDynamicUtils.getBootstrapMethodHandlerAndArguments;
import static io.github.m4gshm.components.visualizer.eval.bytecode.InvokeDynamicUtils.getInvokeDynamicUsedMethodInfo;
import static io.github.m4gshm.components.visualizer.model.MethodId.newMethodId;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.of;
import static lombok.AccessLevel.PRIVATE;
import static org.apache.bcel.generic.Type.getArgumentTypes;
import static org.apache.bcel.generic.Type.getType;

@Slf4j
@UtilityClass
public class SchedulingConfigurerUtils {
    public static List<ScheduledMethod> getScheduledByConfigurerMethods(Component component, Class<?> componentType,
                                                                        Function<TimeUnit, String> timeUnitStringifier,
                                                                        EvalContextFactory evalContextFactory,
                                                                        Map<CallCacheKey, Result> callCache,
                                                                        Resolver resolver) {
        if (SchedulingConfigurer.class.isAssignableFrom(componentType)) {
            var configureTasksMethodClassPair = getClassAndMethodSource(componentType, "configureTasks",
                    getType(ScheduledTaskRegistrar.class));
            if (configureTasksMethodClassPair != null) {
                var method = configureTasksMethodClassPair.getValue();
                var source = configureTasksMethodClassPair.getKey();
                var constantPoolGen = new ConstantPoolGen(method.getConstantPool());
                return instructionHandleStream(method).flatMap(instructionHandle -> {
                    var instruction = instructionHandle.getInstruction();
                    if (instruction instanceof INVOKEVIRTUAL) {
                        var invokevirtual = (INVOKEVIRTUAL) instruction;
                        var methodName = invokevirtual.getMethodName(constantPoolGen);
                        var fixedRate = "addFixedRateTask".equals(methodName);
                        var fixeDelay = "addFixedDelayTask".equals(methodName);
                        var cron = "addCronTask".equals(methodName);
                        var triggerType = fixedRate ? TriggerType.fixedRate : fixeDelay
                                ? TriggerType.fixedDelay : cron
                                ? TriggerType.cron : null;
                        if (triggerType != null) {
                            return extractScheduledMethods(triggerType, instructionHandle, component, componentType,
                                    source, method, evalContextFactory, callCache, resolver, timeUnitStringifier).stream();
                        }
                    }
                    return of();
                }).collect(toList());
            }
        }
        return List.of();
    }

    @SneakyThrows
    private static List<ScheduledMethod> extractScheduledMethods(
            TriggerType triggerType, InstructionHandle runnableInstruction, Component component,
            Class<?> componentType, JavaClass source, Method method, EvalContextFactory evalContextFactory,
            Map<CallCacheKey, Result> callCache, Resolver resolver, Function<TimeUnit, String> timeUnitStringifier
    ) {
        var evalContext = evalContextFactory.getEvalContext(component, source, method);
        var evaluated = evalContext.eval(runnableInstruction, callCache);
        if (evaluated instanceof DelayInvoke) {
            var delayInvoke = (DelayInvoke) evaluated;
            var arguments = delayInvoke.getArguments();
            var argAmount = arguments.size();
            if (argAmount == 1) {
                var taskExpr = arguments.get(0);
                var runnableAndTriggerExpr = findTaskConstructorExpr(source, method, evalContextFactory, resolver,
                        taskExpr.getFirstInstruction(), evalContext);
                var runnableExpr = runnableAndTriggerExpr != null ? runnableAndTriggerExpr.getRunnableExpr() : null;
                var triggerExpr = runnableAndTriggerExpr != null ? runnableAndTriggerExpr.getTriggerExpr() : null;
                if (runnableExpr != null && triggerExpr != null) {
                    var runnable = runnableExpr.getValue();
                    if (runnable instanceof Runnable) {
                        return getScheduledMethods(triggerType, timeUnitStringifier, triggerExpr, component,
                                componentType, source, (Runnable) runnable, runnableExpr, evalContext, null);
                    } else {
                        //log
                    }
                } else {
                    //log
                }
            } else if (argAmount == 2) {
                var runnableExpr = arguments.get(0);
                var runnableResolved = evalContext.resolve(runnableExpr, resolver);
                var runnable = runnableResolved.getValue();
                if (runnable instanceof Runnable) {
                    var triggerExpr = arguments.get(1);
                    return getScheduledMethods(triggerType, timeUnitStringifier, triggerExpr, component,
                            componentType, source, (Runnable) runnable, runnableExpr, evalContext, null);
                } else {
                    //log
                }
            }
        } else {
            //log
        }
        return List.of();
    }

    //todo need loop control
    private static ScheduledRoutineAndTrigger findTaskConstructorExpr(JavaClass javaClass, Method method,
                                                                      EvalContextFactory evalContextFactory,
                                                                      Resolver resolver, InstructionHandle first,
                                                                      Eval evalContext
    ) throws ClassNotFoundException {
        var constantPoolGen = new ConstantPoolGen(method.getConstantPool());
        var callCache = new HashMap<CallCacheKey, Result>();
        var instruction = first.getInstruction();
        var intervalTaskConstructor = isConstructorOfClass(instruction, IntervalTask.class, constantPoolGen);
        var cronTaskConstructor = isConstructorOfClass(instruction, CronTask.class, constantPoolGen);
        if (intervalTaskConstructor || cronTaskConstructor) {
            var delayInvokeExpr = (DelayInvoke) evalContext.eval(first, callCache);
            var argumentsExpr = delayInvokeExpr.getArguments();
            var invokespecial = (InvokeInstruction) instruction;
            var argumentTypes = invokespecial.getArgumentTypes(constantPoolGen);
            return getRunnableAndTriggerExpr(argumentTypes, argumentsExpr, evalContext, resolver);
        } else if (instruction instanceof INVOKEDYNAMIC) {
            throw new UnsupportedOperationException("TODO INVOKEDYNAMIC");
        } else if (instruction instanceof InvokeInstruction) {
            var invokeInstruction = (InvokeInstruction) instruction;
            var className = invokeInstruction.getClassName(constantPoolGen);
            var methodName = invokeInstruction.getMethodName(constantPoolGen);
            var argumentTypes = invokeInstruction.getArgumentTypes(constantPoolGen);

            var delayInvokeExpr = (DelayInvoke) evalContext.eval(first, callCache);

            var object = delayInvokeExpr.getObject();
            if (object instanceof DelayLoadFromStore) {
                var delayLoad = (DelayLoadFromStore) object;
                object = delayLoad.getStoreInstructions().get(0);
            }

            if (object instanceof DelayInvoke) {
                var objectInstruction = object.getFirstInstruction().getInstruction();
                if (objectInstruction instanceof INVOKEDYNAMIC) {
                    var bootstrapMethods = getBootstrapMethods(javaClass);
                    var bootstrapMethodAndArguments = getBootstrapMethodHandlerAndArguments(
                            (INVOKEDYNAMIC) objectInstruction, bootstrapMethods, constantPoolGen);

                    var sourceMethodInfo = bootstrapMethodAndArguments.getSourceMethodInfo();
                    if (sourceMethodInfo != null) {
                        var methodName1 = sourceMethodInfo.getName();
                        var className1 = sourceMethodInfo.getClassName();
                        var argumentTypes1 = getArgumentTypes(sourceMethodInfo.getSignature());

                        var intervalTaskConstructor1 = isConstructorOfClass(methodName1, className1, IntervalTask.class);
                        var cronTaskConstructor1 = isConstructorOfClass(methodName1, className1, CronTask.class);
                        if (intervalTaskConstructor1 || cronTaskConstructor1) {
                            var argumentsExpr1 = delayInvokeExpr.getArguments();
                            return getRunnableAndTriggerExpr(argumentTypes1, argumentsExpr1, evalContext, resolver);
                        }
                    }
                } else {
                    //
                }
            } else {
                //log
            }

            //check return type is Task
            var bean = object != null ? object.getValue() : null;

            var parentComponent = evalContext.getComponent();
            var same = parentComponent != null && bean != null
                    ? parentComponent.getBean() == bean
                    : evalContext.getClassName().equals(className);
            var component1 = same ? parentComponent : bean != null ? Component.builder()
                    .bean(bean)
                    .path((parentComponent != null ? parentComponent.getPath() + "." : "") + method.getName())
                    .build() : null;
            final JavaClass javaClass1;
            final Method method1;
            if (same) {
                javaClass1 = javaClass;
                var sameMethod = method.getName().equals(methodName) && Arrays.equals(method.getArgumentTypes(), argumentTypes);
                method1 = sameMethod ? method : getMethodsStream(javaClass).filter(byNameAndArgs(methodName, argumentTypes))
                        .findFirst().orElseThrow(() -> methodNotFoundException(javaClass.getClassName(), methodName,
                                argumentTypes)).getValue();
            } else {
                var classAndMethodSource = getClassAndMethodSource(getClassByName(className), methodName, argumentTypes);
                if (classAndMethodSource == null) {
                    throw methodNotFoundException(className, methodName, argumentTypes);
                }
                javaClass1 = classAndMethodSource.getKey();
                method1 = classAndMethodSource.getValue();
            }
            var evalContext1 = evalContextFactory.getEvalContext(component1, javaClass1, method1)
                    .withArguments2(0, delayInvokeExpr.getArguments());

            var instructionHandles = instructionHandleStream(method1).collect(toList());

            var last1 = !instructionHandles.isEmpty() ? instructionHandles.get(instructionHandles.size() - 1) : null;

            var nextExp = evalContext1.eval(last1, callCache);

            return findTaskConstructorExpr(javaClass1, method1, evalContextFactory, resolver,
                    nextExp.getFirstInstruction(), evalContext1);
        }
        return null;
    }

    private static IllegalStateException methodNotFoundException(String className, String methodName, Type[] argumentTypes) {
        return new IllegalStateException("no method '" + methodName +
                "' with args " + asList(argumentTypes) + " in class " +
                className);
    }

    public static boolean isConstructorOfClass(Instruction instruction, Class<?> clazz, ConstantPoolGen constantPoolGen) throws ClassNotFoundException {
        if (!(instruction instanceof INVOKESPECIAL)) {
            return false;
        }
        var invokespecial = (INVOKESPECIAL) instruction;
        return isConstructorOfClass(invokespecial.getName(constantPoolGen), invokespecial.getClassName(constantPoolGen), clazz);
    }

    private static boolean isConstructorOfClass(String methodName, String className, Class<?> clazz) throws ClassNotFoundException {
        var isConstructor = "<init>".equals(methodName);
        if (!isConstructor) {
            return false;
        }
        var aClass = classByName(className);
        return clazz.isAssignableFrom(aClass);
    }

    private static ScheduledRoutineAndTrigger getRunnableAndTriggerExpr(Type[] argumentTypes, List<Result> argumentsExpr,
                                                                        Eval evalContext, Resolver resolver) {
        Result runnableExpr = null;
        Result triggerExpr = null;
        for (int i = 0; i < argumentTypes.length; i++) {
            var argumentType = argumentTypes[i];
            var argClass = findClassByName(argumentType.getClassName());
            if (argClass != null && Runnable.class.isAssignableFrom(argClass)) {
                runnableExpr = argumentsExpr.get(i);// evalContext.resolve(argumentsExpr.get(i), resolver);
            } else {
                triggerExpr = argumentsExpr.get(i);
            }
            if (runnableExpr != null && triggerExpr != null) {
                return new ScheduledRoutineAndTrigger(runnableExpr, triggerExpr);
            }
        }
        return null;
    }

    private static List<ScheduledMethod> getScheduledMethods(TriggerType triggerType,
                                                             Function<TimeUnit, String> timeUnitStringifier,
                                                             Result triggerExpr, Component component,
                                                             Class<?> componentType, JavaClass source,
                                                             Runnable runnable, Result runnableExpr,
                                                             Eval evalContext, Resolver resolver
    ) {
        final Stream<MethodId> methodIdStream;
        if (component.getBean().equals(runnable)) {
            methodIdStream = of(newMethodId("run"));
        } else {
            var runnableClass = runnable.getClass();
            var touched = new HashMap<String, Set<MethodId>>();
            List<MethodId> scheduledMethodIds;
            if (isLambda(runnableClass)) {
//                JavaClass javaClass;
//                try {
//                    String name = runnableClass.getName();
//                    int split = name.lastIndexOf("/");
//                    if(split >=0) {
//                        String tail = name.substring(0);
////                        tail.matches("/\\d.+");
//                        name = name.substring(0, split);
//                    }
//                    javaClass = LambdaProxyClassesDumper.loadClass(name);
//                } catch (ClassNotFoundException e) {
//                    log.error("load lambda bytecode error", e);
//                    javaClass = null;
//                }
                scheduledMethodIds = getMethodIds(componentType, runnableExpr.getFirstInstruction(),
                        evalContext.getConstantPoolGen(), getBootstrapMethods(source), touched);
            } else {
                scheduledMethodIds = getScheduledMethodIdsFromMethod(componentType,
                        getClassAndMethodSource(runnableClass, "run"), touched);
            }
            methodIdStream = scheduledMethodIds.stream();
        }

        var triggerValues = evalContext.resolve(triggerExpr, resolver).getValue(resolver);
        var triggerExpressions = resolveTriggerExpression(triggerType, triggerValues, triggerExpr,
                resolver, timeUnitStringifier);

        if (!triggerValues.isEmpty() && triggerExpressions.isEmpty()) {
            throw new IllegalStateException("trigger expression is empty for trigger values " + triggerValues);
        }

        return methodIdStream.flatMap(methodId -> getScheduledMethodStream(triggerType, methodId,
                triggerExpressions)).collect(toList());
    }

    private static boolean isLambda(Class<?> aClass) {
        return aClass.isSynthetic() && aClass.getSimpleName().contains("$$Lambda");
    }

    private static List<MethodId> getScheduledMethodIdsFromMethod(
            Class<?> beanType, Entry<JavaClass, Method> runClassMethodPair, Map<String, Set<MethodId>> touched
    ) {
        var source = runClassMethodPair.getKey();
        var runMethod = runClassMethodPair.getValue();

        var bootstrapMethods = getBootstrapMethods(source);
        var constantPoolGen = new ConstantPoolGen(source.getConstantPool());

        return instructionHandleStream(runMethod.getCode()).map(instructionHandle -> {
            return getMethodIds(beanType, instructionHandle, constantPoolGen, bootstrapMethods, touched);
        }).flatMap(Collection::stream).collect(toList());
    }

    private static List<MethodId> getMethodIds(Class<?> beanType, InstructionHandle instructionHandle,
                                               ConstantPoolGen constantPoolGen, BootstrapMethods bootstrapMethods,
                                               Map<String, Set<MethodId>> touched) {
        var instruction = instructionHandle.getInstruction();
        if (instruction instanceof INVOKEDYNAMIC) {
            return getMethodIds(beanType, (INVOKEDYNAMIC) instruction, constantPoolGen, bootstrapMethods, touched);
        } else if (instruction instanceof InvokeInstruction) {
            return getMethodIds(beanType, instructionHandle, constantPoolGen, touched);
        } else {
            return List.of();
        }
    }

    private static List<MethodId> getMethodIds(Class<?> componentType,
                                               InstructionHandle instructionHandle,
                                               ConstantPoolGen constantPoolGen,
                                               Map<String, Set<MethodId>> touched) {
        var instruction = (InvokeInstruction) instructionHandle.getInstruction();
        var methodName = instruction.getMethodName(constantPoolGen);
        var type = instruction.getLoadClassType(constantPoolGen);
        var argumentTypes = instruction.getArgumentTypes(constantPoolGen);
        if (getType(componentType).equals(type)) {
            return List.of(newMethodId(methodName, argumentTypes));
        } else {
            return getMethodIds(componentType, type.getClassName(), methodName, argumentTypes, touched);
        }
    }

    private static List<MethodId> getMethodIds(Class<?> componentType, INVOKEDYNAMIC invokedynamic,
                                               ConstantPoolGen constantPoolGen, BootstrapMethods bootstrapMethods,
                                               Map<String, Set<MethodId>> touched) {
        var methodInfo = getInvokeDynamicUsedMethodInfo(invokedynamic, constantPoolGen, bootstrapMethods);
        var methodName = methodInfo.getName();
        var methodInfoClassName = methodInfo.getClassName();
        var argumentTypes = getArgumentTypes(methodInfo.getSignature());
        if (methodInfoClassName.equals(componentType.getName())) {
            return List.of(newMethodId(methodName, argumentTypes));
        } else {
            return getMethodIds(componentType, methodInfo.getClassName(), methodName, argumentTypes, touched);
        }
    }

    private static List<MethodId> getMethodIds(Class<?> componentType, String className,
                                               String methodName, Type[] argumentTypes,
                                               Map<String, Set<MethodId>> touched) {
        if (className.startsWith("java")) {
            return List.of();
        } else {
            var add = touched.computeIfAbsent(className, k -> new HashSet<>()).add(newMethodId(methodName, argumentTypes));
            if (!add) {
                //recursion detected
                return List.of();
            }
            var classAndMethodSource = getClassAndMethodSource(getClassByName(className), methodName, argumentTypes);
            return getScheduledMethodIdsFromMethod(componentType, classAndMethodSource, touched);
        }
    }

    private static List<String> resolveTriggerExpression(TriggerType triggerType, List<Object> triggerValues,
                                                         Result triggerExpr, Resolver resolver,
                                                         Function<TimeUnit, String> timeUnitStringifier) {
        switch (triggerType) {
            case fixedDelay:
            case fixedRate:
                var millisecs = triggerValues.stream().map(value -> value instanceof Number ? ((Number) value).longValue() : null)
                        .filter(Objects::nonNull).collect(toList());
                var timeUnits = getTimeUnits(triggerExpr, resolver);
                return millisecs.stream().flatMap(millisec ->
                        getTimeExpressionStream(millisec, timeUnits, timeUnitStringifier)).collect(toList());
            default:
                return triggerValues.stream().map(v -> "" + v).collect(toList());
        }
    }

    private static List<TimeUnit> getTimeUnits(Result triggerExpr, Resolver resolver) {
        List<TimeUnit> timeUnits;
        if (triggerExpr instanceof DelayInvoke) {
            var object1 = ((DelayInvoke) triggerExpr).getObject();
            timeUnits = object1 != null ? object1.getValue(resolver).stream()
                    .map(object -> object instanceof TimeUnit ? (TimeUnit) object : null).filter(Objects::nonNull)
                    .collect(toList()) : List.of(MILLISECONDS);
        } else timeUnits = List.of(MILLISECONDS);
        return timeUnits;
    }

    private static Stream<String> getTimeExpressionStream(long millisec, Collection<TimeUnit> timeUnits,
                                                          Function<TimeUnit, String> timeUnitStringifier) {
        return timeUnits.stream().map(timeUnit -> getTimeExpression(millisec, timeUnit, timeUnitStringifier));
    }

    private static String getTimeExpression(long millisec, TimeUnit timeUnit, Function<TimeUnit, String> timeUnitStringifier) {
        return timeUnit.convert(millisec, MILLISECONDS) + timeUnitStringifier.apply(timeUnit);
    }

    private static Stream<ScheduledMethod> getScheduledMethodStream(TriggerType triggerType, MethodId methodId,
                                                                    List<String> triggerExpressions) {
        return triggerExpressions.stream().map(expression -> ScheduledMethod.builder()
                .method(methodId)
                .expression(expression)
                .triggerType(triggerType)
                .build());
    }

    @Data
    @FieldDefaults(makeFinal = true, level = PRIVATE)
    public class ScheduledRoutineAndTrigger {
        Result runnableExpr;
        Result triggerExpr;
    }

}