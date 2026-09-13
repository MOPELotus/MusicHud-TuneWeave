# MusicHud-TuneWeave — Paper / Velocity / BungeeCord

MusicHud TuneWeave 是由 [TuneWeave](https://github.com/MOPELotus/TuneWeave) 驱动的 Minecraft 音乐播放与同步项目，支持点歌、队列、歌词、HUD 和多人同步。

MusicHud TuneWeave is an independent LGPL fork of [MusicHud](https://github.com/Etern-34520/MusicHud), also derived from the former MusicHud-Paper implementation. It is not an official MusicHud release and does not guarantee API or protocol compatibility with upstream MusicHud.

## 支持与部署

本分支构建 Paper、Velocity、BungeeCord 三种服务端/代理插件，客户端模组由对应 Minecraft 版本分支提供。音乐服务和账号凭据由客户端持有，服务器与代理只协调公共播放。

群组服只在代理端安装对应插件，所有后端均不安装；独立 Paper 单服直接安装 Paper 插件。客户端仍需匹配的模组。详见[部署说明](docs/deployment.md)。

## 构建

使用 JDK 25；所有插件模块以 Java 21 为字节码目标。服务器或代理自身可能要求更高版本的 Java。

```bash
./gradlew core:test paper:build velocity:build bungeecord:build --rerun-tasks
```

Windows 使用 gradlew.bat。部署各模块 build/libs 中的 `musichud-tuneweave-paper-<version>.jar`、`musichud-tuneweave-velocity-<version>.jar` 或 `musichud-tuneweave-bungeecord-<version>.jar`，不使用 -plain/-sources 包。

Paper 配置位于 MusicHud-TuneWeave 目录；代理使用对应平台的插件数据目录。不要迁入旧版服务器端音乐凭据。跳过投票的 pusherVoteAdditionalRate 配置范围为 0 到 1。

## 许可证与致谢

本项目遵循 [LGPL-3.0](license)，保留上游作者及贡献者归属。Java 包名为 indi.mopelotus.musichud。
