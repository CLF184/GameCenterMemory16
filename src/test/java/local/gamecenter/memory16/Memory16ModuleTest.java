package local.gamecenter.memory16;

import com.xiaomi.gamecenter.download.model.ClientInfo;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class Memory16ModuleTest {
    private static final String TARGET = "com.xiaomi.gamecenter";

    private record Loaded(Map<Method, XposedInterface.Hooker> hooks, Set<Method> deoptimized) {}

    private static Loaded load(String process, String packageName) {
        Map<Method, XposedInterface.Hooker> hooks = new HashMap<>();
        Set<Method> deoptimized = new HashSet<>();
        XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(), new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hook")) {
                        Method target = (Method) args[0];
                        return Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                                new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, call, values) -> {
                                    if (call.getName().equals("intercept")) {
                                        check(!hooks.containsKey(target), "Duplicate hook registration");
                                        hooks.put(target, (XposedInterface.Hooker) values[0]);
                                        return null;
                                    }
                                    return builder;
                                });
                    }
                    if (method.getName().equals("deoptimize")) {
                        deoptimized.add((Method) args[0]);
                        return true;
                    }
                    if (method.getName().equals("log")) return null;
                    throw new AssertionError("Unexpected framework call: " + method);
                });
        var module = new Memory16Module();
        module.attachFramework(framework, () -> {});
        module.onModuleLoaded(new XposedModuleInterface.ModuleLoadedParam() {
            @Override public boolean isSystemServer() { return false; }
            @Override public String getProcessName() { return process; }
        });
        var ready = (XposedModuleInterface.PackageReadyParam) Proxy.newProxyInstance(
                XposedModuleInterface.class.getClassLoader(),
                new Class<?>[]{XposedModuleInterface.PackageReadyParam.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getPackageName" -> packageName;
                        case "getClassLoader" -> Memory16ModuleTest.class.getClassLoader();
                        default -> throw new AssertionError("Unexpected package callback: " + method);
                    };
                });
        module.onPackageReady(ready);
        module.onPackageReady(ready);
        return new Loaded(hooks, deoptimized);
    }

    private static Object intercept(XposedInterface.Hooker hook, Object result) throws Throwable {
        int[] originalCalls = {0};
        var chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("proceed")) {
                        throw new AssertionError("Unexpected chain call: " + method);
                    }
                    originalCalls[0]++;
                    if (result instanceof Throwable failure) throw failure;
                    return result;
                });
        try {
            return hook.intercept(chain);
        } finally {
            check(originalCalls[0] == 1, "Original serialization must run exactly once");
        }
    }

    public static void main(String[] args) throws Throwable {
        for (String process : new String[]{TARGET, TARGET + ":remote"}) {
            Loaded loaded = load(process, TARGET);
            check(loaded.hooks().size() == 1, "Expected exactly the device serialization hook");
            Method method = ClientInfo.DeviceInfo.class.getDeclaredMethod("toJson");
            check(loaded.hooks().containsKey(method), "Wrong hook target");
            check(loaded.deoptimized().contains(ClientInfo.class.getDeclaredMethod("toJson")),
                    "Direct caller must be deoptimized");
            var source = new ClientInfo.DeviceInfo();
            JSONObject json = source.toJson();
            JSONObject expected = new JSONObject(json.toString()).put("totalMemoryKb", 16777216);
            Object result = intercept(loaded.hooks().get(method), json);
            check(result == json && expected.similar(json), "Only upload memory may change");
            check(source.totalMemoryKb == 24 * 1024 * 1024, "Real device memory must remain unchanged");
            check(json.get("totalMemoryKb") instanceof Integer, "Memory field must remain an integer");
            check(intercept(loaded.hooks().get(method), null) == null, "Null result must be preserved");
            JSONObject missing = new JSONObject().put("device", "test-device");
            intercept(loaded.hooks().get(method), missing);
            check(!missing.has("totalMemoryKb"), "Do not add absent fields after upstream changes");
            Exception failure = new Exception("upstream serialization failed");
            try {
                intercept(loaded.hooks().get(method), failure);
                throw new AssertionError("Original exception was swallowed");
            } catch (Exception actual) {
                check(actual == failure, "Original exception must propagate");
            }
        }
        for (String process : new String[]{"android", "com.xiaomi.winplay", TARGET + ".fake"}) {
            check(load(process, TARGET).hooks().isEmpty(), "Unexpected process hooked: " + process);
        }
        check(load(TARGET, "com.xiaomi.winplay").hooks().isEmpty(), "Unexpected package hooked");
        System.out.println("PASS: scope, actual hook target, duplicate callbacks, original execution,");
        System.out.println("      upload-only 16 GiB, preserved fields/state, null/missing data, exceptions");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
