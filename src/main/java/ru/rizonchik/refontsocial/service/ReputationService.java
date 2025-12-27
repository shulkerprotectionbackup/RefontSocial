package ru.rizonchik.refontsocial.service;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.rizonchik.refontsocial.listener.InteractionTracker;
import ru.rizonchik.refontsocial.storage.Storage;
import ru.rizonchik.refontsocial.storage.model.PlayerRep;
import ru.rizonchik.refontsocial.util.Colors;
import ru.rizonchik.refontsocial.util.NumberUtil;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ReputationService {

    private final JavaPlugin plugin;
    private final Storage storage;

    private InteractionTracker interactionTracker;

    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldownGlobal = new ConcurrentHashMap<>();

    public ReputationService(JavaPlugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public void setInteractionTracker(InteractionTracker tracker) {
        this.interactionTracker = tracker;
    }

    public void shutdown() {
        cache.clear();
        cooldownGlobal.clear();
    }

    public PlayerRep getOrCreate(UUID uuid, String name) {
        boolean cacheEnabled = plugin.getConfig().getBoolean("performance.cache.enabled", true);
        int expireSeconds = plugin.getConfig().getInt("performance.cache.expireSeconds", 30);
        long now = System.currentTimeMillis();

        if (cacheEnabled) {
            CacheEntry entry = cache.get(uuid);
            if (entry != null && now - entry.time <= Math.max(1, expireSeconds) * 1000L) {
                return entry.rep;
            }
        }

        PlayerRep rep = storage.getOrCreate(uuid, name);
        if (cacheEnabled) cache.put(uuid, new CacheEntry(rep, now));
        return rep;
    }

    public String getName(UUID uuid) {
        return storage.getLastKnownName(uuid);
    }

    public List<PlayerRep> getTop(int limit, int offset) {
        return storage.getTop(limit, offset);
    }

    public void sendShow(Player viewer, UUID target, String targetName) {
        PlayerRep rep = getOrCreate(target, targetName != null ? targetName : "Player");
        String key = viewer.getUniqueId().equals(target) ? "showSelf" : "showOther";
        viewer.sendMessage(Colors.msg(plugin, key,
                "%target%", targetName != null ? targetName : "Player",
                "%score%", NumberUtil.formatScore(plugin, rep.getScore()),
                "%likes%", String.valueOf(rep.getLikes()),
                "%dislikes%", String.valueOf(rep.getDislikes()),
                "%votes%", String.valueOf(rep.getVotes())
        ));
    }

    public boolean shouldShowVoterName(Player viewer) {
        String mode = plugin.getConfig().getString("profile.history.showVoterNameMode", "PERMISSION");
        if (mode == null) mode = "PERMISSION";
        mode = mode.toUpperCase(java.util.Locale.ROOT);

        if (mode.equals("ALWAYS")) return true;
        if (mode.equals("ANONYMOUS")) return false;

        String perm = plugin.getConfig().getString("profile.history.showVoterNamePermission", "refontsocial.admin");
        if (perm == null || perm.trim().isEmpty()) perm = "refontsocial.admin";

        return viewer != null && viewer.hasPermission(perm);
    }

    private boolean isIpBlocked(Player voter, UUID target, Storage.VoteState state, long now) {
        boolean enabled = plugin.getConfig().getBoolean("antiAbuse.ipProtection.enabled", false);
        if (!enabled) return false;

        if (voter != null && voter.hasPermission("refontsocial.bypass.ip")) return false;

        String mode = plugin.getConfig().getString("antiAbuse.ipProtection.mode", "SAME_IP_DENY");
        if (mode == null) mode = "SAME_IP_DENY";
        mode = mode.toUpperCase(java.util.Locale.ROOT);

        String voterIp = storage.getIpHash(voter.getUniqueId());
        String targetIp = storage.getIpHash(target);

        if (voterIp == null || targetIp == null) return false;
        if (!voterIp.equals(targetIp)) return false;

        if (mode.equals("SAME_IP_DENY")) {
            voter.sendMessage(Colors.msg(plugin, "ipDenied"));
            return true;
        }

        long cd = plugin.getConfig().getLong("antiAbuse.ipProtection.cooldownSeconds", 86400);
        if (cd < 1) cd = 1;
        long cdMs = cd * 1000L;

        if (state != null && state.lastTime != null) {
            long left = (state.lastTime + cdMs) - now;
            if (left > 0) {
                voter.sendMessage(Colors.msg(plugin, "ipCooldown", "%seconds%", String.valueOf(left / 1000L + 1)));
                return true;
            }
        }

        return false;
    }

    public void voteWithReason(Player voter, UUID target, String targetName, boolean like, String reasonTagKey) {
        if (voter == null || target == null) return;

        boolean preventSelf = plugin.getConfig().getBoolean("antiAbuse.preventSelfVote", true);
        if (preventSelf && voter.getUniqueId().equals(target)) {
            voter.sendMessage(Colors.msg(plugin, "selfVoteDenied"));
            return;
        }

        boolean requireHasPlayedBefore = plugin.getConfig().getBoolean("antiAbuse.targetEligibility.requireHasPlayedBefore", true);
        boolean requireTargetOnline = plugin.getConfig().getBoolean("antiAbuse.targetEligibility.requireTargetOnline", false);

        OfflinePlayer off = Bukkit.getOfflinePlayer(target);

        if (requireTargetOnline) {
            if (off == null || !off.isOnline()) {
                voter.sendMessage(Colors.msg(plugin, "targetMustBeOnline"));
                return;
            }
        }

        if (requireHasPlayedBefore) {
            boolean played = false;
            try {
                played = (off != null && off.hasPlayedBefore());
            } catch (Throwable ignored) {
            }
            if (!played && (off == null || !off.isOnline())) {
                voter.sendMessage(Colors.msg(plugin, "targetNeverPlayed"));
                return;
            }
        }

        boolean bypassCooldown = voter.hasPermission("refontsocial.bypass.cooldown");
        boolean bypassInteraction = voter.hasPermission("refontsocial.bypass.interaction");

        long now = System.currentTimeMillis();

        int globalCd = plugin.getConfig().getInt("antiAbuse.cooldowns.voteGlobalSeconds", 20);
        if (!bypassCooldown && globalCd > 0) {
            String key = voter.getUniqueId().toString();
            Long last = cooldownGlobal.get(key);
            if (last != null) {
                long left = (last + globalCd * 1000L) - now;
                if (left > 0) {
                    voter.sendMessage(Colors.msg(plugin, "cooldownGlobal", "%seconds%", String.valueOf(left / 1000L + 1)));
                    return;
                }
            }
        }

        boolean requireInteraction = plugin.getConfig().getBoolean("antiAbuse.requireInteraction.enabled", true);
        if (!bypassInteraction && requireInteraction && interactionTracker != null) {
            long validSeconds = plugin.getConfig().getLong("antiAbuse.requireInteraction.interactionValidSeconds", 600);
            long validMs = Math.max(1, validSeconds) * 1000L;
            if (!interactionTracker.hasRecentInteraction(voter.getUniqueId(), target, validMs)) {
                voter.sendMessage(Colors.msg(plugin, "interactionRequired"));
                return;
            }
        }

        boolean dailyLimit = plugin.getConfig().getBoolean("antiAbuse.dailyLimit.enabled", true);
        if (!bypassCooldown && dailyLimit) {
            int maxPerDay = plugin.getConfig().getInt("antiAbuse.dailyLimit.maxVotesPerDay", 20);
            if (maxPerDay > 0) {
                int used = storage.countVotesByVoterSince(voter.getUniqueId(), NumberUtil.startOfTodayMillis());
                if (used >= maxPerDay) {
                    voter.sendMessage(Colors.msg(plugin, "dailyLimit", "%limit%", String.valueOf(maxPerDay)));
                    return;
                }
            }
        }

        int sameTargetCd = plugin.getConfig().getInt("antiAbuse.cooldowns.sameTargetSeconds", 600);
        int changeVoteCd = plugin.getConfig().getInt("antiAbuse.cooldowns.changeVoteSeconds", 1800);

        Storage.VoteState state = storage.getVoteState(voter.getUniqueId(), target);

        if (isIpBlocked(voter, target, state, now)) {
            return;
        }

        if (!bypassCooldown && state != null) {
            if (sameTargetCd > 0) {
                long left = (state.lastTime + sameTargetCd * 1000L) - now;
                if (left > 0) {
                    voter.sendMessage(Colors.msg(plugin, "cooldownTarget", "%seconds%", String.valueOf(left / 1000L + 1)));
                    return;
                }
            }

            if (state.value != null && state.value != (like ? 1 : 0) && changeVoteCd > 0) {
                long left = (state.lastTime + changeVoteCd * 1000L) - now;
                if (left > 0) {
                    voter.sendMessage(Colors.msg(plugin, "cooldownChangeVote", "%seconds%", String.valueOf(left / 1000L + 1)));
                    return;
                }
            }
        }
        cooldownGlobal.put(voter.getUniqueId().toString(), now);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Storage.VoteResult result = storage.applyVote(
                    voter.getUniqueId(),
                    target,
                    like ? 1 : 0,
                    now,
                    targetName,
                    reasonTagKey
            );

            cache.remove(target);

            PlayerRep rep = getOrCreate(target, targetName != null ? targetName : "Игрок");
            String score = NumberUtil.formatScore(plugin, rep.getScore());

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!voter.isOnline()) return;

                String safeTargetName = safeName(target, targetName);

                if (result == Storage.VoteResult.CREATED) {
                    voter.sendMessage(Colors.msg(plugin, like ? "voteLikeDone" : "voteDislikeDone",
                            "%target%", safeTargetName,
                            "%score%", score
                    ));
                } else if (result == Storage.VoteResult.CHANGED) {
                    voter.sendMessage(Colors.msg(plugin, "voteChanged",
                            "%target%", safeTargetName,
                            "%score%", score
                    ));
                } else {
                    voter.sendMessage(Colors.msg(plugin, "voteRemoved",
                            "%target%", safeTargetName,
                            "%score%", score
                    ));
                }

                String display = plugin.getConfig().getString("reasons.tags." + reasonTagKey, reasonTagKey);
                voter.sendMessage(Colors.msg(plugin, "reasonSaved", "%reason%", display));
            });
        });
    }

    public void vote(Player voter, UUID target, String targetName, boolean like) {
        if (voter == null || target == null) return;

        boolean preventSelf = plugin.getConfig().getBoolean("antiAbuse.preventSelfVote", true);
        if (preventSelf && voter.getUniqueId().equals(target)) {
            voter.sendMessage(Colors.msg(plugin, "selfVoteDenied"));
            return;
        }

        boolean requireHasPlayedBefore = plugin.getConfig().getBoolean("antiAbuse.targetEligibility.requireHasPlayedBefore", true);
        boolean requireTargetOnline = plugin.getConfig().getBoolean("antiAbuse.targetEligibility.requireTargetOnline", false);

        OfflinePlayer off = Bukkit.getOfflinePlayer(target);

        if (requireTargetOnline) {
            if (off == null || !off.isOnline()) {
                voter.sendMessage(Colors.msg(plugin, "targetMustBeOnline"));
                return;
            }
        }

        if (requireHasPlayedBefore) {
            if (off == null) {
                voter.sendMessage(Colors.msg(plugin, "targetNotFound"));
                return;
            }

            boolean hasPlayedBefore = false;
            try {
                hasPlayedBefore = off.hasPlayedBefore();
            } catch (Throwable ignored) {
            }

            if (!hasPlayedBefore) {
                voter.sendMessage(Colors.msg(plugin, "targetNeverPlayed"));
                return;
            }
        }

        boolean bypassCooldown = voter.hasPermission("refontsocial.bypass.cooldown");
        boolean bypassInteraction = voter.hasPermission("refontsocial.bypass.interaction");

        long now = System.currentTimeMillis();

        int globalCd = plugin.getConfig().getInt("antiAbuse.cooldowns.voteGlobalSeconds", 20);
        if (!bypassCooldown && globalCd > 0) {
            String key = voter.getUniqueId().toString();
            Long last = cooldownGlobal.get(key);
            if (last != null) {
                long left = (last + globalCd * 1000L) - now;
                if (left > 0) {
                    voter.sendMessage(Colors.msg(plugin, "cooldownGlobal", "%seconds%", String.valueOf(left / 1000L + 1)));
                    return;
                }
            }
        }

        boolean requireInteraction = plugin.getConfig().getBoolean("antiAbuse.requireInteraction.enabled", true);
        if (!bypassInteraction && requireInteraction && interactionTracker != null) {
            long validSeconds = plugin.getConfig().getLong("antiAbuse.requireInteraction.interactionValidSeconds", 600);
            long validMs = Math.max(1, validSeconds) * 1000L;
            if (!interactionTracker.hasRecentInteraction(voter.getUniqueId(), target, validMs)) {
                voter.sendMessage(Colors.msg(plugin, "interactionRequired"));
                return;
            }
        }

        boolean dailyLimit = plugin.getConfig().getBoolean("antiAbuse.dailyLimit.enabled", true);
        if (!bypassCooldown && dailyLimit) {
            int maxPerDay = plugin.getConfig().getInt("antiAbuse.dailyLimit.maxVotesPerDay", 20);
            if (maxPerDay > 0) {
                int used = storage.countVotesByVoterSince(voter.getUniqueId(), NumberUtil.startOfTodayMillis());
                if (used >= maxPerDay) {
                    voter.sendMessage(Colors.msg(plugin, "dailyLimit", "%limit%", String.valueOf(maxPerDay)));
                    return;
                }
            }
        }

        int sameTargetCd = plugin.getConfig().getInt("antiAbuse.cooldowns.sameTargetSeconds", 600);
        int changeVoteCd = plugin.getConfig().getInt("antiAbuse.cooldowns.changeVoteSeconds", 1800);

        Storage.VoteState state = storage.getVoteState(voter.getUniqueId(), target);

        if (isIpBlocked(voter, target, state, now)) {
            return;
        }

        if (!bypassCooldown && state != null) {
            if (sameTargetCd > 0) {
                long left = (state.lastTime + sameTargetCd * 1000L) - now;
                if (left > 0) {
                    voter.sendMessage(Colors.msg(plugin, "cooldownTarget", "%seconds%", String.valueOf(left / 1000L + 1)));
                    return;
                }
            }

            if (state.value != null && state.value != (like ? 1 : 0) && changeVoteCd > 0) {
                long left = (state.lastTime + changeVoteCd * 1000L) - now;
                if (left > 0) {
                    voter.sendMessage(Colors.msg(plugin, "cooldownChangeVote", "%seconds%", String.valueOf(left / 1000L + 1)));
                    return;
                }
            }
        }

        boolean reasonsEnabled = plugin.getConfig().getBoolean("reasons.enabled", true);
        boolean requireReason = plugin.getConfig().getBoolean("reasons.requireReason", false);

        if (reasonsEnabled && requireReason) {
            voter.sendMessage(Colors.msg(plugin, "reasonRequired"));
            return;
        }

        String reason = null;

        cooldownGlobal.put(voter.getUniqueId().toString(), now);

        Storage.VoteResult result = storage.applyVote(
                voter.getUniqueId(),
                target,
                like ? 1 : 0,
                now,
                targetName,
                reason
        );

        cache.remove(target);

        PlayerRep rep = getOrCreate(target, targetName != null ? targetName : "Player");
        String score = NumberUtil.formatScore(plugin, rep.getScore());

        if (result == Storage.VoteResult.CREATED) {
            voter.sendMessage(Colors.msg(plugin, like ? "voteLikeDone" : "voteDislikeDone",
                    "%target%", safeName(target, targetName),
                    "%score%", score
            ));
            return;
        }

        if (result == Storage.VoteResult.CHANGED) {
            voter.sendMessage(Colors.msg(plugin, "voteChanged",
                    "%target%", safeName(target, targetName),
                    "%score%", score
            ));
            return;
        }

        voter.sendMessage(Colors.msg(plugin, "voteRemoved",
                "%target%", safeName(target, targetName),
                "%score%", score
        ));
    }

    private String safeName(UUID uuid, String name) {
        if (name != null && !name.trim().isEmpty()) return name;
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        if (off != null && off.getName() != null) return off.getName();
        return uuid.toString().substring(0, 8);
    }

    private static final class CacheEntry {
        private final PlayerRep rep;
        private final long time;

        private CacheEntry(PlayerRep rep, long time) {
            this.rep = rep;
            this.time = time;
        }
    }
}