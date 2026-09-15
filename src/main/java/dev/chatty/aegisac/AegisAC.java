package dev.chatty.aegisac;

import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AegisAC extends JavaPlugin {
    private final Map<UUID, PlayerData> playerData = new HashMap<>();
    private final Set<UUID> alertViewers = new HashSet<>();
    private ViolationManager violations;
    private AntiCheatListener listener;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        violations = new ViolationManager(this);
        listener = new AntiCheatListener(this, violations);
        getServer().getPluginManager().registerEvents(listener, this);

        AntiCheatCommand commandHandler = new AntiCheatCommand(this, violations);
        PluginCommand command = getCommand("aegisac");
        if (command != null) {
            command.setExecutor(commandHandler);
            command.setTabCompleter(commandHandler);
        }

        for (Player player : getServer().getOnlinePlayers()) {
            playerData.put(player.getUniqueId(), new PlayerData());
            playerData.get(player.getUniqueId()).lastSafe = player.getLocation().clone();
        }

        // Main-thread maintenance: AntiKB evaluation and violation decay.
        getServer().getScheduler().runTaskTimer(this, listener::tick, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, violations::decay, 20L, 20L);

        getLogger().info("AegisAC 1.0.0 enabled for Paper 1.21.11.");
        getLogger().info("Tip: enable Paper's native Anti-Xray as well; AegisAC's X-Ray check is heuristic.");
    }

    @Override
    public void onDisable() {
        playerData.clear();
        alertViewers.clear();
    }

    PlayerData data(Player player) {
        return playerData.computeIfAbsent(player.getUniqueId(), id -> {
            PlayerData data = new PlayerData();
            data.lastSafe = player.getLocation().clone();
            return data;
        });
    }

    void removeData(Player player) {
        playerData.remove(player.getUniqueId());
        alertViewers.remove(player.getUniqueId());
        // Keep VL only while online; this is a lightweight real-time AC, not a database.
        violations.remove(player);
    }

    boolean hasAlerts(UUID uuid) {
        return alertViewers.contains(uuid);
    }

    boolean toggleAlerts(UUID uuid) {
        if (alertViewers.remove(uuid)) return false;
        alertViewers.add(uuid);
        return true;
    }

    String prefix() {
        return getConfig().getString("messages.prefix", "&8[&bAegisAC&8]&r ");
    }

    String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
