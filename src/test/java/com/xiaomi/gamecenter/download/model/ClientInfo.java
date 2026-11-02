package com.xiaomi.gamecenter.download.model;

import org.json.JSONObject;

/** Test fixture with the actual app's serialization boundary and field names. */
public final class ClientInfo {
    public JSONObject toJson() throws Exception {
        return new JSONObject().put("deviceInfo", new DeviceInfo().toJson());
    }

    public static final class DeviceInfo {
        public final int totalMemoryKb = 24 * 1024 * 1024;

        public JSONObject toJson() throws Exception {
            return new JSONObject().put("device", "test-device").put("model", "test-model")
                    .put("sdkVersion", 37).put("locale", "zh_CN")
                    .put("totalMemoryKb", totalMemoryKb);
        }
    }
}
