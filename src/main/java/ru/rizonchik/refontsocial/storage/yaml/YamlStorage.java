package ru.rizonchik.refontsocial.storage.yaml;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ru.rizonchik.refontsocial.storage.Storage;
import ru.rizonchik.refontsocial.storage.TopCategory;
import ru.rizonchik.refontsocial.storage.model.PlayerRep;
import ru.rizonchik.refontsocial.storage.model.VoteLogEntry;
import ru.rizonchik.refontsocial.util.NumberUtil;
import ru.rizonchik.refontsocial.util.YamlUtil;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public final class YamlStorage implements Storage {

    private final JavaPlugin plugin;

    private File file;
    private YamlConfiguration yaml;

    public YamlStorage(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void init() {
        file = new File(plugin.getDataFolder(), "data.yml");
        yaml = YamlUtil.load(file);

        if (!yaml.contains("players")) yaml.createSection("players");
        if (!yaml.contains("votes")) yaml.createSection("votes");
        if (!yaml.contains("tags")) yaml.createSection("tags");
        if (!yaml.contains("vote_log")) yaml.createSection("vote_log");

        YamlUtil.save(file, yaml);
    }

    @Override
    public void close() {
        YamlUtil.save(file, yaml);
    }

    @Override
    public PlayerRep getOrCreate(UUID uuid, String name) {
        String path = "players." + uuid.toString();

        if (!yaml.contains(path)) {
            yaml.set(path + ".name", name);
            yaml.set(path + ".likes", 0);
            yaml.set(path + ".dislikes", 0);
            yaml.set(path + ".score", NumberUtil.defaultScore(plugin));
            yaml.set(path + ".seen", false);
            yaml.set(path + ".ipHash", null);
            YamlUtil.save(file, yaml);
        } else if (name != null && !name.isEmpty()) {
            yaml.set(path + ".name", name);
        }

        int likes = yaml.getInt(path + ".likes", 0);
        int dislikes = yaml.getInt(path + ".dislikes", 0);
        double score = yaml.getDouble(path + ".score", NumberUtil.defaultScore(plugin));
        int votes = likes + dislikes;

        return new PlayerRep(uuid, yaml.getString(path + ".name", name), likes, dislikes, votes, score);
    }

    @Override
    public String getLastKnownName(UUID uuid) {
        return yaml.getString("players." + uuid + ".name", null);
    }

    @Override
    public List<PlayerRep> getTop(int limit, int offset) {
        return getTop(TopCategory.SCORE, limit, offset);
    }

    @Override
    public List<PlayerRep> getTop(TopCategory category, int limit, int offset) {
        if (!yaml.contains("players")) return Collections.emptyList();

        Map<String, Object> map = yaml.getConfigurationSection("players").getValues(false);
        List<PlayerRep> list = new ArrayList<>();

        for (String key : map.keySet()) {
            try {
                UUID uuid = UUID.fromString(key);
                String base = "players." + key;

                boolean seen = yaml.getBoolean(base + ".seen", false);
                if (!seen) continue;

                String name = yaml.getString(base + ".name", null);
                int likes = yaml.getInt(base + ".likes", 0);
                int dislikes = yaml.getInt(base + ".dislikes", 0);
                double score = yaml.getDouble(base + ".score", NumberUtil.defaultScore(plugin));

                list.add(new PlayerRep(uuid, name, likes, dislikes, likes + dislikes, score));
            } catch (Exception ignored) {
            }
        }

        Comparator<PlayerRep> cmp;
        if (category == TopCategory.LIKES) {
            cmp = Comparator.comparingInt(PlayerRep::getLikes).reversed()
                    .thenComparingDouble(PlayerRep::getScore).reversed()
                    .thenComparingInt(PlayerRep::getVotes).reversed();
        } else if (category == TopCategory.DISLIKES) {
            cmp = Comparator.comparingInt(PlayerRep::getDislikes).reversed()
                    .thenComparingDouble(PlayerRep::getScore)
                    .thenComparingInt(PlayerRep::getVotes).reversed();
        } else if (category == TopCategory.VOTES) {
            cmp = Comparator.comparingInt(PlayerRep::getVotes).reversed()
                    .thenComparingDouble(PlayerRep::getScore).reversed();
        } else {
            cmp = Comparator.comparingDouble(PlayerRep::getScore).reversed()
                    .thenComparingInt(PlayerRep::getVotes).reversed();
        }

        List<PlayerRep> sorted = list.stream().sorted(cmp).collect(Collectors.toList());

        int from = Math.max(0, offset);
        int to = Math.min(sorted.size(), from + Math.max(0, limit));
        if (from >= to) return Collections.emptyList();
        return sorted.subList(from, to);
    }

    @Override
    public VoteState getVoteState(UUID voter, UUID target) {
        String path = "votes." + voter + "." + target;
        if (!yaml.contains(path)) return null;

        Long lastTime = yaml.getLong(path + ".lastTime", 0L);
        Object v = yaml.get(path + ".value");
        Integer value = (v == null) ? null : yaml.getInt(path + ".value");
        String reason = yaml.getString(path + ".reason", null);

        return new VoteState(lastTime, value, reason);
    }

    @Override
    public VoteResult applyVote(UUID voter, UUID target, int value, long timeMillis, String targetName, String reason) {
        PlayerRep rep = getOrCreate(target, targetName);

        String votePath = "votes." + voter + "." + target;
        Object existingObj = yaml.get(votePath + ".value");
        Integer existing = existingObj == null ? null : yaml.getInt(votePath + ".value");
        String existingReason = yaml.getString(votePath + ".reason", null);

        int likes = rep.getLikes();
        int dislikes = rep.getDislikes();

        VoteResult result;

        if (existing == null) {
            if (value == 1) likes++; else dislikes++;
            yaml.set(votePath + ".value", value);
            yaml.set(votePath + ".reason", reason);
            yaml.set(votePath + ".lastTime", timeMillis);

            addTagCount(target, reason, +1);
            addVoteLog(target, voter, null, value, reason, timeMillis);

            result = VoteResult.CREATED;
        } else if (existing == value) {
            if (value == 1) likes--; else dislikes--;
            yaml.set(votePath + ".value", null);
            yaml.set(votePath + ".reason", null);
            yaml.set(votePath + ".lastTime", timeMillis);

            addTagCount(target, existingReason, -1);
            addVoteLog(target, voter, null, value, "(removed)", timeMillis);

            result = VoteResult.REMOVED;
        } else {
            if (existing == 1 && value == 0) { likes--; dislikes++; }
            if (existing == 0 && value == 1) { dislikes--; likes++; }

            yaml.set(votePath + ".value", value);
            yaml.set(votePath + ".reason", reason);
            yaml.set(votePath + ".lastTime", timeMillis);

            addTagCount(target, existingReason, -1);
            addTagCount(target, reason, +1);
            addVoteLog(target, voter, null, value, reason, timeMillis);

            result = VoteResult.CHANGED;
        }

        likes = Math.max(0, likes);
        dislikes = Math.max(0, dislikes);

        String p = "players." + target;
        yaml.set(p + ".name", targetName);
        yaml.set(p + ".likes", likes);
        yaml.set(p + ".dislikes", dislikes);
        yaml.set(p + ".score", NumberUtil.computeScore(plugin, likes, dislikes));

        YamlUtil.save(file, yaml);
        return result;
    }

    @Override
    public int countVotesByVoterSince(UUID voter, long sinceMillis) {
        if (!yaml.contains("votes." + voter)) return 0;
        int cnt = 0;

        ConfigurationSection sec = yaml.getConfigurationSection("votes." + voter);
        if (sec == null) return 0;

        for (String target : sec.getKeys(false)) {
            String base = "votes." + voter + "." + target;
            long t = yaml.getLong(base + ".lastTime", 0L);
            Object v = yaml.get(base + ".value");
            if (v != null && t >= sinceMillis) cnt++;
        }

        return cnt;
    }

    @Override
    public void markSeen(UUID uuid, String name, String ipHash) {
        String path = "players." + uuid.toString();
        if (!yaml.contains(path)) {
            getOrCreate(uuid, name);
        }

        yaml.set(path + ".seen", true);
        if (name != null && !name.isEmpty()) yaml.set(path + ".name", name);
        if (ipHash != null) yaml.set(path + ".ipHash", ipHash);

        YamlUtil.save(file, yaml);
    }

    @Override
    public int getRank(UUID uuid) {
        List<PlayerRep> all = getTop(TopCategory.SCORE, Integer.MAX_VALUE, 0);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getUuid().equals(uuid)) return i + 1;
        }
        return -1;
    }

    @Override
    public Map<String, Integer> getTopTags(UUID target, int limit) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (limit <= 0) return out;

        String base = "tags." + target.toString();
        ConfigurationSection sec = yaml.getConfigurationSection(base);
        if (sec == null) return out;

        List<Map.Entry<String, Object>> entries = new ArrayList<>(sec.getValues(false).entrySet());
        entries.sort((a, b) -> Integer.compare(
                parseInt(b.getValue(), 0),
                parseInt(a.getValue(), 0)
        ));

        for (int i = 0; i < entries.size() && out.size() < limit; i++) {
            String tag = entries.get(i).getKey();
            int cnt = parseInt(entries.get(i).getValue(), 0);
            if (cnt > 0) out.put(tag, cnt);
        }

        return out;
    }

    @Override
    public List<VoteLogEntry> getRecentVotes(UUID target, int limit, boolean includeVoterName) {
        List<VoteLogEntry> list = new ArrayList<>();
        if (limit <= 0) return list;

        String base = "vote_log." + target.toString();
        ConfigurationSection sec = yaml.getConfigurationSection(base);
        if (sec == null) return list;

        List<String> keys = new ArrayList<>(sec.getKeys(false));
        keys.sort((a, b) -> Long.compare(
                yaml.getLong(base + "." + b + ".time", 0L),
                yaml.getLong(base + "." + a + ".time", 0L)
        ));

        for (String k : keys) {
            if (list.size() >= limit) break;

            String p = base + "." + k;
            int value = yaml.getInt(p + ".value", 1);
            String reason = yaml.getString(p + ".reason", null);
            long time = yaml.getLong(p + ".time", 0L);

            String voterName = null;
            if (includeVoterName) {
                voterName = yaml.getString(p + ".voterName", null);
                if (voterName == null) {
                    String voterUuid = yaml.getString(p + ".voter", null);
                    if (voterUuid != null) {
                        voterName = yaml.getString("players." + voterUuid + ".name", null);
                    }
                }
            }

            list.add(new VoteLogEntry(time, value, reason, voterName));
        }

        return list;
    }

    private void addTagCount(UUID target, String tag, int delta) {
        if (tag == null || tag.trim().isEmpty()) return;

        String base = "tags." + target.toString() + "." + tag;
        int cur = yaml.getInt(base, 0);
        int next = Math.max(0, cur + delta);
        yaml.set(base, next);
    }

    @Override
    public String getIpHash(UUID uuid) {
        return yaml.getString("players." + uuid.toString() + ".ipHash", null);
    }

    private void addVoteLog(UUID target, UUID voter, String voterName, int value, String reason, long timeMillis) {
        String base = "vote_log." + target.toString();
        String id = String.valueOf(System.currentTimeMillis()) + "-" + String.valueOf(new Random().nextInt(9999));

        yaml.set(base + "." + id + ".time", timeMillis);
        yaml.set(base + "." + id + ".value", value);
        yaml.set(base + "." + id + ".reason", reason);
        yaml.set(base + "." + id + ".voter", voter != null ? voter.toString() : null);
        yaml.set(base + "." + id + ".voterName", voterName);

        int keep = plugin.getConfig().getInt("profile.history.limit", 10);
        if (keep < 1) keep = 1;
        trimVoteLog(target, keep * 3);
    }

    private void trimVoteLog(UUID target, int keep) {
        String base = "vote_log." + target.toString();
        ConfigurationSection sec = yaml.getConfigurationSection(base);
        if (sec == null) return;

        List<String> keys = new ArrayList<>(sec.getKeys(false));
        keys.sort((a, b) -> Long.compare(
                yaml.getLong(base + "." + b + ".time", 0L),
                yaml.getLong(base + "." + a + ".time", 0L)
        ));

        for (int i = keep; i < keys.size(); i++) {
            yaml.set(base + "." + keys.get(i), null);
        }
    }

    private int parseInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }
}