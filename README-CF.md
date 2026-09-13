# MusicHud TuneWeave — CurseForge edition

> **This is the feature-limited CurseForge edition.** TuneWeave downloading and updating are not included. For the full-featured edition, we recommend the standard builds on [GitHub](https://github.com/MOPELotus/MusicHud-TuneWeave/actions), or Modrinth when the project is published there. Music playback, lyrics, the HUD and shared queues remain available.

MusicHud TuneWeave adds an in-game music browser, playback controls, synchronized lyrics and a configurable HUD. Play independently or join a shared queue with other players using compatible clients and a supported server or proxy plugin.

This is an independently maintained fork of [MusicHud by Ephern / Etern](https://github.com/Ephern/MusicHud). It separates music-provider integration into TuneWeave, uses its own mod identity and data paths, and includes changes to shared playback, queue coordination and server/proxy integration. It retains upstream attribution and is distributed under LGPL-3.0.

### Installation

Install the file for your Minecraft version and loader, together with its required dependencies. Fabric needs Fabric API and the appropriate ModernUI build. Most supported ModernUI Fabric builds also need Forge Config API Port; the project's 26.2 ModernUI fork does not. NeoForge needs the corresponding ModernUI build. Never install this mod alongside upstream MusicHud or a second edition of MusicHud TuneWeave.

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
