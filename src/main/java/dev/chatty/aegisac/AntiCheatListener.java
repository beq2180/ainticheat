package dev.chatty.aegisac;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class AntiCheatListener implements Listener {
    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final AegisAC plugin;
    private final ViolationManager violations;

    AntiCheatListener(AegisAC plugin, ViolationManager violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.data(player);
        data.lastSafe = player.getLocation().clone();
        exemptTeleport(data);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.removeData(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PlayerData data = plugin.data(event.getPlayer());
        data.lastSafe = event.getPlayer().getLocation().clone();
        exemptTeleport(data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerData data = plugin.data(event.getPlayer());
        if (event.getTo() != null) data.lastSafe = event.getTo().clone();
        exemptTeleport(data);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null || player.hasPermission("aegisac.bypass")) return;
        if (event instanceof PlayerTeleportEvent) return;
        if (event.getFrom().getWorld() != to.getWorld()) return;

        PlayerData data = plugin.data(player);
        long now = System.currentTimeMillis();

        double dx = to.getX() - event.getFrom().getX();
        double dy = to.getY() - event.getFrom().getY();
        double dz = to.getZ() - event.getFrom().getZ();
        double horizontal = Math.hypot(dx, dz);
        boolean positionChanged = Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1.0E-5;

        if (positionChanged && violations.enabled(CheckType.TIMER)) {
            data.moveTimes.addLast(now);
            trimTimes(data.moveTimes, now - 1000L);
            if (data.moveTimes.size() > 29 && now > data.teleportExemptUntil && !generalMovementExempt(player)) {
                double vl = violations.flag(player, CheckType.TIMER, 0.45,
                        "movePackets=" + data.moveTimes.size() + "/s");
                if (violations.shouldCancel(player, CheckType.TIMER, vl)) {
                    event.setCancelled(true);
                    setback(player, data);
                    return;
                }
            }
        }

        if (now <= data.teleportExemptUntil || generalMovementExempt(player)) {
            updateSafe(player, data, to);
            return;
        }

        boolean velocityGrace = now <= data.velocityExemptUntil;
        boolean environmentExempt = isSpecialMovementEnvironment(player, to);

        if (violations.enabled(CheckType.NOCLIP) && positionChanged && !environmentExempt) {
            boolean fromSolid = insideOccluding(event.getFrom());
            boolean toSolid = insideOccluding(to);
            if (!fromSolid && toSolid) {
                double vl = violations.flag(player, CheckType.NOCLIP, 1.0, "entered a full collision block");
                if (violations.shouldCancel(player, CheckType.NOCLIP, vl)) {
                    event.setCancelled(true);
                    setback(player, data);
                    return;
                }
            }
        }

        if (violations.enabled(CheckType.SPEED) && !environmentExempt && !velocityGrace && horizontal > 0.0) {
            double allowed = allowedHorizontal(player, to);
            if (horizontal > allowed) {
                double severity = Math.min(2.0, Math.max(0.35, (horizontal - allowed) * 2.5));
                double vl = violations.flag(player, CheckType.SPEED, severity,
                        String.format("h=%.3f max=%.3f", horizontal, allowed));
                if (violations.shouldCancel(player, CheckType.SPEED, vl)) {
                    event.setCancelled(true);
                    if (violations.shouldSetback(player, CheckType.SPEED, vl)) setback(player, data);
                    return;
                }
            }
        }

        if (violations.enabled(CheckType.FLY) && !environmentExempt && !velocityGrace) {
            AttributeInstance jump = player.getAttribute(Attribute.JUMP_STRENGTH);
            double jumpStrength = jump == null ? 0.42 : jump.getValue();
            double maxRise = jumpStrength + 0.26;

            if (!player.isOnGround() && dy > maxRise && !nearBounceBlock(to)) {
                double vl = violations.flag(player, CheckType.FLY, 0.9,
                        String.format("rise=%.3f max=%.3f", dy, maxRise));
                if (violations.shouldCancel(player, CheckType.FLY, vl)) {
                    event.setCancelled(true);
                    if (violations.shouldSetback(player, CheckType.FLY, vl)) setback(player, data);
                    return;
                }
            }

            if (!player.isOnGround() && horizontal > 0.035 && Math.abs(dy) < 0.012) {
                data.hoverTicks++;
                if (data.hoverTicks >= 8) {
                    double vl = violations.flag(player, CheckType.FLY, 0.55,
                            "hoverTicks=" + data.hoverTicks);
                    if (violations.shouldCancel(player, CheckType.FLY, vl)) {
                        event.setCancelled(true);
                        if (violations.shouldSetback(player, CheckType.FLY, vl)) setback(player, data);
                        return;
                    }
                }
            } else if (Math.abs(dy) > 0.03 || player.isOnGround()) {
                data.hoverTicks = 0;
            }
        }

        updateSafe(player, data, to);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Entity victim = event.getEntity();

        if (damager instanceof Player attacker && victim instanceof LivingEntity living
                && !attacker.hasPermission("aegisac.bypass") && attacker.getGameMode() != GameMode.SPECTATOR) {

            if (violations.enabled(CheckType.REACH)) {
                double distance = distanceToBox(attacker.getEyeLocation().toVector(), living.getBoundingBox());
                AttributeInstance range = attacker.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
                double baseRange = range == null ? 3.0 : range.getValue();
                double allowed = baseRange + 0.38;

                if (distance > allowed) {
                    double vl = violations.flag(attacker, CheckType.REACH,
                            Math.min(2.0, 0.7 + (distance - allowed) * 1.3),
                            String.format("reach=%.2f max=%.2f", distance, allowed));
                    if (violations.shouldCancel(attacker, CheckType.REACH, vl)) {
                        event.setCancelled(true);
                    }
                }
            }

            if (violations.enabled(CheckType.KILLAURA)) {
                PlayerData data = plugin.data(attacker);
                long nowNs = System.nanoTime();

                Vector eye = attacker.getEyeLocation().toVector();
                Vector center = living.getBoundingBox().getCenter();
                Vector toward = center.clone().subtract(eye);
                double len = toward.length();
                if (len > 0.001) {
                    toward.multiply(1.0 / len);
                    double dot = attacker.getEyeLocation().getDirection().normalize().dot(toward);
                    if (dot < -0.08 && len > 1.1) {
                        double vl = violations.flag(attacker, CheckType.KILLAURA, 0.9,
                                String.format("attackAngleDot=%.2f", dot));
                        if (violations.shouldCancel(attacker, CheckType.KILLAURA, vl)) event.setCancelled(true);
                    }
                }

                long deltaMs = data.lastAttackNs == 0L ? Long.MAX_VALUE : (nowNs - data.lastAttackNs) / 1_000_000L;
                if (data.lastAttackTarget != null && !data.lastAttackTarget.equals(living.getUniqueId()) && deltaMs < 85L) {
                    data.rapidTargetSwitches++;
                    if (data.rapidTargetSwitches >= 3) {
                        double vl = violations.flag(attacker, CheckType.KILLAURA, 0.75,
                                "rapidTargetSwitches=" + data.rapidTargetSwitches);
                        if (violations.shouldCancel(attacker, CheckType.KILLAURA, vl)) event.setCancelled(true);
                    }
                } else {
                    data.rapidTargetSwitches = Math.max(0, data.rapidTargetSwitches - 1);
                }
                data.lastAttackTarget = living.getUniqueId();
                data.lastAttackNs = nowNs;
            }

            if (violations.enabled(CheckType.AUTOCLICKER)
                    && violations.shouldCancel(attacker, CheckType.AUTOCLICKER, violations.get(attacker, CheckType.AUTOCLICKER))) {
                event.setCancelled(true);
            }
        }

        if (victim instanceof Player player && !player.hasPermission("aegisac.bypass") && !event.isCancelled()) {
            PlayerData data = plugin.data(player);
            data.expectingCombatVelocityUntil = System.currentTimeMillis() + 300L;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerArmSwingEvent event) {
        Player player = event.getPlayer();
        if (event.getHand() != EquipmentSlot.HAND || player.hasPermission("aegisac.bypass")
                || !violations.enabled(CheckType.AUTOCLICKER)) return;

        PlayerData data = plugin.data(player);
        long nowMs = System.currentTimeMillis();
        long nowNs = System.nanoTime();
        data.swingTimes.addLast(nowMs);
        trimTimes(data.swingTimes, nowMs - 1000L);

        if (data.lastSwingNs != 0L) {
            double intervalMs = (nowNs - data.lastSwingNs) / 1_000_000.0;
            if (intervalMs > 1.0 && intervalMs < 500.0) {
                data.swingIntervalsMs.addLast(intervalMs);
                while (data.swingIntervalsMs.size() > 30) data.swingIntervalsMs.removeFirst();
            }
        }
        data.lastSwingNs = nowNs;

        int cps = data.swingTimes.size();
        if (cps > 22) {
            violations.flag(player, CheckType.AUTOCLICKER, 0.9 + (cps - 22) * 0.08, "cps=" + cps);
        }

        if (cps >= 14 && data.swingIntervalsMs.size() >= 18) {
            double mean = mean(data.swingIntervalsMs);
            double std = stddev(data.swingIntervalsMs, mean);
            if (mean > 25.0 && mean < 105.0 && std < 2.25) {
                violations.flag(player, CheckType.AUTOCLICKER, 0.75,
                        String.format("regularity std=%.2fms cps=%d", std, cps));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("aegisac.bypass") || player.getGameMode() == GameMode.CREATIVE) return;

        PlayerData data = plugin.data(player);
        Block block = event.getBlock();
        long nowMs = System.currentTimeMillis();
        long nowNs = System.nanoTime();

        data.breakTimes.addLast(nowMs);
        trimTimes(data.breakTimes, nowMs - 1000L);

        if (violations.enabled(CheckType.NUKER)) {
            int last250 = countSince(data.breakTimes, nowMs - 250L);
            if (data.breakTimes.size() > 28 || last250 > 10) {
                double vl = violations.flag(player, CheckType.NUKER, 1.0,
                        "breaks=" + data.breakTimes.size() + "/s burst=" + last250);
                if (violations.shouldCancel(player, CheckType.NUKER, vl)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        if (violations.enabled(CheckType.FASTBREAK) && block.getType().isBlock() && block.getType().getHardness() >= 1.2f) {
            if (data.lastHardBreakNs != 0L) {
                double intervalMs = (nowNs - data.lastHardBreakNs) / 1_000_000.0;
                if (intervalMs < 42.0) data.fastHardBreakStreak++;
                else data.fastHardBreakStreak = Math.max(0, data.fastHardBreakStreak - 1);

                if (data.fastHardBreakStreak >= 4) {
                    double vl = violations.flag(player, CheckType.FASTBREAK, 0.85,
                            String.format("hardBreak=%.1fms streak=%d", intervalMs, data.fastHardBreakStreak));
                    if (violations.shouldCancel(player, CheckType.FASTBREAK, vl)) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
            data.lastHardBreakNs = nowNs;
        }

        handleXray(player, data, block, nowMs);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("aegisac.bypass") || player.getGameMode() == GameMode.CREATIVE
                || !violations.enabled(CheckType.SCAFFOLD)) return;

        PlayerData data = plugin.data(player);
        long now = System.currentTimeMillis();
        data.placeTimes.addLast(now);
        trimTimes(data.placeTimes, now - 1000L);

        Location placed = event.getBlockPlaced().getLocation().add(0.5, 0.5, 0.5);
        Location feet = player.getLocation();
        double horizontal = Math.hypot(placed.getX() - feet.getX(), placed.getZ() - feet.getZ());
        boolean beneath = placed.getY() < feet.getY() + 0.05 && horizontal < 1.65;
        int recent = data.placeTimes.size();

        if (beneath && recent >= 8 && player.getLocation().getPitch() < 55.0f && player.isSprinting()) {
            double vl = violations.flag(player, CheckType.SCAFFOLD, 0.75,
                    "placements=" + recent + "/s pitch=" + String.format("%.1f", player.getLocation().getPitch()));
            if (violations.shouldCancel(player, CheckType.SCAFFOLD, vl)) event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("aegisac.bypass")) return;

        PlayerData data = plugin.data(player);
        long now = System.currentTimeMillis();
        Vector velocity = event.getVelocity();
        double horizontal = Math.hypot(velocity.getX(), velocity.getZ());

        long grace = plugin.getConfig().getLong("settings.velocity-grace-ms", 900L);
        if (horizontal > 0.08 || Math.abs(velocity.getY()) > 0.08) {
            data.velocityExemptUntil = Math.max(data.velocityExemptUntil, now + grace);
        }

        if (violations.enabled(CheckType.ANTIKB) && now <= data.expectingCombatVelocityUntil && horizontal >= 0.12) {
            data.pendingVelocity = new PlayerData.PendingVelocity(player.getLocation().clone(), velocity, now + 360L);
            data.expectingCombatVelocityUntil = 0L;
        }
    }

    public void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("aegisac.bypass")) continue;
            PlayerData data = plugin.data(player);
            PlayerData.PendingVelocity pending = data.pendingVelocity;
            if (pending == null || now < pending.evaluateAtMs) continue;
            data.pendingVelocity = null;

            if (!violations.enabled(CheckType.ANTIKB) || generalMovementExempt(player)
                    || isSpecialMovementEnvironment(player, player.getLocation())
                    || hasBlockingAhead(player, pending.velocity)) continue;

            Location current = player.getLocation();
            if (current.getWorld() != pending.origin.getWorld()) continue;

            double moved = Math.hypot(current.getX() - pending.origin.getX(), current.getZ() - pending.origin.getZ());
            double expected = Math.hypot(pending.velocity.getX(), pending.velocity.getZ());
            double minimum = Math.max(0.075, expected * 0.42);

            if (moved < minimum) {
                double vl = violations.flag(player, CheckType.ANTIKB, 0.9,
                        String.format("response=%.2f expected>=%.2f", moved, minimum));
                double reapplyAt = plugin.getConfig().getDouble("checks.antikb.reapply-vl", 3.0);
                if (vl >= reapplyAt) {
                    Vector corrective = pending.velocity.clone().multiply(0.85);
                    player.setVelocity(corrective);
                    data.velocityExemptUntil = now + plugin.getConfig().getLong("settings.velocity-grace-ms", 900L);
                }
            }
        }
    }

    private void handleXray(Player player, PlayerData data, Block block, long nowMs) {
        Material material = block.getType();
        if (!violations.enabled(CheckType.XRAY)) return;

        if (!isValuableOre(material)) {
            data.blocksSinceOre = Math.min(100, data.blocksSinceOre + 1);
            if (isNaturalMiningBlock(material)) data.xrayScore = Math.max(0.0, data.xrayScore - 0.035);
            return;
        }

        boolean sameVein = data.lastOreLocation != null
                && data.lastOreLocation.getWorld() == block.getWorld()
                && data.lastOreLocation.distanceSquared(block.getLocation()) <= 7.0
                && nowMs - data.lastOreTimeMs < 8000L;

        data.lastOreLocation = block.getLocation().clone();
        data.lastOreTimeMs = nowMs;
        if (sameVein) return;

        int exposed = 0;
        for (BlockFace face : FACES) {
            Block relative = block.getRelative(face);
            if (!relative.getType().isOccluding()) exposed++;
        }

        double weight = switch (material) {
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, ANCIENT_DEBRIS -> 2.1;
            case EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> 1.7;
            default -> 1.0;
        };

        if (exposed <= 1) {
            data.xrayScore += weight;
            if (data.blocksSinceOre <= 5) data.xrayScore += 0.55;
        } else if (exposed >= 3) {
            data.xrayScore = Math.max(0.0, data.xrayScore - 0.8);
        }
        data.blocksSinceOre = 0;

        double alertScore = plugin.getConfig().getDouble("checks.xray.alert-score", 8.0);
        if (data.xrayScore >= alertScore) {
            violations.flag(player, CheckType.XRAY, 1.0,
                    String.format("miningScore=%.1f exposedFaces=%d ore=%s", data.xrayScore, exposed, material.name()));
            data.xrayScore = Math.max(alertScore * 0.65, data.xrayScore - 2.0);
        }
    }

    private void exemptTeleport(PlayerData data) {
        data.teleportExemptUntil = System.currentTimeMillis()
                + plugin.getConfig().getLong("settings.teleport-grace-ms", 1200L);
        data.hoverTicks = 0;
        data.moveTimes.clear();
        data.pendingVelocity = null;
    }

    private void updateSafe(Player player, PlayerData data, Location to) {
        if (player.isOnGround() && !insideOccluding(to) && !isSpecialMovementEnvironment(player, to)) {
            data.lastSafe = to.clone();
        }
    }

    private void setback(Player player, PlayerData data) {
        Location safe = data.lastSafe;
        if (safe != null && safe.getWorld() == player.getWorld()) {
            data.teleportExemptUntil = System.currentTimeMillis() + 700L;
            player.teleport(safe);
        }
    }

    private boolean generalMovementExempt(Player player) {
        return player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || player.isInsideVehicle()
                || player.isFlying()
                || player.getAllowFlight()
                || player.isGliding()
                || player.isRiptiding()
                || player.getHeight() < 1.0
                || player.isDead();
    }

    private boolean isSpecialMovementEnvironment(Player player, Location location) {
        Material feet = location.getBlock().getType();
        Material head = location.clone().add(0, 1.0, 0).getBlock().getType();
        return player.isInWater()
                || isLava(feet) || isLava(head)
                || player.isSwimming()
                || player.isClimbing()
                || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || feet == Material.COBWEB
                || head == Material.COBWEB
                || feet == Material.POWDER_SNOW
                || head == Material.POWDER_SNOW
                || feet == Material.SCAFFOLDING
                || head == Material.SCAFFOLDING;
    }

    private boolean isLava(Material material) {
        return material == Material.LAVA;
    }

    private double allowedHorizontal(Player player, Location to) {
        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        double attributeSpeed = speed == null ? 0.1 : speed.getValue();
        double allowed = Math.max(0.405, attributeSpeed * 3.65);
        if (player.isSprinting()) allowed *= 1.12;
        if (player.isSneaking()) allowed *= 1.08;

        Block below = to.clone().subtract(0, 0.20, 0).getBlock();
        if (below.getType().isBlock()) {
            float slip = below.getType().getSlipperiness();
            if (slip > 0.6f) allowed *= Math.min(1.80, 1.0 + (slip - 0.6f) * 2.2);
        }

        double tps = Bukkit.getTPS()[0];
        double threshold = plugin.getConfig().getDouble("settings.low-tps-threshold", 18.0);
        if (tps > 0.0 && tps < threshold) {
            double maxMultiplier = plugin.getConfig().getDouble("settings.low-tps-max-multiplier", 1.45);
            allowed *= Math.min(maxMultiplier, 20.0 / Math.max(10.0, tps));
        }
        return allowed;
    }

    private boolean nearBounceBlock(Location location) {
        Material below = location.clone().subtract(0, 0.25, 0).getBlock().getType();
        return below == Material.SLIME_BLOCK
                || below == Material.HONEY_BLOCK
                || below == Material.PISTON_HEAD
                || below == Material.MOVING_PISTON;
    }

    private boolean insideOccluding(Location location) {
        Material feet = location.getBlock().getType();
        Material chest = location.clone().add(0, 0.9, 0).getBlock().getType();
        return feet.isOccluding() || chest.isOccluding();
    }

    private boolean hasBlockingAhead(Player player, Vector velocity) {
        Vector horizontal = velocity.clone().setY(0);
        if (horizontal.lengthSquared() < 0.0001) return false;
        horizontal.normalize().multiply(0.55);
        Location ahead = player.getLocation().clone().add(horizontal);
        return ahead.getBlock().getType().isOccluding()
                || ahead.clone().add(0, 1.0, 0).getBlock().getType().isOccluding();
    }

    private double distanceToBox(Vector point, BoundingBox box) {
        double x = clamp(point.getX(), box.getMinX(), box.getMaxX());
        double y = clamp(point.getY(), box.getMinY(), box.getMaxY());
        double z = clamp(point.getZ(), box.getMinZ(), box.getMaxZ());
        double dx = point.getX() - x;
        double dy = point.getY() - y;
        double dz = point.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void trimTimes(Deque<Long> deque, long oldestAllowed) {
        while (!deque.isEmpty() && deque.peekFirst() < oldestAllowed) deque.removeFirst();
    }

    private int countSince(Deque<Long> deque, long cutoff) {
        int count = 0;
        for (long value : deque) if (value >= cutoff) count++;
        return count;
    }

    private double mean(Deque<Double> values) {
        double sum = 0.0;
        for (double v : values) sum += v;
        return values.isEmpty() ? 0.0 : sum / values.size();
    }

    private double stddev(Deque<Double> values, double mean) {
        if (values.size() < 2) return Double.MAX_VALUE;
        double sum = 0.0;
        for (double v : values) {
            double d = v - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / values.size());
    }

    private boolean isValuableOre(Material material) {
        return switch (material) {
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE,
                    EMERALD_ORE, DEEPSLATE_EMERALD_ORE,
                    GOLD_ORE, DEEPSLATE_GOLD_ORE,
                    ANCIENT_DEBRIS -> true;
            default -> false;
        };
    }

    private boolean isNaturalMiningBlock(Material material) {
        return switch (material) {
            case STONE, DEEPSLATE, TUFF, GRANITE, DIORITE, ANDESITE,
                    NETHERRACK, BASALT, BLACKSTONE, CALCITE, DRIPSTONE_BLOCK -> true;
            default -> false;
        };
    }
}
