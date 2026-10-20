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
| v5 | 远程为空 → 查询 App 端导出的 `PreferencesProvider`（`call("getAll")`，进程级 1 秒 TTL 缓存）→ 文件直读 | 通道打通：`flyme.update`（uid 1000）能拿到全部 13 项配置；但 systemui 等普通 uid 进程全部为 0 |
| **v6（最终）** | **存储整体切换到设备加密存储（DE）+ provider 标记 `directBootAware`** | 解决"安装后 App 处于 stopped 状态导致普通 uid 进程连不上 provider"与"重启后首次解锁前配置不可读"两个根因 |

v5 真机日志（2026-10-19 07:03）判定依据：`com.meizu.flyme.update`（uid 1000，系统进程豁免 stopped 限制）在进程启动后 19ms 内经 provider 拿到 13 项配置；同秒重启的 systemui/launcher（普通 uid）全部为 0——说明 provider 通道本身可用，但普通 App 进程在 App 处于 stopped 状态（每次安装/升级后、且未重新打开过 App）时会被 AMS 拦截。**因此：每次安装/升级后必须打开一次 App。**

v6 改动：
1. `Utils.getMySharedPreferences` 改为经 `createDeviceProtectedStorageContext()` 访问 DE 存储（App 端全部配置读写均经此函数，一处切换全量生效）；
2. `PreferencesProvider` 标记 `android:directBootAware="true"`，App 的 `Application` 在 Direct Boot 模式下跳过友盟初始化——重启后首次解锁前 systemui 等进程即可读到配置；
3. `Application` 启动时把旧版写在 CE 的配置一次性迁移到 DE（仅 DE 为空时执行，不会回滚）；
4. 兼容层 `getAll()` 改为 **provider 优先**（远程服务在 LSPosed 2.2.1 上从未送达过数据），并新增 `channelReport()` 通道诊断：探针日志变为 `偏好读取: N 项配置（provider=P 远程=R 文件=F）`，-1 表示通道异常；
5. **移除"主题无限试用"**（ThemePatcher 模块、设置开关、文案全部删除）：与 FlymeTool 等主题 hook 模块冲突，且其 hook 目标与硬编码资源 ID 在新 ROM 上不稳定；去广告等其余功能保留。

配套：`PreferencesProvider`（原作者预留的导出组件）增加 `call("getAll", spName)` 全量读取接口；`ResourceCompat` 在宿主 `attachBaseContext` 时暂存 Application 供通道使用；proguard 保留两个 Provider 类。

**v7**：模拟替换保留原字符串样式 Span（ROM 原值若是带 `<font>` 标记的 Spanned，纯文本替换会剥掉着色标记）+ 每 ID 节流命中诊断日志（原值/类型/样式/调用栈）；真机 A/B 证实变色与替换无关（归因 FlymeTool 的 `Resources.getColor/getColorStateList` 全局拦截与罩色改写）。

**v8（最终）**：

