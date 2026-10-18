# libxposed API 102 迁移记录（4.6.11 → 4.6.12）

> 本文档记录 glm-5.3 完成的全部修改。基础版本：上游 dev 分支 commit `dd80c67`（版本 4.6.11 / versionCode 559）。
> 完整机器可读补丁见同目录 [libxposed102-migration.patch](libxposed102-migration.patch)，逐文件 diff 与验证细节见 [源码分析报告.md](源码分析报告.md)。

## 一、版本与声明方式

| 项 | 原版（4.6.11） | 本分支（4.6.12） |
|---|---|---|
| versionCode / versionName | 559 / 4.6.11 | 560 / 4.6.12 |
| Xposed API | `de.robv.android.xposed`（api-82 jar，`compileOnly fileTree`） | `io.github.libxposed:api:102.0.0`（Maven Central AAR） |
| 模块入口声明 | `assets/xposed_init` | `META-INF/xposed/java_init.list`（+ `module.prop`、`scope.list`） |
| 入口类 | `Start : IXposedHookLoadPackage` | `Start : XposedModule()` |
| minApiVersion / targetApiVersion | —（xposedminversion=54） | 101 / 102 |

`module.prop`：`minApiVersion=101`、`targetApiVersion=102`、`exceptionMode=protective`、`staticScope=true`。`scope.list` 与原 `@array/scope` 一致。`AndroidManifest.xml` 未动（LSPosed 仍读取 MODULE_SETTINGS category 定位设置页）。

## 二、兼容层（`com.coderstory.flyme10.xposedcompat`，13 个 Java 文件）

用 Java 复刻旧 API 的全部签名，内部全部映射到新 API，使 7 个功能模块（IsEnable/HideApp/Others/ThemePatcher/FuckAd/SystemUi/Start）只改 import：

- `XC_MethodHook` / `XC_MethodReplacement` / `MethodHookParam`：before/proceed/after 语义与旧框架一致（before 中 setResult/setThrowable 跳过原方法、after 必执行、参数修改透传）；基础类必须是 Java——`Others.kt` 的无返回类型 `replaceHookedMethod` 覆写依赖 Java 基类。
- `HookerImpl`（`XposedInterface.Hooker`）：把旧回调模型桥接到 `hook(Executable).intercept(...)` 拦截器链；回调异常捕获记日志，module.prop 同时声明 protective 兜底。
- `XposedBridge` / `XposedHelpers`：`findAndHookMethod`、`hookAllMethods`（含父类，顺带修复旧版 hook 不到继承方法的边角）、`hookAllConstructors`、`getObjectField/setObjectField/callMethod` 等反射工具。
- `LoadPackageParam` / `InitPackageResourcesParam` / `StartupParam` / `AndroidAppHelper`（`anyContext()` 额外提供 ActivityThread 系统上下文，供加载期取 ContentResolver）。

## 三、配置读取通道（三级回退，按真机日志迭代 5 版）

| 版本 | 方案 | 真机结果 |
|---|---|---|
| v1/v2 | 兼容层直接解析 `shared_prefs/UserSettings.xml` | 失败——现代加载路径不放开文件权限（0600/SELinux），所有开关读成 false |
| v3 | `getRemotePreferences("UserSettings")` 优先 | 通道连通，但 LSPosed 2.2.1 从未送达数据（供给依赖注入模块自身 App 进程或 App 侧集成 service 库，现代路径两者皆无） |
| v4 | 远程快照为空时合并文件直读 | 同样为空 |
| **v5（最终）** | **远程为空 → 查询 App 端导出的 `PreferencesProvider`（`call("getAll")`，进程级 1 秒 TTL 缓存）→ 文件直读** | 跨进程实时拉取 App 端文件，与框架同步机制解耦 |

配套：`PreferencesProvider`（原作者预留的导出组件）增加 `call("getAll", spName)` 全量读取接口；`ResourceCompat` 在宿主 `attachBaseContext` 时暂存 Application 供通道使用；proguard 保留两个 Provider 类。

## 四、激活自检移除

`MainActivity.checkEnable()`（弹"检测到插件尚未在框架中激活"）与 `checkDialog()`（`MODE_WORLD_READABLE` 抛异常即弹"插件配置初始化失败"并杀进程）已删除。原设计靠旧框架把模块注入自身 App 进程 hook `isEnable` 自证——新 API 按 scope.list 严格注入，检测必然误报。两条文案在成品 dex/资源中零残留。

## 五、模拟资源替换（reshook emulation）

LSPosed 官方已移除现代 API 的资源 hook（Wiki："Resource hooks are removed"）。参考 EzHookTool（`hook-xposed-101`）/ HyperCeiler ResourcesTool 的技术，内嵌实现：

- 队列中的替换在宿主 `Resources` 就绪时按 `getIdentifier` 解析出资源 ID；
- 旧 XResources 桥存在则优先走桥，否则 hook `Resources.getString(int)` / `getText(int)` / `getString(int, Object[])` 按 ID 匹配返回替换值；
- 电量百分比（`status_bar_settings_battery_meter_format_simple` → `%d%%`）为"注册时无条件、查询时判开关"（gateKey），切换即时生效、重启手机不失效；
- 局限：XML 布局/样式原生解析路径无法拦截（"阿巴阿巴" style 替换跳过并记日志）；ThemePatcher 硬编码 ID 是否命中取决于宿主版本。

## 六、交付产物（同一自签证书 SHA-256 `ed419703…726b`，可互相覆盖安装）

- `Flyme10助手_4.6.12_libxposed102_signed.apk`（v1，含旧激活自检）
- `Flyme10助手_4.6.12_libxposed102_v2_signed.apk`（移除激活自检）
- `Flyme10助手_4.6.12_libxposed102_v3_signed.apk`（远程偏好优先）
- `Flyme10助手_4.6.12_libxposed102_v4_signed.apk`（模拟资源替换）
- `Flyme10助手_4.6.12_libxposed102_v5_signed.apk`（Provider 实时配置通道，最终版）

## 七、已知限制与注意事项

- 需要**支持 libxposed 新 API 的框架**（LSPosed 2.x）；旧框架请使用原作者的 4.6.11。
- 资源替换仅覆盖 Java 代码层取值；样式类替换不支持。
- 原作者针对旧版 Flyme 硬编码的部分 hook 目标（`StatusBarIconControllerImpl`、`MobileSignalController`、广告类等）在新 ROM 上不存在，会记 `ClassNotFoundException` 日志后跳过——与原版行为一致，非迁移引入。
- 卸载重装会清空配置，重新配置即可（时钟/电量等查询时判定项即时生效；其余加载期注册项需重启作用域一次）。

## 八、构建

与原版一致：AGP 8.2.1 / Gradle 8.2 / JDK 17 / compileSdk 34 / minSdk 33，`assembleRelease` 产出后自备 keystore 签名。本仓库不含任何签名密钥。
