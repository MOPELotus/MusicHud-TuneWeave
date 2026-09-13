# 部署说明

| 使用方式 | 安装位置 |
| --- | --- |
| 单人游戏 | 客户端安装对应 Minecraft 版本和加载器的模组 |
| 独立 Paper 服务器 | 客户端安装模组，Paper 服务器安装 Paper 插件 |
| Velocity 群组服 | 客户端安装模组，只在 Velocity 代理安装 Velocity 插件 |
| BungeeCord 群组服 | 客户端安装模组，只在 BungeeCord 代理安装 BungeeCord 插件 |

**群组服的所有后端均不要安装本插件，禁止代理端与后端双装。** 公共播放状态、队列和管理命令由代理持有；切换后端仍由同一代理协调播放。

Paper 会检查实际生效的代理转发配置并拒绝错误部署。代理会在进入后端和切服时检测冲突；发现双装后会提示并断开受影响的连接。请从所有后端移除本插件，按服务器正常维护流程重新启动并连接。此检测不负责扫描离线后端，也不提供任意插件热重载支持。

## 客户端

安装与 Minecraft 版本及 Fabric/NeoForge 匹配的 ModernUI 和 MusicHud TuneWeave 模组；Fabric 还需要 Fabric API。依赖版本及最低要求以对应分支 gradle.properties 和模组元数据为准。

26.x 客户端运行需要 Java 25，1.21.x 客户端运行需要 Java 21。26.2 使用 [MusicHud TuneWeave 配套的 ModernUI 分支](https://github.com/MOPELotus/ModernUI-MC/releases)。

在游戏内配置 TuneWeave。可选择自动下载，也可运行独立实例并配置地址（默认 http://127.0.0.1:7832）。音乐账号、Cookie 和 token 只保留在客户端；服务器及代理不运行 TuneWeave，也不保存音乐平台凭据。

## 服务器和代理

从 plugin 分支构建与平台对应的 Paper、Velocity 或 BungeeCord JAR，放入对应平台的 plugins 目录。使用不含 -plain 或 -sources 后缀的部署 JAR。插件字节码目标为 Java 21；实际运行 Java 版本还须满足所用服务器或代理自身要求。

客户端和插件应使用相互匹配的 MusicHud TuneWeave 版本。项目命名空间为 musichud_tuneweave，与上游 MusicHud 不兼容，不能同时安装。配置和 Uni 数据使用本项目独立目录，不自动迁移上游旧数据。
