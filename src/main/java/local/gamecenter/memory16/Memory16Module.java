package local.gamecenter.memory16;

import android.util.Log;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

public final class Memory16Module extends XposedModule {
    private static final String TARGET = "com.xiaomi.gamecenter";
    private static final String CLIENT_INFO = TARGET + ".download.model.ClientInfo";
    private static final String TAG = "GameCenterMemory16";
    private static final int MEMORY_KB = 16 * 1024 * 1024;
    private final AtomicBoolean loggedRewrite = new AtomicBoolean();
    private boolean targetProcess;
    private boolean installed;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        String process = param.getProcessName();
        targetProcess = TARGET.equals(process) || process.startsWith(TARGET + ":");
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!targetProcess || !TARGET.equals(param.getPackageName()) || installed) return;
        try {
            ClassLoader loader = param.getClassLoader();
            Method serialize = Class.forName(CLIENT_INFO + "$DeviceInfo", false, loader)
                    .getDeclaredMethod("toJson");
            if (serialize.getReturnType() != JSONObject.class) {
                log(Log.ERROR, TAG, "Unexpected DeviceInfo.toJson return type; hook skipped");
                return;
            }
            hook(serialize).setId("gamecenter:upload-memory-16g").intercept(chain -> {
                Object result = chain.proceed();
                if (result instanceof JSONObject json && json.has("totalMemoryKb")) {
                    int previous = json.optInt("totalMemoryKb");
                    json.put("totalMemoryKb", MEMORY_KB);
                    if (loggedRewrite.compareAndSet(false, true)) {
                        log(Log.INFO, TAG, "Upload totalMemoryKb: " + previous + " -> " + MEMORY_KB);
                    }
                }
                return result;
            });
            installed = true;
            log(Log.INFO, TAG, "Installed DeviceInfo.toJson hook; upload memory = 16 GiB");
            // The encrypted request header is built by this direct caller.
            Method caller = Class.forName(CLIENT_INFO, false, loader).getDeclaredMethod("toJson");
            if (!deoptimize(caller)) {
                log(Log.WARN, TAG, "Caller deoptimization unavailable; verify rewrite log on device");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log(Log.ERROR, TAG, "Memory hook setup failed; check installed game center version", e);
        }
    }
}
