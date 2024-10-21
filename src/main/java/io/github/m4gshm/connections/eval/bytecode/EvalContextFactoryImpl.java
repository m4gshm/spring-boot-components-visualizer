package io.github.m4gshm.connections.eval.bytecode;

import io.github.m4gshm.connections.eval.result.Result;
import io.github.m4gshm.connections.eval.result.Variable;
import io.github.m4gshm.connections.model.CallPoint;
import io.github.m4gshm.connections.model.Component;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.bcel.classfile.BootstrapMethods;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.Type;

import java.util.*;
import java.util.stream.Stream;

import static io.github.m4gshm.connections.CallPointsHelper.getCallsHierarchy;
import static io.github.m4gshm.connections.Utils.classByName;
import static java.util.Map.entry;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Stream.concat;
import static lombok.AccessLevel.PROTECTED;

@Slf4j
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = PROTECTED)
public class EvalContextFactoryImpl implements EvalContextFactory {

    Map<Component, List<Component>> dependencyToDependentMap;
    Map<Component, List<CallPoint>> callPointsCache;
    Map<CallCacheKey, Result> callCache;

    public static List<List<Result>> computeArgumentVariants(Component component, Method method,
                                                             Map<Component, List<Component>> dependencyToDependentMap,
                                                             Map<Component, List<CallPoint>> callPointsCache,
                                                             Map<CallCacheKey, Result> callCache,
                                                             EvalContextFactory evalContextFactory) {
        var componentType = component.getType();
        var methodName = method.getName();
        var argumentTypes = method.getArgumentTypes();
        var methodCallPoints = getCallPoints(componentType, methodName, argumentTypes,
                getDependentOnThisComponent(dependencyToDependentMap, component), callPointsCache);
        var methodArgumentVariants = getEvalCallPointVariants(methodCallPoints, callCache, evalContextFactory);
        return methodArgumentVariants.values().stream()
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .flatMap(e -> e.getValue().stream()).map(Eval.EvalArguments::getArguments)
                .distinct().collect(toList());
    }

    public static List<Component> getDependentOnThisComponent(
            Map<Component, List<Component>> dependencyToDependentMap, Component component) {
        var dependencies = dependencyToDependentMap.getOrDefault(component, List.of());
        return concat(Stream.of(component),
                dependencies.stream()).collect(toList());
    }

