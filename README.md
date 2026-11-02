# GameCenterMemory16 · 游戏中心内存上报 16GB

独立的现代 LSPosed / libxposed API 102 模块。只将小米游戏中心发送给服务器的 `deviceInfo.totalMemoryKb` 改为 `16777216`（16GiB），用于检验 PC 游戏引擎入口缺失是否与服务器内存规格匹配有关。

[下载 v1.0 APK](https://github.com/CLF184/GameCenterMemory16/releases/tag/v1.0)

## 产物

- `dist/GameCenterMemory16-v1.0-api102.apk`
- 包名：`local.gamecenter.memory16`
- 版本：1.0 / versionCode 1
- APK SHA-256：`cd51516e535a548491895ab97e6587ce26246c60a07c285b60a25be37c87b011`
- 最低 Android 10，要求支持现代 API 102 的 LSPosed 框架；不兼容仅支持传统 API 82 或现代 API 101 的框架。

## 使用

1. 安装 APK。
2. 在兼容的 LSPosed 中启用模块，作用域仅选“小米游戏中心” `com.xiaomi.gamecenter`。
3. 完全停止游戏中心并重新打开。模块关闭热重载；停用或更新后也需要重启目标进程。
4. 查看 LSPosed 日志中的 `GameCenterMemory16`：
   - `Installed DeviceInfo.toJson hook`：目标方法已挂钩。
   - `Upload totalMemoryKb: ... -> 16777216`：已经实际命中序列化并改写内存字段。
5. 再检查 PC 游戏页面及游戏详情页是否出现“PC 游戏引擎”。这一步才是入口效果的真机验证。

模块没有启动器界面，也不申请权限。

## 实现

已分析游戏中心 81.4.3.700 的以下序列化路径：

```text
com.xiaomi.gamecenter.download.model.ClientInfo$DeviceInfo.toJson()
  -> 仅改返回 JSONObject 的 totalMemoryKb
  -> ClientInfo.toJson()
  -> GCHeaderInterceptor 加密并缓存请求头
```

在 `onPackageReady`、创建 Application 之前安装 Hook，并对直接调用方 `ClientInfo.toJson()` 请求反优化，避免已编译调用方的内联影响。保留原始序列化的返回对象、其他字段和异常；不改 DeviceInfo 中真实内存字段，不 Hook 系统内存 API，不修改原 APK，也不改 WinPlay。只在游戏中心及其子进程中对该包安装 Hook。

该字段会影响游戏中心所有使用这份设备信息的请求，范围不限于 PC 游戏接口。模块不伪造接口响应，不修改 buttonStyle，不保证入口一定出现；服务器仍可能根据其他条件筛选。

## 构建与检查

在本目录运行：

```powershell
.\build.ps1
# 或指定本机 JDK 路径：
.\build.ps1 -JdkPath 'C:\path\to\jdk'
```

要求 Windows、PowerShell 7、JDK 17 或更新版本、Python 3 和 curl。优先使用 `-JdkPath` 参数，其次读取 `JAVA_HOME`，未配置时从 `PATH` 中的 `javac.exe` 定位 JDK。脚本将固定版本依赖下载到 `tools/`，使用官方 Android 35 平台库、aapt2、D8 和 apksig 编译、打包、签名，产物输出到 `dist/`。

`tools/memory16-local.p12` 是此本地构建的签名密钥，需保留以便后续覆盖安装；不应提交到公共仓库。脚本只打包 `src/main`，测试类与 libxposed API 实现不进入 APK。

首次构建会生成自己的本地签名密钥。自行构建的 APK 与 Release APK 的签名不同，不能直接相互覆盖安装。

本地检查通过：

- 使用真实模块注册的拦截器验证作用域和进程边界、重复回调只挂钩一次。
- 原序列化执行一次，只有上传内存改为整数 16777216，其他 JSON 字段和源对象真实内存不变。
- 原返回 null、缺少字段和原方法抛出的异常保持原行为。
- APK 作用域、入口类、DEX 类定义和不打包 API/测试类检查通过。
- APK v2、v3 签名验证通过。

已完成 Android 真机 Hook 命中验证，确认 DeviceInfo 序列化后的内存字段会改成 16GiB。此验证尚不能单独证明网络发送完成、服务器接收结果或 PC 引擎入口已经恢复。

API 生命周期参考：[libxposed 官方接口](https://github.com/libxposed/api/blob/master/api/src/main/java/io/github/libxposed/api/XposedModuleInterface.java)。
