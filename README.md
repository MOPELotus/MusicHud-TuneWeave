# MusicHud TuneWeave

在 Minecraft 里搜索、播放音乐，显示逐字歌词，与服务器里的朋友共享播放队列。

本项目基于 [MusicHud](https://github.com/Ephern/MusicHud)，使用 [TuneWeave](https://github.com/MOPELotus/TuneWeave) 接入音乐平台。支持 Fabric、NeoForge 客户端，以及 Paper、Velocity、BungeeCord 服务端/代理插件。

[下载发布版本](https://github.com/MOPELotus/MusicHud-TuneWeave/releases) · [开发构建](https://github.com/MOPELotus/MusicHud-TuneWeave/actions) · [反馈问题](https://github.com/MOPELotus/MusicHud-TuneWeave/issues) · [部署说明](docs/deployment.md)

## 可以做什么

- 搜索歌曲、歌单、专辑和歌手，浏览并管理平台支持的个人收藏。
- 在账户页查看最近播放的歌曲、专辑和歌单，并继续点歌或打开详情。
- 使用桌面式音乐界面、HUD、逐字歌词、封面和播放来源跳转。
- 点歌、管理队列、投票切歌；将歌单或专辑设为空闲播放源，按随机或顺序模式播放。
- 重抽自己提供的“下一首”空闲歌曲，同时保持当前音乐和歌词继续播放。
- 单人独立使用，或在多人服务器、群组服中同步听歌。
- 使用 TuneWeave 提供的多平台搜索、云盘、Uni 歌单，以及支持的视频、播客、电台等内容。

具体功能、可用音质与 VIP/云盘访问取决于 TuneWeave 的平台支持及音乐账号权限。音乐账号保存在客户端；公共听歌不要求每位玩家都登录音乐账号。

## 选择下载文件

客户端每个版本组提供 Fabric、NeoForge 两个文件，只安装与你的加载器对应的一个。文件名中的版本范围表示同一个 JAR 支持该范围，不需要为范围内每个小版本下载另一份 MusicHud。

| Minecraft | MusicHud 文件后缀 | Java | ModernUI 来源与已核对版本 |
| --- | --- | --- | --- |
| 1.21.1 | `+1.21.1.jar` | 21 | 官方 ModernUI `1.21.1-3.13.0.1` |
| 1.21.6–1.21.8 | `+1.21.6-1.21.8.jar` | 21 | 官方 ModernUI `1.21.8-3.13.0.3`，支持该整个范围 |
| 1.21.9–1.21.10 | `+1.21.9-1.21.10.jar` | 21 | mVUS `3.12.0.5-build.4`，ModernUI 文件需分别匹配 1.21.9 或 1.21.10 |
| 1.21.11 | `+1.21.11.jar` | 21 | mVUS `3.12.0.5-build.4+mc1.21.11` |
| 26.1–26.1.2 | `+26.1-26.1.2.jar` | 25 | 官方 ModernUI `26.1.2-3.13.0.5`，支持该整个范围 |
| 26.2 | `+26.2.jar` | 25 | 本项目配套 ModernUI `26.2-3.13.0.7` |
| 26.3 | `+26.3.jar` | 25 | 本项目配套 ModernUI `26.3-3.13.0.7` |

例如，Minecraft 1.21.7 Fabric 使用 `musichud-tuneweave-fabric-<发行版本>+1.21.6-1.21.8.jar`。

## 客户端依赖

**Fabric：** Fabric Loader、[Fabric API](https://modrinth.com/mod/fabric-api)、对应版本的 ModernUI，以及 MusicHud TuneWeave。26.2 的 Fabric API 最低为 `0.160.0+26.2`，26.3 最低为 `0.160.5+26.3`；其他版本选择与游戏版本匹配的 Fabric API。

**NeoForge：** NeoForge、对应版本的 ModernUI，以及 MusicHud TuneWeave。

ModernUI 有以下三条来源，按上表选择其中一条，再选择对应加载器：

1. [官方 ModernUI](https://modrinth.com/mod/modern-ui/versions)：用于 1.21.1、1.21.6–1.21.8、26.1–26.1.2。
2. [ModernUI mVUS](https://modrinth.com/mod/modernui-mc-mvus/versions)：用于 1.21.9–1.21.10、1.21.11。
3. [MOPELotus ModernUI 分支](https://github.com/MOPELotus/ModernUI-MC/releases)：用于 26.2、26.3，提供 Fabric 和 NeoForge 文件。

**26.2 / 26.3 配套 ModernUI 分支不需要 Forge Config API Port。** 上表其余版本使用的官方 ModernUI / mVUS **Fabric** 文件仍声明了这一依赖，需要额外安装匹配游戏版本的 [Forge Config API Port](https://modrinth.com/mod/forge-config-api-port/versions)。这是 ModernUI 的依赖；MusicHud TuneWeave 自身不依赖它。NeoForge 无需另装 API Port。

[Mod Menu](https://modrinth.com/mod/modmenu) 为 Fabric 可选依赖。不要同时安装多份 ModernUI，也不要与原版 MusicHud 同时安装。

## 首次使用

1. 将模组和对应依赖放入游戏实例的 `mods` 目录，进入世界或服务器，按 **M** 打开界面。
2. 首次使用需要准备 **TuneWeave 本地服务**。打开设置中的 API 服务区域，点击“下载 TuneWeave 服务端”，选择保存目录和下载方式，再点击“下载”。内置下载会按系统选择发布文件并校验 SHA-256。
3. 下载完成后，在“使用该文件并立即（重新）启动 TuneWeave？”提示中选择“是”。确认设置页中的 TuneWeave 状态正常；以后可使用已经保存的服务配置。
4. 选择音乐平台；需要个人收藏、云盘等账号功能时，在客户端完成该平台支持的登录流程。
5. 搜索歌曲并加入播放队列，或打开歌单/专辑，将其设为空闲播放源。点歌队列优先播放，队列为空后使用空闲播放源。

在账户页点击“最近播放”，可切换歌曲、专辑和歌单记录，显示最近最多 100 条及其时间、可用的设备信息。点击歌曲加入队列，点击专辑或歌单卡片打开详情；时间按本机时区显示。此功能需要 **TuneWeave 0.1.0-alpha.11 或更新版本**及音乐平台登录，目前由网易云提供支持。

也可以自行下载 [TuneWeave 发布文件](https://github.com/MOPELotus/TuneWeave/releases)，在设置中选择可执行文件；已有独立实例时可直接配置其服务地址，默认地址为 `http://127.0.0.1:7832`。TuneWeave 是运行在客户端机器上的独立程序，不是放进 `mods` 或 `plugins` 的 JAR。

下载较慢时可在下载窗口选择 GitHub 下载代理。连接或启动失败时，可在同一设置区域查看服务状态、版本和日志。

## 常用按键

| 默认按键 | 功能 |
| --- | --- |
| **M** | 打开音乐界面 |
| **.** | 投票跳过当前歌曲 |
| **,** | 显示/隐藏 HUD |
| **右 Shift** | 切换公共连接与客户端隔离模式 |

按键可以在 Minecraft 控制设置中修改；静音、增减音量也可自行绑定。隔离模式下独立播放，不与服务器同步。

## 多人服务器与群组服

玩家仍需安装客户端模组及其依赖。客户端和插件请使用同一发行版本。

| 环境 | 插件安装位置 | 选择的文件 |
| --- | --- | --- |
| 单人游戏 | 无需独立插件 | 客户端模组即可 |
| 独立 Paper 服务器 | Paper 的 `plugins` 目录 | `musichud-tuneweave-paper-<发行版本>.jar` |
| Velocity 群组服 | 仅 Velocity 的 `plugins` 目录 | `musichud-tuneweave-velocity-<发行版本>.jar` |
| BungeeCord 群组服 | 仅 BungeeCord 的 `plugins` 目录 | `musichud-tuneweave-bungeecord-<发行版本>.jar` |

**群组服只在代理层安装本插件，所有后端都不要安装。** 代理与后端双装会被检测并阻止连接。代理负责共享队列和公共播放，切换后端时继续由同一个代理协调。

插件采用 Java 21 字节码，实际 Java 版本还要满足服务器或代理自身要求。Paper 的 API 基线为 1.21.1。服务器和代理不安装 ModernUI、不运行 TuneWeave，也不保存音乐平台账号。完整说明见[部署说明](docs/deployment.md)。

`plugin` 是跨 Minecraft 版本的公共分支：Paper、Velocity、BungeeCord 各提供一个通用 JAR，26.3 继续使用这三个插件文件。Paper 的 `api-version` 保持最低 API 版本 1.21.1，编译基线和 Java 21 字节码不随新增游戏版本提高。客户端模组仍需按 Minecraft 版本选择。1.3.0-beta-3 已使用同一套插件 JAR 完成 Paper 26.3、Velocity 4.2.0 和支持 26.3 的 BungeeCord 联调，具体运行版本与部署限制见[部署说明](docs/deployment.md#服务器和代理)。

## 获取更新与反馈

全量 CI 和发布流程由默认分支 `26.3` 管理。当前矩阵覆盖七个客户端版本组的双加载器 JAR，以及 `plugin` 分支的三个跨版本插件，共 17 个部署文件，并附带 `SHA256SUMS` 与构建来源清单。

GitHub Release 在推送匹配版本标签，或从 `26.3` 手动运行 CI 并选择 `publish` 时发布。Modrinth、CurseForge、Hangar 使用独立的 `Platform publishing` 工作流：从 `26.3` 运行，填写已发布的 GitHub Release 标签，先 `prepare` 再 `publish`。该流程也保留对旧版 15 文件发布包的校验和重试支持。

Actions 中的 `release-bundle` 可用于获取开发构建。报告问题时请提供 Minecraft、加载器、MusicHud、ModernUI 和 TuneWeave 的版本，以及复现步骤和相关日志片段；发送前请移除账号凭证。

## 致谢与许可

感谢 [Ephern / Etern 的 MusicHud](https://github.com/Ephern/MusicHud)、[ModernUI](https://github.com/BloCamLimb/ModernUI-MC)、mVUS 维护者，以及 [TuneWeave](https://github.com/MOPELotus/TuneWeave)。本项目由 MOPELotus 维护，保留上游署名，按 [LGPL-3.0](license) 发布。

## 构建发行版本

插件也支持 `-Pdistribution=cf`，仅用于给送审包统一添加 `-cf` 版本后缀，插件功能不变：服务器和代理始终不下载、不启动 TuneWeave，不持有音乐平台账号。

```bash
./gradlew core:test paper:build velocity:build bungeecord:build -Pdistribution=cf --rerun-tasks
```

Windows 使用 `gradlew.bat`。基础 `mod_version` 不添加后缀，例如 `1.3.0-beta-3` 在此模式下生成 `musichud-tuneweave-velocity-1.3.0-beta-3-cf.jar`。省略该参数或使用 `-Pdistribution=standard` 构建普通版，无需先 `clean`。两种客户端使用同一通信协议，插件以相同基础发行版本匹配即可。

Actions 的 `distribution=cf` 模式仅生成送审产物，不自动发布 GitHub Release；Modrinth 和 CurseForge 上传仍未启用。CF 后缀不表示平台已经批准。
