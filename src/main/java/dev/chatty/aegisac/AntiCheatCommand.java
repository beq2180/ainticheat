package dev.chatty.aegisac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

public final class AntiCheatCommand implements CommandExecutor, TabCompleter {
    private final AegisAC plugin;
    private final ViolationManager violations;

    AntiCheatCommand(AegisAC plugin, ViolationManager violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("aegisac.command")) {
            sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.no-permission", "&cNo permission.")));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "alerts" -> alerts(sender);
            case "vl" -> vl(sender, args);
            case "reset" -> reset(sender, args);
            case "reload" -> reload(sender);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(plugin.color(plugin.prefix() + "&bCommands"));
        sender.sendMessage(plugin.color("&7/ac status &8- &fshow enabled checks"));
        sender.sendMessage(plugin.color("&7/ac alerts &8- &ftoggle live alerts"));
        sender.sendMessage(plugin.color("&7/ac vl <player> &8- &fshow violations"));
        sender.sendMessage(plugin.color("&7/ac reset <player> &8- &fclear violations"));
        sender.sendMessage(plugin.color("&7/ac reload &8- &freload config"));
    }

    private void status(CommandSender sender) {
        List<String> enabled = new ArrayList<>();
        List<String> disabled = new ArrayList<>();
        for (CheckType type : CheckType.values()) {
            (violations.enabled(type) ? enabled : disabled).add(type.display());
        }
        sender.sendMessage(plugin.color(plugin.prefix() + "&aEnabled: &f" + String.join(", ", enabled)));
        if (!disabled.isEmpty()) sender.sendMessage(plugin.color(plugin.prefix() + "&cDisabled: &f" + String.join(", ", disabled)));
        sender.sendMessage(plugin.color(plugin.prefix() + String.format("&7TPS: &f%.2f", Bukkit.getTPS()[0])));
    }

    private void alerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.color(plugin.prefix() + "&7Console always receives alerts."));
            return;
        }
        if (!player.hasPermission("aegisac.alerts")) {
            player.sendMessage(plugin.color(plugin.getConfig().getString("messages.no-permission", "&cNo permission.")));
            return;
        }
        boolean now = plugin.toggleAlerts(player.getUniqueId());
        player.sendMessage(plugin.color(plugin.prefix() + plugin.getConfig().getString(
                now ? "messages.alerts-on" : "messages.alerts-off",
                now ? "&aAlerts enabled." : "&7Alerts disabled.")));
    }

    private void vl(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.color(plugin.prefix() + "&cUsage: /ac vl <player>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.color(plugin.prefix() + "&cThat player is not online."));
            return;
        }
        EnumMap<CheckType, Double> map = violations.snapshot(target);
        sender.sendMessage(plugin.color(plugin.prefix() + "&fVL for &b" + target.getName()
                + " &8(global " + String.format("%.1f", violations.global(target)) + ")"));
        if (map.isEmpty()) {
            sender.sendMessage(plugin.color("&7No active violations."));
            return;
        }
        for (CheckType type : CheckType.values()) {
            double value = map.getOrDefault(type, 0.0);
            if (value > 0.0) sender.sendMessage(plugin.color("&7- &b" + type.display() + ": &f" + String.format("%.2f", value)));
        }
    }

    private void reset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.color(plugin.prefix() + "&cUsage: /ac reset <player>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.color(plugin.prefix() + "&cThat player is not online."));
            return;
        }
        violations.reset(target);
        sender.sendMessage(plugin.color(plugin.prefix() + "&aReset violations for &f" + target.getName() + "&a."));
    }

    private void reload(CommandSender sender) {
        plugin.reloadConfig();
        sender.sendMessage(plugin.color(plugin.prefix() + "&aConfiguration reloaded."));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("status", "alerts", "vl", "reset", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("vl") || args[0].equalsIgnoreCase("reset"))) {
            String start = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(start)).toList();
        }
        return List.of();
    }
}
