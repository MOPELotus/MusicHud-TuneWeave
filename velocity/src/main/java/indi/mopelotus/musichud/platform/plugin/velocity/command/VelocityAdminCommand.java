package indi.mopelotus.musichud.platform.plugin.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import indi.mopelotus.musichud.Version;
import indi.mopelotus.musichud.velocity.PluginVersion;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.PlaybackSession;
import indi.mopelotus.musichud.server.api.MusicPlayerServerService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.List;
import java.util.Locale;

/** Proxy-side public-session administration; provider accounts remain client-owned. */
public final class VelocityAdminCommand implements SimpleCommand {
    private static final List<String> ROOT = List.of("help", "status", "api", "playback");

    @Override public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help", "?" -> sendHelp(invocation);
            case "status" -> sendStatus(invocation);
            case "api" -> sendApi(invocation, args);
            case "playback" -> sendPlayback(invocation, args);
            default -> send(invocation, Component.text("未知子命令: " + args[0], NamedTextColor.RED));
        }
    }

    private void sendHelp(Invocation invocation) {
        send(invocation, Component.text("━━━━━━━━ MusicHud TuneWeave ━━━━━━━━", NamedTextColor.GOLD));
        send(invocation, Component.text("基础", NamedTextColor.AQUA));
        send(invocation, Component.text("/mt status", NamedTextColor.WHITE).append(Component.text("  查看代理、公共播放和队列状态", NamedTextColor.GRAY)));
        send(invocation, Component.text("/mt api status", NamedTextColor.WHITE).append(Component.text("  查看 TuneWeave API 客户端分布式模式", NamedTextColor.GRAY)));
        send(invocation, Component.text("/mt playback skip", NamedTextColor.WHITE).append(Component.text("  管理员强制切歌", NamedTextColor.GRAY)));
        send(invocation, Component.text("别名: /mt /musichud /musichud-tuneweave /tuneweave", NamedTextColor.DARK_GRAY));
    }

    private void sendStatus(Invocation invocation) {
        MusicPlayerServerService service = MusicPlayerServerService.getInstance();
        PlaybackSession session = service.getCurrentPlaybackSession();
        MusicDetail detail = session == null ? MusicDetail.NONE : session.musicDetail();
        send(invocation, Component.text("━━━━━━━━ 服务端状态 ━━━━━━━━", NamedTextColor.GOLD));
        field(invocation, "版本", PluginVersion.VERSION);
        field(invocation, "协议版本", Version.CURRENT.toString());
        field(invocation, "公共会话", session != null && session.isActive() ? "运行中" : "空闲");
        field(invocation, "当前播放", detail == null || detail == MusicDetail.NONE ? "无" : detail.getName());
        field(invocation, "队列数量", String.valueOf(service.getMusicQueue().size()));
        field(invocation, "账号状态", "网易云 / QQ / Bilibili 由客户端持有");
    }

    private void sendApi(Invocation invocation, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("status")) {
            send(invocation, Component.text("用法: /mt api status", NamedTextColor.GRAY)); return;
        }
        field(invocation, "API 状态", "服务端不托管（客户端分布式）");
    }

    private void sendPlayback(Invocation invocation, String[] args) {
        if (!invocation.source().hasPermission("musichud.admin")) {
            send(invocation, Component.text("你没有权限执行这个命令，需要权限: musichud.admin", NamedTextColor.RED));
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("skip")) {
            send(invocation, Component.text("用法: /mt playback skip", NamedTextColor.GRAY)); return;
        }
        MusicPlayerServerService.getInstance().forceSkipCurrent();
        send(invocation, Component.text("[MusicHud TuneWeave] 已请求管理员强制切歌。", NamedTextColor.GREEN));
    }

    private void field(Invocation invocation, String key, String value) {
        send(invocation, Component.text("[MusicHud TuneWeave] ", NamedTextColor.DARK_GRAY)
                .append(Component.text(key, NamedTextColor.YELLOW))
                .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                .append(Component.text(value, NamedTextColor.WHITE)));
    }

    private void send(Invocation invocation, Component message) { invocation.source().sendMessage(message); }

    @Override public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) return ROOT.stream().filter(value -> args.length == 0 || value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("api")) return List.of("status");
        if (args.length == 2 && args[0].equalsIgnoreCase("playback")) return List.of("skip");
        return List.of();
    }
}
