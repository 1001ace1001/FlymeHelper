# Flyme10助手（4.6.12 · libxposed API 102 分支）

> **本分支作者：glm-5.3**。本仓库是 [coderstory/FlymeHelper](https://github.com/coderstory/FlymeHelper) 的延续分支，感谢原作者 [CoderStory](https://blog.coderstory.cn) 的贡献（遵循 GPL-2.0，原版权声明保留于 [LICENSE](LICENSE) 与各源文件头部）。
>
> **本分支已停止维护**。源码与全部变更记录按 GPL-2.0 原样归档，可自由取用与继续开发。

本分支相对原版（4.6.11 / commit `dd80c67`）的全部修改由 **glm-5.3** 完成：将模块从传统 Xposed API（`de.robv.android.xposed`，api-82）整体迁移至 libxposed 新 API（`io.github.libxposed:api:102.0.0`，targetApiVersion=102），并适配 LSPosed 2.x 的现代模块加载路径：

- 版本 4.6.12（versionCode 560，原版 4.6.11 基础 +1）
- 新式模块声明：`META-INF/xposed/java_init.list` / `module.prop` / `scope.list`，移除 `assets/xposed_init`
- 新增 Java 兼容层 `com.coderstory.flyme10.xposedcompat`：以拦截器链复刻旧 API 的全部签名（`XC_MethodHook`、`XposedBridge`、`XposedHelpers`、`XSharedPreferences` 等），7 个功能模块因此只改了 import 行
- 移除方向错误的"未在框架中激活"自检（新 API 严格按 scope.list 注入、不注入模块自身 App 进程，该检测在新路径下必然误报）
- 配置读取通道重写（三级回退）：框架 `getRemotePreferences` → App 端导出的 `PreferencesProvider` 实时拉取（加载期经 ActivityThread 系统上下文）→ 文件直读；卸载重装、改配置不再失效
- 内嵌模拟资源替换（拦截 `Resources.getString/getText`，思路参考 EzHookTool / HyperCeiler 的 ResourcesTool）：电量百分比等字符串替换不再依赖已被 LSPosed 移除的旧版 XResources 桥；电量开关为"查询时判定"，切换即时生效

归档文档：

- 修改说明与迭代记录：[docs/CHANGES-libxposed102.md](docs/CHANGES-libxposed102.md)
- 完整变更补丁：[docs/libxposed102-migration.patch](docs/libxposed102-migration.patch)
- 源码分析报告（逆向分析 + 迁移全程）：[docs/源码分析报告.md](docs/源码分析报告.md)

---

以下为原版说明：

# Flyme10助手

> Flyme10助手是基于Xposed开发的Flyme系统MOD工具

CoderStory的博客：[blog.coderstory.cn](https://blog.coderstory.cn)   
 
 
 
Flyme10助手支持去除Flyme系统的大部分广告内容，清理应用缓存，应用冻结备份等功能。


## License
```text
This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
```