   public static Map<Component, Map<CallPoint, List<CallPoint>>> getCallPoints(
            Class<?> objectClass, String methodName, Type[] argumentTypes, List<Component> dependentOnThisComponent,
            Map<Component, List<CallPoint>> callPointsCache) {
        return dependentOnThisComponent.stream().map(dependentComponent -> {
            var callPoints = getCallsHierarchy(dependentComponent, callPointsCache);
            var callersWithVariants = callPoints.stream().map(dependentMethod -> {
                var matchedCallPoints = getMatchedCallPoints(dependentMethod, methodName, argumentTypes, objectClass);
                return entry(dependentMethod, matchedCallPoints);
            }).filter(e -> !e.getValue().isEmpty()).collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
            return !callersWithVariants.isEmpty() ? entry(dependentComponent, callersWithVariants) : null;
        }).filter(Objects::nonNull).collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    static Map<Component, Map<CallPoint, List<Eval.EvalArguments>>> getEvalCallPointVariants(
            Map<Component, Map<CallPoint, List<CallPoint>>> callPoints,
            Map<CallCacheKey, Result> callCache, EvalContextFactory evalContextFactory
    ) {
        return callPoints.entrySet().stream().map(e -> {
            var dependentComponent = e.getKey();
            var callPointListMap = e.getValue();
            var variants = callPointListMap.entrySet().stream().map(ee -> {
                        var callPoint = ee.getKey();
                        var matchedCallPoints = ee.getValue();
                        var javaClass = callPoint.getJavaClass();
                        var method = callPoint.getMethod();
                        var eval = evalContextFactory.getEvalContext(dependentComponent, javaClass, method);
                        return evalCallPointArgumentVariants(callPoint, matchedCallPoints, eval, callCache);
                    }
            ).filter(Objects::nonNull).collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
            return entry(dependentComponent, variants);
        }).collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    static Map.Entry<CallPoint, List<Eval.EvalArguments>> evalCallPointArgumentVariants(
            CallPoint dependentMethod, List<CallPoint> matchedCallPoints,
            Eval eval, Map<CallCacheKey, Result> callCache
    ) {
        var argVariants = matchedCallPoints.stream().map(callPoint -> {
            try {
                return Eval.evalArguments(callPoint, eval, callCache);
            } catch (EvalBytecodeException e) {
                var result = (e instanceof UnresolvedResultException) ? ((UnresolvedResultException) e).getResult() : null;
                if (result instanceof Variable) {
                    var variable = (Variable) result;
                    var evalContext = variable.getEvalContext();
                    var variableMethod = evalContext.getMethod();
                    log.info("{} is aborted, cannot evaluate variable {}, in method {} {} of {}", "evalCallPointArgumentVariants",
                            variable.getName(), variableMethod.getName(),
                            variableMethod.getSignature(), evalContext.getComponent().getType()
                    );
                } else if (result != null) {
                    log.info("{} is aborted, cannot evaluate result '{}'", "evalCallPointArgumentVariants", result);
                } else {
                    log.info("{} is aborted by error", "evalCallPointArgumentVariants", e);
                }
                return List.<Eval.EvalArguments>of();
            }
        }).flatMap(Collection::stream).filter(Objects::nonNull).collect(toList());
        return !argVariants.isEmpty() ? entry(dependentMethod, argVariants) : null;
    }

    static List<CallPoint> getMatchedCallPoints(CallPoint dependentMethod, String methodName,
                                                Type[] argumentTypes, Class<?> objectClass) {
        return dependentMethod.getCallPoints().stream().filter(calledMethodInsideDependent -> {
            var match = isMatch(methodName, argumentTypes, objectClass, calledMethodInsideDependent);
            var cycled = isMatch(dependentMethod.getMethodName(), dependentMethod.getArgumentTypes(),
                    dependentMethod.getOwnerClass(), calledMethodInsideDependent);
            //exclude cycling
            return match && !cycled;
        }).collect(toList());
    }

    static boolean isMatch(String expectedMethodName, Type[] expectedArguments, Class<?> objectClass,
                           CallPoint calledMethodInsideDependent) {
        var calledMethod = calledMethodInsideDependent.getMethodName();
        var calledMethodArgumentTypes = calledMethodInsideDependent.getArgumentTypes();
        var calledMethodClass = getCalledMethodClass(calledMethodInsideDependent);

        var methodEquals = expectedMethodName.equals(calledMethod);
        var argumentsEqual = Arrays.equals(expectedArguments, calledMethodArgumentTypes);
        var classEquals = calledMethodClass != null && calledMethodClass.isAssignableFrom(objectClass);
        return methodEquals && argumentsEqual && classEquals;
    }

    static Class<?> getCalledMethodClass(CallPoint calledMethodInsideDependent) {
        var ownerClass = calledMethodInsideDependent.getOwnerClass();
        var ownerClassName = calledMethodInsideDependent.getOwnerClassName();
        Class<?> calledMethodClass = null;
        try {
            calledMethodClass = ownerClass == null ? classByName(ownerClassName) : ownerClass;
        } catch (ClassNotFoundException e) {
            log.debug("getCalledMethodClass", e);
        }
        return calledMethodClass;
    }

    @Override
    public Eval getEvalContext(Component component, Method method, BootstrapMethods bootstrapMethods) {
        return new Eval(component,
                new ConstantPoolGen(method.getConstantPool()),
                bootstrapMethods, method,
                computeArgumentVariants(component, method,
                        dependencyToDependentMap, callPointsCache, callCache, this));
    }
}
