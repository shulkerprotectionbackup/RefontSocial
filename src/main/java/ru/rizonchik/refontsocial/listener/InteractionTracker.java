package ru.rizonchik.refontsocial.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class InteractionTracker implements Listener {

    private final JavaPlugin plugin;

    private final Map<UUID, Map<UUID, Long>> lastInteraction = new ConcurrentHashMap<>();

    private int taskId = -1;

    public InteractionTracker(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        double radius = plugin.getConfig().getDouble("antiAbuse.requireInteraction.radiusBlocks", 100.0);
        long period = plugin.getConfig().getLong("antiAbuse.requireInteraction.taskPeriodTicks", 40L);
        if (period < 20L) period = 20L;

        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> tick(radius), period, period);
    }

    public void shutdown() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        lastInteraction.clear();
    }

    private void tick(double radius) {
        long now = System.currentTimeMillis();

        for (Player p : Bukkit.getOnlinePlayers()) {
            List<Player> nearby = new ArrayList<>();
            for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
                if (e instanceof Player) nearby.add((Player) e);
            }

            for (Player other : nearby) {
                if (other == null || !other.isOnline()) continue;
                if (other.getUniqueId().equals(p.getUniqueId())) continue;

                UUID a = p.getUniqueId();
                UUID b = other.getUniqueId();
                lastInteraction
                        .computeIfAbsent(a, k -> new ConcurrentHashMap<>())
                        .put(b, now);
            }
        }
    }

    public boolean hasRecentInteraction(UUID voter, UUID target, long validMs) {
        Map<UUID, Long> map = lastInteraction.get(voter);
        if (map == null) return false;
        Long t = map.get(target);
        if (t == null) return false;
        return System.currentTimeMillis() - t <= validMs;
    }
}