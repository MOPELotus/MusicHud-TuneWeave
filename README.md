# MusicHud Companion

独立版本 **0.2.0**，帧协议版本 **1**。桌面程序独立维护在本仓库 `companion` 分支。

Minecraft 26.3 的独立桌面 HUD。Minecraft 继续使用原 HUD Renderer，Companion 只显示游戏传来的透明画面。游戏内 HUD 保留。

## 使用

1. 安装 `experiment/companion` 分支构建的 26.3 Fabric 或 NeoForge 模组，以及对应的 ModernUI。普通正式分支尚不包含桌面导出功能。
2. 保留 Windows 构建产物的整个 `Companion` 目录，双击 `Companion.exe`。随附运行时，无须另装 Java；不能只复制 EXE。
3. 在 MusicHud 的设置菜单中找到 **桌面 HUD（Companion）** 分类，打开 **启用桌面 HUD** 开关。
4. 关闭 **锁定桌面 HUD（鼠标穿透）** 开关，在桌面直接拖动 HUD；调整后回到游戏打开该开关。

锁定后，整个桌面 HUD 不接收鼠标操作，点击及滚轮交给下方窗口。新连接、Companion 重启均默认锁定；拖动结束的位置保存在本机。显示器移除后，屏幕外的位置回到主屏工作区。导出开关默认关闭，每次启动游戏需重新启用。

桌面窗口置顶，继续使用原 HUD Renderer、布局尺寸、样式、内容和淡出；独立画布不随游戏窗口大小、GUI 缩放或游戏内 HUD 的锚点、偏移而变化。可在菜单内选择独立的 1–4 倍桌面缩放（默认 2）及 60 或 30 FPS。启用状态和未播放时隐藏规则仍与原 HUD 一致；打开 MusicHud 菜单或 HUD 编辑器时，桌面画面保持可见，方便调整位置。游戏退出、连接断开或连接超时后清空画面。Windows 托盘菜单可退出 Companion。

Companion 已连接时，游戏主线程的绘制节奏至少保持所选桌面帧率，避免闲置与最小化限帧中断桌面动画；最小化时跳过游戏世界和游戏 GUI 绘制，仅绘制桌面 HUD。游戏设置值不被改写，断开 Companion 或关闭导出后恢复原限帧行为。帧率是上限，仍受机器负载影响。

建议先使用窗口或无边框模式测试。置顶窗口能否覆盖独占全屏由 Windows、驱动和显示模式决定。

## 构建

使用 Java 25，从仓库根目录运行：

```powershell
.\gradlew.bat test protocol:test installDist --rerun-tasks
.\gradlew.bat bundleCompanion -PdesktopPlatform=windows
```

第二条命令必须在 Windows x64 执行，输出 `build/package/Companion/Companion.exe` 及其依赖目录。已有同名输出时打包工具会拒绝覆盖；请使用新工作目录，或在确认应用已关闭后清理生成目录。

[Companion service 工作流](https://github.com/MOPELotus/MusicHud-TuneWeave/actions/workflows/companion.yml) 构建 Windows 应用并检查启动器。托管 CI 不承担物理显卡或窗口行为验收。

独立 Gradle 构建不加入 Minecraft 的模块图。正常客户端 JAR 的 `verifyCompanionIsolation` 检查会拒绝打入 Companion 窗口实现或新增 GLFW/OpenGL 桌面依赖。

桌面启动器的 Java 堆上限为 256 MiB；GPU、驱动和运行时的本机内存另计。

## 本机数据与排查

Windows 数据目录为 `%LOCALAPPDATA%\MusicHud-TuneWeave\Companion`，包含位置、单实例锁和 `companion-39841.log`。日志记录 GPU、连接状态及帧率、提交延迟 P50/P95，不包含平台凭据。

启动参数：`--x 24 --y 24` 覆盖初始桌面位置，`--position-file <path>` 指定位置文件；`--port 39841` 指定接收端口，须与游戏一致。位置使用物理像素。当前游戏端固定监听 `127.0.0.1:39841`，同一端口只连接一个 Companion。

没有画面时，先查看游戏是否启用了原 HUD、是否因未播放而隐藏，以及桌面设置页是否显示已连接。透明窗口创建失败时检查物理显卡驱动与桌面合成环境；应用不会自动改用软件渲染器。Linux 只用于开发验证，需要有桌面合成器的硬件显示会话。

## 验证边界

Windows 人工验收需实际运行游戏和 Companion，检查透明圆角与淡出、置顶、不抢焦点、解锁拖动、锁定时释放拖动、点击和滚轮穿透、重启默认锁定、DPI、多显示器与断流清空，并从两进程日志确认物理 GPU。构建或启动器检查通过不等于这些项目通过。

性能对照应保持同一窗口尺寸、GUI 缩放、曲目和背景场景，预热后分别测量关闭导出、仅离屏、离屏加读回及完整 IPC。记录游戏帧时间、帧率、丢帧、延迟和两进程 CPU/GPU/内存；日志的 capture-to-submit 是软件提交时间，不是屏幕实际出光延迟。

## 数据边界与许可

IPC 仅传输版本化的 RGBA8 画面、帧身份、时间和锁定状态。像素按顶行优先、紧密排列、预乘 Alpha 编码。游戏仍负责布局、字体、歌词、动画、播放与同步。Companion 不使用 SMTC、不匹配歌曲名称、不调用 TuneWeave、不接收平台凭据，也不提供音乐控制。

本项目保留 LGPL 与 MusicHud 上游署名；分发许可见应用 JAR 内 `META-INF/licenses/`，以及发行目录的 `licenses/`。LWJGL 与 GLFW 的许可随应用提供。

## 版本及进程管理

`Companion.exe --version` 仅输出版本号，不初始化图形、连接或写入日志。
手动启动仍受支持；模组托管启动使用 `--owner-pid PID --owner-start epochMillis`。
两个参数必须同时提供，PID 和启动时间必须为正数；启动时间为 Java `ProcessHandle.info().startInstant().toEpochMilli()`。
程序在打开图形前核验身份，并在每轮绘制检查父进程是否存活和启动时间是否一致。
父进程正常退出或崩溃后，Companion 结束拖动、保存位置、释放鼠标与图形资源并退出；PID 重用不会延长旧实例生命周期。

Windows ZIP 为 `Companion-0.2.0-windows-x64.zip`，根目录包含完整 `Companion/`。
`Companion/companion.json` 记录版本、帧协议版本与源提交；同目录外的 `companion-manifest.json` 记录 ZIP 的 SHA-256、大小和入口。
此分支 CI 仅构建测试产物，不自动发布 Release 或上传分发平台。

托管启动还可传 `--stop-file absolutePath`。启动前该路径应不存在；文件出现后程序正常退出，避免 Windows 强制终止进程跳过清理。
Companion 只检查信号文件，不创建、修改或删除它。管理方使用每次启动独有的临时目录，并在退出后清理自己创建的目录。