1. **电池着色守卫**（缓和 FlymeTool / flyme-mod 家族引发的"电量白底白字、黑底黑字"）：依据 flyme-mod（ChargingInfoHooks，同 ROM）披露的结构，hook `com.flyme.statusbar.battery.FlymeBatteryMeterView` 的 `setBatteryPercentView(TextView, String)` 与 `apply(boolean)`——图标与百分比文字共用 `mFilterColor`；同一条状态栏里的时钟始终正确，故在每次 `apply` 后以最近一次时钟文字色为参照校验，偏离（RGB 距离 > 128 或 alpha 不同）则以时钟色强制纠正 `mFilterColor` 与文字颜色并让 ROM 重绘。只守卫 StatusBar 实例（锁屏电池遵循另一套明暗参照）。健康时零动作，纠偏时按分钟节流记日志。
2. **stopped 唤醒广播**：真机日志显示普通 uid 宿主进程（systemui/launcher）读 provider 全部失败而 uid 1000 进程（flyme.update、system:ui）全部成功——App 处于 stopped 状态时普通 uid 的 provider 访问被系统拦截。新增导出的空接收器 `WakeReceiver`（directBootAware），provider 不可达时由宿主进程发送携带 `FLAG_INCLUDE_STOPPED_PACKAGES` 的显式广播清除 stopped 状态并重试一次。
3. **崩溃循环修复**（真机崩溃日志：`IllegalStateException: SharedPreferences in credential encrypted storage are not available until after user (id 0) is unlocked`，Occurrence 27）：崩溃点在 `PreferencesProvider.onCreate()`——它先于 `Application.onCreate` 执行，且启动即向 CE 存储写 authorities 字符串；解锁前/解锁过渡期被拉起即抛异常，宿主每次查询 provider 又重新拉起进程形成循环。修复：该写入改为可失败跳过（解锁后任意一次进程启动补写）；`Application.onCreate` 以 `UserManager.isUserUnlocked` 为准提前返回（`isDeviceProtectedStorage` 在解锁过渡期不可靠）；友盟初始化整体 try-catch。
4. **移除 XSP 旧机制**：`XSharedPreferences` 删除文件直读 XML 的全套路径（`reload()`/`parse()`/候选路径/`File` 构造降级为仅取名的兼容壳）与 `getFile()`——现代 SELinux 下宿主进程永远读不到模块私有文件，属死代码；通道诊断改为 `provider=P 远程=R`；删除 manifest 中遗留的 `xposedsharedprefs` 标记与 `XposedHelper` 的文件路径回退。

**v9（最终，推荐）**：**撤除 v8 的电池着色守卫**——真机实测：重启系统界面使守卫生效后，电量变色问题反而**加重并扩散到电池图标**，证明"以时钟色为参照强制改写 `mFilterColor`"对 ROM 着色管线的假设有误（图标本体的合法着色并非时钟色，改写 + 触发重绘污染了图标）。整套移除（含尚未发布的右侧图标集群守卫），systemui 侧完全恢复 v7 行为。v8 其余修复全部保留：Direct Boot 崩溃循环修复（`IllegalStateException: SharedPreferences in credential encrypted storage are not available…`）、stopped 唤醒广播、XSP 旧机制清除。变色 bug 根因维持 FlymeTool 归因（此前 A/B 测试已证实与本模块无关）。

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
- `Flyme10助手_4.6.12_libxposed102_v5_signed.apk`（Provider 实时配置通道）
- `Flyme10助手_4.6.12_libxposed102_v6_signed.apk`（DE 存储 + directBootAware + 移除主题试用）
- `Flyme10助手_4.6.12_libxposed102_v7_signed.apk`（替换保留样式 Span + 命中诊断日志）
- `Flyme10助手_4.6.12_libxposed102_v8_signed.apk`（电池着色守卫 + stopped 唤醒 + 崩溃循环修复 + 移除 XSP 旧机制——**守卫经真机实测有害，已被 v9 撤除，不推荐使用**）
- `Flyme10助手_4.6.12_libxposed102_v9_signed.apk`（v8 基础上撤除电池着色守卫，最终版）

## 七、已知限制与注意事项

- 需要**支持 libxposed 新 API 的框架**（LSPosed 2.x）；旧框架请使用原作者的 4.6.11。
- 资源替换仅覆盖 Java 代码层取值；样式类替换不支持。
- 原作者针对旧版 Flyme 硬编码的部分 hook 目标（`StatusBarIconControllerImpl`、`MobileSignalController`、广告类等）在新 ROM 上不存在，会记 `ClassNotFoundException` 日志后跳过——与原版行为一致，非迁移引入。
- 卸载重装会清空配置，重新配置即可（时钟/电量等查询时判定项即时生效；其余加载期注册项需重启作用域一次）。

## 八、构建

与原版一致：AGP 8.2.1 / Gradle 8.2 / JDK 17 / compileSdk 34 / minSdk 33，`assembleRelease` 产出后自备 keystore 签名。本仓库不含任何签名密钥。
