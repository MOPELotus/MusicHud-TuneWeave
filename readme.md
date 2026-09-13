# MusicHud-TuneWeave

MusicHud TuneWeave 是由 [TuneWeave](https://github.com/MOPELotus/TuneWeave) 驱动的 Minecraft 音乐播放与同步项目，支持点歌、队列、歌词、HUD 和多人同步。

MusicHud TuneWeave is an independent LGPL fork of [MusicHud](https://github.com/Etern-34520/MusicHud), also derived from the former MusicHud-Paper implementation. It is not an official MusicHud release and does not guarantee API or protocol compatibility with upstream MusicHud.

## 支持范围

本分支提供 Minecraft **1.21.6-1.21.8** 的 Fabric 和 NeoForge 客户端模组，运行需要 Java 21。Paper、Velocity、BungeeCord 插件由 [plugin 分支](https://github.com/MOPELotus/MusicHud-TuneWeave/tree/plugin)构建。

群组服只在代理端安装对应插件，所有后端均不安装；独立 Paper 单服直接安装 Paper 插件。客户端仍需安装模组。详见[部署说明](docs/deployment.md)。

## 安装与音乐服务

1. 安装匹配 Minecraft 版本与加载器的 ModernUI；Fabric 还需要 Fabric API。
2. 将 `musichud-tuneweave-fabric-<version>+<mc-version>.jar` 或 `musichud-tuneweave-neoforge-<version>+<mc-version>.jar` 放入 mods 目录。
3. 在游戏内设置页下载或配置 TuneWeave；独立实例默认地址为 http://127.0.0.1:7832。

依赖版本以本分支 gradle.properties 和模组元数据为准。26.2 使用[配套 ModernUI 分支](https://github.com/MOPELotus/ModernUI-MC/releases)。TuneWeave 自动下载会校验 SHA-256，升级仅清理受管理的旧二进制，保留无关用户文件。

音乐平台凭据由客户端持有，不发送给 Minecraft 服务器。项目命名空间为 musichud_tuneweave，不能与上游 MusicHud 并装；配置和 Uni 数据使用独立目录，不自动迁移旧数据。

## 构建与测试

构建使用 JDK 25。

```bash
./gradlew common:test fabric:build neoforge:build --rerun-tasks
```

Windows 使用 gradlew.bat。网络/下载集成测试默认不运行，可显式执行 common:test -PintegrationTests。产物位于 fabric/build/libs 和 neoforge/build/libs，使用不含 -sources/-dev-shadow 后缀的分发 JAR。

## 许可证与致谢

本项目遵循 [LGPL-3.0](license)，保留上游作者及贡献者归属。Java 包名为 indi.mopelotus.musichud。
