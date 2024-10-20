package io.github.m4gshm.connections.client;

import io.github.m4gshm.connections.eval.bytecode.CallCacheKey;
import io.github.m4gshm.connections.eval.bytecode.Eval;
import io.github.m4gshm.connections.eval.bytecode.NotInvokedException;
import io.github.m4gshm.connections.eval.result.DelayInvoke;
import io.github.m4gshm.connections.eval.result.Result;
import io.github.m4gshm.connections.model.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

import static io.github.m4gshm.connections.eval.bytecode.Eval.toParameters;
import static io.github.m4gshm.connections.eval.bytecode.StringifyUtils.stringifyUnresolved;
@Slf4j
public class Utils {
    static List<List<Result>> resolveInvokeParameters(Eval eval, DelayInvoke invoke, Component component,
                                                      String methodName, Map<CallCacheKey, Result> callCache) {
        var parameters = toParameters(invoke.getObject(), invoke.getArguments());
        try {
            return eval.resolveInvokeParameters(invoke, parameters, (current, ex) -> stringifyUnresolved(current, ex, callCache), true);
        } catch (NotInvokedException e) {
            log.info("no call variants for {} inside {}", methodName, component.getName());
            return List.of();
        }
    }
}
