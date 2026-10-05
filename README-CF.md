# MusicHud TuneWeave — CurseForge edition

> **This is the feature-limited CurseForge edition.** TuneWeave downloading and updating are not included. For the full-featured edition, we recommend the standard builds on [GitHub](https://github.com/MOPELotus/MusicHud-TuneWeave/releases), or Modrinth when the project is published there. Music playback, lyrics, the HUD and shared queues remain available.

MusicHud TuneWeave adds an in-game music browser, playback controls, synchronized lyrics and a configurable HUD. Play independently or join a shared queue with other players using compatible clients and a supported server or proxy plugin.

This is an independently maintained fork of [MusicHud by Ephern / Etern](https://github.com/Ephern/MusicHud). It separates music-provider integration into TuneWeave, uses its own mod identity and data paths, and includes changes to shared playback, queue coordination and server/proxy integration. It retains upstream attribution and is distributed under LGPL-3.0.

### Installation

Install the file for your Minecraft version and loader, together with its required dependencies. Fabric needs Fabric API and the appropriate ModernUI build. Most supported ModernUI Fabric builds also need Forge Config API Port; the project's 26.2 / 26.3 ModernUI fork does not. NeoForge needs the corresponding ModernUI build. Never install this mod alongside upstream MusicHud or a second edition of MusicHud TuneWeave.

For Minecraft 26.2 / 26.3, use the matching [ModernUI 3.13.0.9 release](https://github.com/MOPELotus/ModernUI-MC/releases). Choose either the standard `-universal.jar` or the MiSans `-universal-misans.jar` for your loader; replace the old ModernUI JAR and never install both editions. Standard supports custom fonts without a MiSans installer. MiSans retains its font installer and weight controls; missing fonts require an explicit download and restart. Both editions include the same marquee/render/input fixes and use separate client configuration files, importing the old `client.toml` on first use. See the [font edition guide](https://github.com/MOPELotus/ModernUI-MC/blob/26.3-3.13.0.9/docs/font-editions.md). This font choice is independent of the MusicHud standard/CF distribution.

Press **M** in a world to open the music interface. Search for music, add tracks to the queue, or use a supported playlist as an idle playback source. Lyrics, account features and available audio quality depend on the music service and the user's access rights.

### Local music service

The CurseForge edition is identified by `-cf` in its file version. It does not contain the TuneWeave downloader or updater and does not bundle the TuneWeave executable.

Users install TuneWeave separately. In settings, select an already installed executable and choose to start or restart it, or enter the address of a service you run independently. New configurations leave the executable path empty and automatic startup disabled. Users may explicitly enable startup on future game launches; existing user configuration is retained when changing editions.

When the mod starts the configured executable, it invokes that file directly, without a shell command. It configures TuneWeave to listen on the loopback interface. Logs and TuneWeave data are stored beside the selected executable. The mod can stop the child process it owns. Audio playback and system media integration use bundled native libraries; those libraries are separate from the TuneWeave executable.

### Multiplayer

Players need the client mod and its dependencies. A standalone Paper server uses the Paper plugin. Velocity and BungeeCord networks install the matching plugin **only on the proxy**, with no copy on any backend. The plugins coordinate public playback and do not run TuneWeave or store music-platform login credentials.

### Support and development

Please report reproducible problems with Minecraft, loader, mod and TuneWeave versions at the linked issue tracker. Remove credentials from logs before sharing them. The project is maintained by MOPELotus.

## 中文说明

这是功能有所精简的 CurseForge 专版，移除了 TuneWeave 下载和更新功能。推荐需要完整功能的用户使用 GitHub 上的普通版；Modrinth 项目发布后也会提供普通版。音乐播放、歌词、HUD 和共享队列仍可使用。

用户需要自行准备 TuneWeave，在设置中填写本地程序路径后启动，或连接已经运行的服务。新配置默认关闭自动启动，程序路径为空。普通版与 CF 版使用同一模组标识和协议，请只安装其中一种。

## Edition notice and mod updates

The first MusicHud screen and every update prompt explain the CF edition limitations and link to the full standard edition. Update checks include releases and prereleases matching the CF edition, Minecraft version and loader. “Go to download” opens the matching GitHub Release page in your browser. Choose the matching CF JAR and replace the old file after exiting the game. This edition does not download or install mod updates, start an update helper, or manage update backups. The TuneWeave service downloader/updater also remains absent.

首次打开模组界面及更新提示均说明 CF 精简范围并提供 GitHub 完整版入口。更新检测包含正式版和测试版，只匹配 CF 发行版、游戏版本与加载器。“去下载”打开对应版本的 GitHub Release 页面；请选择匹配的 CF 文件，退出游戏后手动替换旧 JAR。CF 版不自动下载或安装模组更新，也不启动更新安装器或管理更新备份。TuneWeave 服务程序的下载/更新功能仍不包含在 CF 版中。
