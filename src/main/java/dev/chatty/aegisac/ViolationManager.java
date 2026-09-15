package dev.chatty.aegisac;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ViolationManager {
    private final AegisAC plugin;
    private final Map<UUID, EnumMap<CheckType, Double>> violations = new HashMap<>();
    private final Map<String, Long> lastAlert = new HashMap<>();
    private final Map<UUID, Long> lastPunishment = new HashMap<>();

    ViolationManager(AegisAC plugin) {
        this.plugin = plugin;
    }

    public boolean enabled(CheckType type) {
        return plugin.getConfig().getBoolean("checks." + type.key() + ".enabled", true);
    }

    public double flag(Player player, CheckType type, double amount, String detail) {
        if (!enabled(type) || player.hasPermission("aegisac.bypass")) return get(player, type);

        EnumMap<CheckType, Double> map = violations.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(CheckType.class));
        double vl = Math.min(100.0, map.getOrDefault(type, 0.0) + Math.max(0.05, amount));
        map.put(type, vl);

        maybeAlert(player, type, vl, detail);
        maybePunish(player);
        return vl;
    }

    private void maybeAlert(Player player, CheckType type, double vl, String detail) {
        double min = plugin.getConfig().getDouble("settings.alert-min-vl", 1.0);
        if (vl < min) return;

        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("settings.alert-cooldown-ms", 900L);
        String key = player.getUniqueId() + ":" + type.name();
        if (now - lastAlert.getOrDefault(key, 0L) < cooldown) return;
        lastAlert.put(key, now);

        String msg = plugin.color(plugin.prefix()
                + "&f" + player.getName()
                + " &7failed &b" + type.display()
                + " &8(&cVL " + String.format("%.1f", vl) + "&8)"
                + (detail == null || detail.isBlank() ? "" : " &7" + detail));

        Bukkit.getConsoleSender().sendMessage(msg);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("aegisac.alerts") && plugin.hasAlerts(online.getUniqueId())) {
                online.sendMessage(msg);
            }
        }
    }

    private void maybePunish(Player player) {
        if (!plugin.getConfig().getBoolean("punishments.enabled", false)) return;

        double global = global(player);
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("punishments.global-vl-cooldown-ms", 15000L);
        if (now - lastPunishment.getOrDefault(player.getUniqueId(), 0L) < cooldown) return;

        int selectedThreshold = -1;
        String selectedCommand = null;
        if (plugin.getConfig().isConfigurationSection("punishments.commands")) {
            for (String key : plugin.getConfig().getConfigurationSection("punishments.commands").getKeys(false)) {
                try {
                    int threshold = Integer.parseInt(key);
                    if (global >= threshold && threshold > selectedThreshold) {
                        selectedThreshold = threshold;
                        selectedCommand = plugin.getConfig().getString("punishments.commands." + key);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (selectedCommand != null && !selectedCommand.isBlank()) {
            lastPunishment.put(player.getUniqueId(), now);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), selectedCommand.replace("%player%", player.getName()));
        }
    }

    public boolean shouldCancel(Player player, CheckType type, double vl) {
        double threshold = plugin.getConfig().getDouble("checks." + type.key() + ".cancel-vl", Double.MAX_VALUE);
        return vl >= threshold;
    }

    public boolean shouldSetback(Player player, CheckType type, double vl) {
        double threshold = plugin.getConfig().getDouble("checks." + type.key() + ".setback-vl", Double.MAX_VALUE);
        return vl >= threshold;
    }

    public double get(Player player, CheckType type) {
        EnumMap<CheckType, Double> map = violations.get(player.getUniqueId());
        return map == null ? 0.0 : map.getOrDefault(type, 0.0);
    }

    public double global(Player player) {
        EnumMap<CheckType, Double> map = violations.get(player.getUniqueId());
        if (map == null) return 0.0;
        return map.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    public EnumMap<CheckType, Double> snapshot(Player player) {
        EnumMap<CheckType, Double> copy = new EnumMap<>(CheckType.class);
        EnumMap<CheckType, Double> current = violations.get(player.getUniqueId());
        if (current != null) copy.putAll(current);
        return copy;
    }

    public void reset(Player player) {
        violations.remove(player.getUniqueId());
    }

    public void remove(Player player) {
        violations.remove(player.getUniqueId());
        lastPunishment.remove(player.getUniqueId());
    }

    public void decay() {
        double decay = plugin.getConfig().getDouble("settings.violation-decay-per-second", 0.12);
        if (decay <= 0) return;

        violations.entrySet().removeIf(entry -> {
            EnumMap<CheckType, Double> map = entry.getValue();
            map.replaceAll((type, vl) -> Math.max(0.0, vl - decay));
            map.entrySet().removeIf(e -> e.getValue() <= 0.001);
            return map.isEmpty();
        });
    }
}
