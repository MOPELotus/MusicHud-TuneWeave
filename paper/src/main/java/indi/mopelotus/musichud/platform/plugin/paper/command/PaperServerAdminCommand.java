package indi.mopelotus.musichud.platform.plugin.paper.command;

import indi.mopelotus.musichud.Version;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.PlaybackSession;
import indi.mopelotus.musichud.server.api.MusicPlayerServerService;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

/** Styled Paper admin command for the TuneWeave public playback server. */
public final class PaperServerAdminCommand implements BasicCommand {
    private static final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + "MusicHud TuneWeave" + ChatColor.DARK_GRAY + "] ";
    private static final List<String> ROOT = List.of("help", "status", "player", "api", "playback");

    private String pluginVersion;

    public void register(org.bukkit.plugin.java.JavaPlugin plugin) {
        pluginVersion = plugin.getPluginMeta().getVersion();
        if (Bukkit.getPluginManager().getPermission("musichud.admin") == null) {
            Bukkit.getPluginManager().addPermission(new Permission("musichud.admin", "MusicHud TuneWeave administrator commands", PermissionDefault.OP));
        }
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("musichud", "MusicHud TuneWeave administration",
                        List.of("musichud-tuneweave", "mt"), this));
    }

    @Override public void execute(CommandSourceStack stack, String[] args) {
        handleCommand(stack.getSender(), "musichud", args);
    }

    private void handleCommand(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help", "?" -> help(sender, label);
            case "status" -> status(sender);
            case "player" -> player(sender, args);
            case "api" -> api(sender, args);
            case "playback" -> playback(sender, args);
            default -> { error(sender, "未知子命令: " + args[0]); hint(sender, "使用 /" + label + " help 查看帮助。"); }
        }
    }

    private void help(CommandSender sender, String label) {
        raw(sender, ChatColor.GOLD + "━━━━━━━━ MusicHud TuneWeave ━━━━━━━━");
        raw(sender, ChatColor.AQUA + "基础");
        raw(sender, ChatColor.WHITE + "/" + label + " status" + ChatColor.GRAY + "  查看服务端、公共播放和队列状态");
        raw(sender, ChatColor.WHITE + "/" + label + " api status" + ChatColor.GRAY + "  查看 TuneWeave API 客户端分布式模式");
        raw(sender, ChatColor.WHITE + "/" + label + " playback skip" + ChatColor.GRAY + "  管理员强制切歌");
        raw(sender, ChatColor.GRAY + "音乐平台账号与凭据由客户端持有；服务端不会显示 Cookie 或 token。");
        raw(sender, ChatColor.DARK_GRAY + "别名: /mt /musichud /musichud-tuneweave");
    }

    private void status(CommandSender sender) {
        MusicPlayerServerService service = MusicPlayerServerService.getInstance();
        PlaybackSession session = service.getCurrentPlaybackSession();
        MusicDetail detail = session == null ? MusicDetail.NONE : session.musicDetail();
        header(sender, "服务端状态");
        field(sender, "版本", pluginVersion);
        field(sender, "协议版本", Version.CURRENT);
        field(sender, "API 状态", "服务端不托管（客户端分布式）");
        field(sender, "在线公共会话", session != null && session.isActive() ? "运行中" : "空闲");
        field(sender, "当前播放", detail == null || detail == MusicDetail.NONE ? "无" : detail.getName());
        field(sender, "队列数量", service.getMusicQueue().size());
        field(sender, "账号状态", "网易云 / QQ / Bilibili 由客户端持有");
    }

    private void player(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (args.length < 2) { hint(sender, "用法: /musichud player <玩家名>"); return; }
        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) { error(sender, "未找到在线玩家: " + args[1]); return; }
        boolean tracked = indi.mopelotus.musichud.server.ServerPlayerRegistry.getInstance().contains(player.getUniqueId());
        header(sender, "玩家详情");
        field(sender, "玩家", player.getName());
        field(sender, "UUID", player.getUniqueId());
        field(sender, "TuneWeave 连接", tracked ? "已连接" : "未连接");
        field(sender, "账号", "平台账号由客户端持有");
    }

    private void api(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("status")) {
            hint(sender, "用法: /musichud api status"); return;
        }
        header(sender, "API 管理");
        field(sender, "状态", "服务端不托管（客户端分布式）");
    }

    private void playback(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) return;
        if (args.length < 2 || !args[1].equalsIgnoreCase("skip")) {
            hint(sender, "用法: /musichud playback skip"); return;
        }
        MusicPlayerServerService.getInstance().forceSkipCurrent();
        success(sender, "已请求管理员强制切歌。");
    }

    private void header(CommandSender sender, String title) { raw(sender, ChatColor.GOLD + "━━━━━━━━ " + title + " ━━━━━━━━"); }
    private void field(CommandSender sender, String key, Object value) { raw(sender, PREFIX + ChatColor.YELLOW + key + ChatColor.DARK_GRAY + " » " + ChatColor.WHITE + value); }
    private void success(CommandSender sender, String value) { raw(sender, PREFIX + ChatColor.GREEN + value); }
    private void error(CommandSender sender, String value) { raw(sender, PREFIX + ChatColor.RED + value); }
    private void hint(CommandSender sender, String value) { raw(sender, PREFIX + ChatColor.GRAY + value); }
    private void raw(CommandSender sender, String value) { sender.sendMessage(value); }

    private boolean requireAdmin(CommandSender sender) {
        if (!(sender instanceof Player player) || player.hasPermission("musichud.admin")) return true;
        error(sender, "你没有权限执行这个命令，需要权限: musichud.admin");
        return false;
    }

    @Override public List<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length == 1) return ROOT.stream().filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("api")) return List.of("status");
        if (args.length == 2 && args[0].equalsIgnoreCase("playback")) return List.of("skip");
        if (args.length == 2 && args[0].equalsIgnoreCase("player")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        return List.of();
    }
}
