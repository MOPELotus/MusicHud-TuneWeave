package indi.mopelotus.musichud.platform.plugin.bungeecord.command;

import indi.mopelotus.musichud.Version;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.bungeecord.PluginVersion;
import indi.mopelotus.musichud.server.api.MusicPlayerServerService;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;
import java.util.List;
import java.util.Locale;

public final class BungeeAdminCommand extends Command implements TabExecutor {
    private static final List<String> ROOT = List.of("help", "status", "api", "playback");
    public BungeeAdminCommand() { super("musichud", null, "mt", "musichud-tuneweave", "tuneweave"); }

    @Override public void execute(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help", "?" -> {
                sender.sendMessage(text("━━━━━━━━ MusicHud TuneWeave ━━━━━━━━", ChatColor.GOLD));
                sender.sendMessage(text("/mt status  查看代理、公共播放和队列状态", ChatColor.WHITE));
                sender.sendMessage(text("/mt api status  查看 TuneWeave API 客户端分布式模式", ChatColor.WHITE));
                sender.sendMessage(text("/mt playback skip  管理员强制切歌", ChatColor.WHITE));
                sender.sendMessage(text("别名: /mt /musichud /musichud-tuneweave /tuneweave", ChatColor.DARK_GRAY));
            }
            case "status" -> {
                var service = MusicPlayerServerService.getInstance();
                var session = service.getCurrentPlaybackSession();
                var detail = session == null ? MusicDetail.NONE : session.musicDetail();
                sender.sendMessage(text("━━━━━━━━ 服务端状态 ━━━━━━━━", ChatColor.GOLD));
                field(sender, "版本", PluginVersion.VERSION);
                field(sender, "协议版本", Version.CURRENT.toString());
                field(sender, "公共会话", session != null && session.isActive() ? "运行中" : "空闲");
                field(sender, "当前播放", detail == null || detail == MusicDetail.NONE ? "无" : detail.getName());
                field(sender, "队列数量", String.valueOf(service.getMusicQueue().size()));
                field(sender, "账号状态", "网易云 / QQ / Bilibili 由客户端持有");
            }
            case "api" -> {
                if (args.length == 2 && args[1].equalsIgnoreCase("status")) field(sender, "API 状态", "服务端不托管（客户端分布式）");
                else sender.sendMessage(text("用法: /mt api status", ChatColor.GRAY));
            }
            case "playback" -> {
                if (!sender.hasPermission("musichud.admin")) {
                    sender.sendMessage(text("你没有权限执行这个命令，需要权限: musichud.admin", ChatColor.RED));
                } else if (args.length == 2 && args[1].equalsIgnoreCase("skip")) {
                    MusicPlayerServerService.getInstance().forceSkipCurrent();
                    sender.sendMessage(text("[MusicHud TuneWeave] 已请求管理员强制切歌。", ChatColor.GREEN));
                } else sender.sendMessage(text("用法: /mt playback skip", ChatColor.GRAY));
            }
            default -> sender.sendMessage(text("未知子命令: " + args[0], ChatColor.RED));
        }
    }

    private static TextComponent text(String value, ChatColor color) {
        var result = new TextComponent(value);
        result.setColor(color);
        return result;
    }
    private static void field(CommandSender sender, String key, String value) {
        var line = text("[MusicHud TuneWeave] ", ChatColor.DARK_GRAY);
        line.addExtra(text(key, ChatColor.YELLOW));
        line.addExtra(text(" » ", ChatColor.DARK_GRAY));
        line.addExtra(text(value, ChatColor.WHITE));
        sender.sendMessage(line);
    }
    @Override public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length <= 1) return ROOT.stream().filter(v -> args.length == 0 || v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("api")) return List.of("status");
        if (args.length == 2 && args[0].equalsIgnoreCase("playback") && sender.hasPermission("musichud.admin")) return List.of("skip");
        return List.of();
    }
}
