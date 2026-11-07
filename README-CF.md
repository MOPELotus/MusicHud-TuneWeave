# MusicHud TuneWeave — CurseForge edition

> **This is the feature-limited CurseForge edition.** It only connects to an existing TuneWeave service. It does not install, update, start, or manage external programs. The project is fully open source; visit its [GitHub homepage](https://github.com/MOPELotus/MusicHud-TuneWeave) for source and other information. Music playback, lyrics, the HUD and shared queues remain available.

MusicHud TuneWeave adds an in-game music browser, playback controls, synchronized lyrics and a configurable HUD. Play independently or join a shared queue with other players using compatible clients and a supported server or proxy plugin.

This is an independently maintained fork of [MusicHud by Ephern / Etern](https://github.com/Ephern/MusicHud). It separates music-provider integration into TuneWeave, uses its own mod identity and data paths, and includes changes to shared playback, queue coordination and server/proxy integration. It retains upstream attribution and is distributed under LGPL-3.0.

### Installation

Install the file for your Minecraft version and loader. All current client branches recommend [ModernUI Lumen 3.13.0.11](https://github.com/MOPELotus/ModernUI-MC-Lumen/releases/tag/v3.13.0.11). Choose the matching Minecraft and Fabric/NeoForge file, then either standard `-universal.jar` or MiSans `-universal-misans.jar`. Replace the old ModernUI JAR; install only one edition. Use Fabric Loader 0.20.1 or newer. Lumen requires Fabric API on Fabric and does not require Forge Config API Port on any supported version.

The adapted official ModernUI versions remain supported: 3.13.0.1 for Minecraft 1.21.1, 3.13.0.3 for 1.21.6–1.21.8, and 3.13.0.5 for 26.1–26.1.2. Their Fabric files still require Forge Config API Port. Official Modern UI shows a dismissible ModernUI Lumen recommendation once per startup. The download button opens the [CurseForge page](https://www.curseforge.com/minecraft/mc-mods/modernui-lumen). Other Minecraft versions require Lumen. Unsupported forks or versions fail startup with a “Modern UI version is too old” message and the Lumen download link. Historical MusicHud releases retain the requirements in their own release notes. Never install this mod alongside upstream MusicHud or a second MusicHud TuneWeave edition.

On Minecraft 1.21.1, Companion desktop export requires OpenGL. VulkanMod supports the in-game HUD and music interface; desktop export is disabled for that session while the saved preference is preserved.

Press **M** in a world to open the music interface. Search for music, add tracks to the queue, or use a supported playlist as an idle playback source. Lyrics, account features and available audio quality depend on the music service and the user's access rights.

### External music service

Install and start TuneWeave independently, outside the mod. Enter the service address in Settings (usually `http://127.0.0.1:7832`). This edition has no executable-path settings, program acquisition, automatic startup, child-process controls or process-log management. Audio playback and system media integration still use bundled native libraries.

CF service preferences use a separate `musichud_tuneweave-cf-server.toml` file, preserving the standard edition's process configuration when switching editions. Only install one edition at a time.

### Multiplayer

Players need the client mod and its dependencies. A standalone Paper server uses the Paper plugin. Velocity and BungeeCord networks install the matching plugin **only on the proxy**, with no copy on any backend. The plugins coordinate public playback and do not run TuneWeave or store music-platform login credentials.

### Support and development

Please report reproducible problems with Minecraft, loader, mod and TuneWeave versions at the linked issue tracker. Remove credentials from logs before sharing them. The project is maintained by MOPELotus.

## 中文说明

这是 CurseForge 精简版，仅连接已有 TuneWeave 服务，不负责外部程序的安装、更新、启动或管理。请自行在模组外安装并启动 TuneWeave，然后在设置中填写服务地址。音乐播放、歌词、HUD 和共享队列仍可使用。普通版与 CF 版使用同一模组标识和协议，请只安装其中一种。

## Edition notice and mod updates

The first MusicHud screen explains the CF edition once. “Take a look” opens only the GitHub project homepage to view source and other content. “Understood” saves acknowledgement, so the notice does not repeat. Users who acknowledged the earlier notice see this changed connection-only policy once.

Version checks remain available, including matching releases and prereleases. A new-version notice offers the project-home link and “Later”; it never downloads or installs files or opens a Release download page. “Later” suppresses automatic reminders for that version during the current game process. A manual check can show it again. Manual checks show checking, current and failed states; background check failures do not open a dialog.

首次打开模组 UI 时显示一次「CF 精简版」说明。「去看看」仅打开 GitHub 项目主页，查看源码及其他项目内容，不自动下载或安装文件。「知道了」保存确认记录，以后不再重复提示。此前确认过旧说明的用户会看到一次新的连接模式说明。

发现新版本时，仅告知版本状态。「去看看」打开项目主页，「下次再说」使同一版本在本次游戏进程内不再自动提醒；手动检查仍可重新显示。手动检查显示「正在检查」「没有匹配的新版本」「检查失败」等状态，后台检查失败不主动弹窗。GitHub 普通发行版继续保留完整的 TuneWeave 管理能力。
