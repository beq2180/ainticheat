package dev.chatty.aegisac;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

public final class PlayerData {
    Location lastSafe;
    long teleportExemptUntil;
    long velocityExemptUntil;

    final Deque<Long> moveTimes = new ArrayDeque<>();
    final Deque<Long> swingTimes = new ArrayDeque<>();
    final Deque<Double> swingIntervalsMs = new ArrayDeque<>();
    final Deque<Long> breakTimes = new ArrayDeque<>();
    final Deque<Long> placeTimes = new ArrayDeque<>();

    long lastSwingNs;
    long lastAttackNs;
    UUID lastAttackTarget;
    int rapidTargetSwitches;
    int hoverTicks;

    long lastHardBreakNs;
    int fastHardBreakStreak;

    int blocksSinceOre = 50;
    Location lastOreLocation;
    long lastOreTimeMs;
    double xrayScore;

    long expectingCombatVelocityUntil;
    PendingVelocity pendingVelocity;

    public static final class PendingVelocity {
        final Location origin;
        final Vector velocity;
        final long evaluateAtMs;

        PendingVelocity(Location origin, Vector velocity, long evaluateAtMs) {
            this.origin = origin;
            this.velocity = velocity.clone();
            this.evaluateAtMs = evaluateAtMs;
        }
    }
}
