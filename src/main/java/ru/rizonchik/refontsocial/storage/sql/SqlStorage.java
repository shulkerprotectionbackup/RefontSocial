package ru.rizonchik.refontsocial.storage.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;
import ru.rizonchik.refontsocial.storage.Storage;
import ru.rizonchik.refontsocial.storage.TopCategory;
import ru.rizonchik.refontsocial.storage.model.PlayerRep;
import ru.rizonchik.refontsocial.storage.model.VoteLogEntry;
import ru.rizonchik.refontsocial.util.NumberUtil;

import java.sql.*;
import java.util.*;

public abstract class SqlStorage implements Storage {

    protected final JavaPlugin plugin;
    protected HikariDataSource ds;

    protected SqlStorage(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    protected abstract HikariConfig buildConfig();

    @Override
    public void init() {
        HikariConfig cfg = buildConfig();
        ds = new HikariDataSource(cfg);

        try (Connection c = ds.getConnection()) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS rs_players (" +
                        "uuid VARCHAR(36) PRIMARY KEY," +
                        "name VARCHAR(16) NULL," +
                        "likes INT NOT NULL DEFAULT 0," +
                        "dislikes INT NOT NULL DEFAULT 0," +
                        "score DOUBLE NOT NULL DEFAULT 5.0," +
                        "updated BIGINT NOT NULL DEFAULT 0" +
                        ")");

                st.executeUpdate("CREATE TABLE IF NOT EXISTS rs_votes (" +
                        "voter VARCHAR(36) NOT NULL," +
                        "target VARCHAR(36) NOT NULL," +
                        "value INT NULL," +
                        "reason VARCHAR(64) NULL," +
                        "last_time BIGINT NOT NULL," +
                        "PRIMARY KEY (voter, target)" +
                        ")");

                st.executeUpdate("CREATE TABLE IF NOT EXISTS rs_vote_log (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "target VARCHAR(36) NOT NULL," +
                        "voter VARCHAR(36) NULL," +
                        "voter_name VARCHAR(16) NULL," +
                        "value INT NOT NULL," +
                        "reason VARCHAR(64) NULL," +
                        "time BIGINT NOT NULL" +
                        ")");

                st.executeUpdate("CREATE TABLE IF NOT EXISTS rs_tags (" +
                        "target VARCHAR(36) NOT NULL," +
                        "tag VARCHAR(64) NOT NULL," +
                        "count INT NOT NULL DEFAULT 0," +
                        "PRIMARY KEY (target, tag)" +
                        ")");

                try {
                    st.executeUpdate("ALTER TABLE rs_players ADD COLUMN seen INT NOT NULL DEFAULT 0");
                } catch (SQLException ignored) {
                }
                try {
                    st.executeUpdate("ALTER TABLE rs_players ADD COLUMN ip_hash VARCHAR(64) NULL");
                } catch (SQLException ignored) {
                }

                try {
                    st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rs_players_score ON rs_players(score)");
                } catch (SQLException ignored) {
                }
                try {
                    st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rs_players_seen_score ON rs_players(seen, score)");
                } catch (SQLException ignored) {
                }
                try {
                    st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rs_votes_voter_time ON rs_votes(voter, last_time)");
                } catch (SQLException ignored) {
                }
                try {
                    st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rs_vote_log_target_time ON rs_vote_log(target, time)");
                } catch (SQLException ignored) {
                }
                try {
                    st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rs_tags_target_count ON rs_tags(target, count)");
                } catch (SQLException ignored) {
                }

                try {
                    st.executeUpdate("UPDATE rs_players SET seen=1 WHERE (likes+dislikes) > 0");
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to init SQL schema", e);
        }
    }

    @Override
    public String getIpHash(UUID uuid) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT ip_hash FROM rs_players WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString("ip_hash");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @Override
    public void close() {
        if (ds != null) {
            ds.close();
            ds = null;
        }
    }

    @Override
    public PlayerRep getOrCreate(UUID uuid, String name) {
        ensurePlayer(uuid, name);

        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid, name, likes, dislikes, score FROM rs_players WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int likes = rs.getInt("likes");
                        int dislikes = rs.getInt("dislikes");
                        double score = rs.getDouble("score");
                        int votes = likes + dislikes;
                        return new PlayerRep(uuid, rs.getString("name"), likes, dislikes, votes, score);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return new PlayerRep(uuid, name, 0, 0, 0, NumberUtil.defaultScore(plugin));
    }

    @Override
    public String getLastKnownName(UUID uuid) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM rs_players WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString("name");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @Override
    public List<PlayerRep> getTop(int limit, int offset) {
        return getTop(TopCategory.SCORE, limit, offset);
    }

    @Override
    public List<PlayerRep> getTop(TopCategory category, int limit, int offset) {
        List<PlayerRep> list = new ArrayList<>();
        if (limit <= 0) return list;

        String order;
        if (category == TopCategory.LIKES) order = "likes DESC, score DESC";
        else if (category == TopCategory.DISLIKES) order = "dislikes DESC, score ASC";
        else if (category == TopCategory.VOTES) order = "(likes+dislikes) DESC, score DESC";
        else order = "score DESC, (likes+dislikes) DESC";

        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid, name, likes, dislikes, score FROM rs_players " +
                            "WHERE seen=1 ORDER BY " + order + " LIMIT ? OFFSET ?")) {
                ps.setInt(1, limit);
                ps.setInt(2, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        int likes = rs.getInt("likes");
                        int dislikes = rs.getInt("dislikes");
                        double score = rs.getDouble("score");
                        int votes = likes + dislikes;
                        list.add(new PlayerRep(uuid, rs.getString("name"), likes, dislikes, votes, score));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return list;
    }

    @Override
    public VoteState getVoteState(UUID voter, UUID target) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT value, reason, last_time FROM rs_votes WHERE voter=? AND target=?")) {
                ps.setString(1, voter.toString());
                ps.setString(2, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Object v = rs.getObject("value");
                        Integer value = v == null ? null : rs.getInt("value");
                        String reason = rs.getString("reason");
                        long t = rs.getLong("last_time");
                        return new VoteState(t, value, reason);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @Override
    public VoteResult applyVote(UUID voter, UUID target, int value, long timeMillis, String targetName, String reason) {
        ensurePlayer(target, targetName);
        ensurePlayer(voter, null);

        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                Integer existing = null;
                String existingReason = null;

                try (PreparedStatement ps = c.prepareStatement("SELECT value, reason FROM rs_votes WHERE voter=? AND target=?")) {
                    ps.setString(1, voter.toString());
                    ps.setString(2, target.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            Object v = rs.getObject("value");
                            existing = v == null ? null : rs.getInt("value");
                            existingReason = rs.getString("reason");
                        }
                    }
                }

                VoteResult result;

                if (existing == null) {
                    upsertVote(c, voter, target, value, timeMillis, reason);
                    updateCounters(c, target, value == 1 ? 1 : 0, value == 0 ? 1 : 0);
                    addTagCount(c, target, reason, +1);
                    insertVoteLog(c, target, voter, null, value, reason, timeMillis);
                    result = VoteResult.CREATED;
                } else if (existing == value) {
                    clearVote(c, voter, target, timeMillis);
                    updateCounters(c, target, value == 1 ? -1 : 0, value == 0 ? -1 : 0);
                    addTagCount(c, target, existingReason, -1);
                    insertVoteLog(c, target, voter, null, value, "(removed)", timeMillis);
                    result = VoteResult.REMOVED;
                } else {
                    upsertVote(c, voter, target, value, timeMillis, reason);
                    if (existing == 1 && value == 0) updateCounters(c, target, -1, +1);
                    if (existing == 0 && value == 1) updateCounters(c, target, +1, -1);

                    addTagCount(c, target, existingReason, -1);
                    addTagCount(c, target, reason, +1);

                    insertVoteLog(c, target, voter, null, value, reason, timeMillis);
                    result = VoteResult.CHANGED;
                }

                recalcScore(c, target, timeMillis);
                c.commit();
                return result;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void upsertVote(Connection c, UUID voter, UUID target, int value, long timeMillis, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO rs_votes(voter, target, value, reason, last_time) VALUES (?,?,?,?,?)")) {
            ps.setString(1, voter.toString());
            ps.setString(2, target.toString());
            ps.setInt(3, value);
            ps.setString(4, reason);
            ps.setLong(5, timeMillis);
            ps.executeUpdate();
        } catch (SQLException ex) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE rs_votes SET value=?, reason=?, last_time=? WHERE voter=? AND target=?")) {
                ps.setInt(1, value);
                ps.setString(2, reason);
                ps.setLong(3, timeMillis);
                ps.setString(4, voter.toString());
                ps.setString(5, target.toString());
                ps.executeUpdate();
            }
        }
    }

    private void clearVote(Connection c, UUID voter, UUID target, long timeMillis) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE rs_votes SET value=NULL, reason=NULL, last_time=? WHERE voter=? AND target=?")) {
            ps.setLong(1, timeMillis);
            ps.setString(2, voter.toString());
            ps.setString(3, target.toString());
            ps.executeUpdate();
        }
    }

    private void insertVoteLog(Connection c, UUID target, UUID voter, String voterName, int value, String reason, long timeMillis) throws SQLException {
        String resolvedName = voterName;

        if (resolvedName == null && voter != null) {
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM rs_players WHERE uuid=?")) {
                ps.setString(1, voter.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) resolvedName = rs.getString("name");
                }
            }
        }

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO rs_vote_log(target, voter, voter_name, value, reason, time) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, target.toString());
            ps.setString(2, voter != null ? voter.toString() : null);
            ps.setString(3, resolvedName);
            ps.setInt(4, value);
            ps.setString(5, reason);
            ps.setLong(6, timeMillis);
            ps.executeUpdate();
        }
    }

    @Override
    public int countVotesByVoterSince(UUID voter, long sinceMillis) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) AS cnt FROM rs_votes WHERE voter=? AND last_time>=? AND value IS NOT NULL")) {
                ps.setString(1, voter.toString());
                ps.setLong(2, sinceMillis);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt("cnt");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    @Override
    public void markSeen(UUID uuid, String name, String ipHash) {
        ensurePlayer(uuid, name);
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE rs_players SET seen=1, name=COALESCE(?, name), ip_hash=COALESCE(?, ip_hash), updated=? WHERE uuid=?")) {
                ps.setString(1, name);
                ps.setString(2, ipHash);
                ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, uuid.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getRank(UUID uuid) {
        try (Connection c = ds.getConnection()) {
            double score;
            int votes;

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT score, (likes+dislikes) AS votes FROM rs_players WHERE uuid=? AND seen=1")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return -1;
                    score = rs.getDouble("score");
                    votes = rs.getInt("votes");
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) AS cnt FROM rs_players WHERE seen=1 AND (" +
                            "score > ? OR " +
                            "(score = ? AND (likes+dislikes) > ?) OR " +
                            "(score = ? AND (likes+dislikes) = ? AND uuid < ?)" +
                            ")")) {
                ps.setDouble(1, score);
                ps.setDouble(2, score);
                ps.setInt(3, votes);
                ps.setDouble(4, score);
                ps.setInt(5, votes);
                ps.setString(6, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return -1;
                    return rs.getInt("cnt") + 1;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Map<String, Integer> getTopTags(UUID target, int limit) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (limit <= 0) return out;

        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT tag, count FROM rs_tags WHERE target=? AND count>0 ORDER BY count DESC LIMIT ?")) {
                ps.setString(1, target.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString("tag"), rs.getInt("count"));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return out;
    }

    @Override
    public List<VoteLogEntry> getRecentVotes(UUID target, int limit, boolean includeVoterName) {
        List<VoteLogEntry> list = new ArrayList<>();
        if (limit <= 0) return list;

        try (Connection c = ds.getConnection()) {
            String sql = includeVoterName
                    ? "SELECT value, reason, time, voter_name FROM rs_vote_log WHERE target=? ORDER BY time DESC LIMIT ?"
                    : "SELECT value, reason, time, NULL AS voter_name FROM rs_vote_log WHERE target=? ORDER BY time DESC LIMIT ?";

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, target.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int v = rs.getInt("value");
                        String reason = rs.getString("reason");
                        long t = rs.getLong("time");
                        String voterName = rs.getString("voter_name");
                        list.add(new VoteLogEntry(t, v, reason, voterName));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return list;
    }

    protected void ensurePlayer(UUID uuid, String name) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO rs_players(uuid, name, likes, dislikes, score, updated, seen, ip_hash) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setInt(3, 0);
                ps.setInt(4, 0);
                ps.setDouble(5, NumberUtil.defaultScore(plugin));
                ps.setLong(6, System.currentTimeMillis());
                ps.setInt(7, 0);
                ps.setString(8, null);
                ps.executeUpdate();
            } catch (SQLException ex) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE rs_players SET name=COALESCE(?, name) WHERE uuid=?")) {
                    ps.setString(1, name);
                    ps.setString(2, uuid.toString());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    protected void updateCounters(Connection c, UUID target, int likeDelta, int dislikeDelta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE rs_players SET likes=likes+?, dislikes=dislikes+? WHERE uuid=?")) {
            ps.setInt(1, likeDelta);
            ps.setInt(2, dislikeDelta);
            ps.setString(3, target.toString());
            ps.executeUpdate();
        }
    }

    protected void recalcScore(Connection c, UUID target, long now) throws SQLException {
        int likes;
        int dislikes;

        try (PreparedStatement ps = c.prepareStatement("SELECT likes, dislikes FROM rs_players WHERE uuid=?")) {
            ps.setString(1, target.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                likes = rs.getInt("likes");
                dislikes = rs.getInt("dislikes");
            }
        }

        double score = NumberUtil.computeScore(plugin, likes, dislikes);

        try (PreparedStatement ps = c.prepareStatement("UPDATE rs_players SET score=?, updated=? WHERE uuid=?")) {
            ps.setDouble(1, score);
            ps.setLong(2, now);
            ps.setString(3, target.toString());
            ps.executeUpdate();
        }
    }

    protected void addTagCount(Connection c, UUID target, String tag, int delta) throws SQLException {
        if (tag == null || tag.trim().isEmpty()) return;

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO rs_tags(target, tag, count) VALUES (?,?,?)")) {
            ps.setString(1, target.toString());
            ps.setString(2, tag);
            ps.setInt(3, Math.max(0, delta));
            ps.executeUpdate();
        } catch (SQLException ex) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE rs_tags SET count=MAX(count+?, 0) WHERE target=? AND tag=?")) {
                ps.setInt(1, delta);
                ps.setString(2, target.toString());
                ps.setString(3, tag);
                ps.executeUpdate();
            } catch (SQLException ex2) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT count FROM rs_tags WHERE target=? AND tag=?")) {
                    ps.setString(1, target.toString());
                    ps.setString(2, tag);
                    try (ResultSet rs = ps.executeQuery()) {
                        int cur = 0;
                        if (rs.next()) cur = rs.getInt("count");
                        int next = Math.max(0, cur + delta);
                        try (PreparedStatement ups = c.prepareStatement(
                                "UPDATE rs_tags SET count=? WHERE target=? AND tag=?")) {
                            ups.setInt(1, next);
                            ups.setString(2, target.toString());
                            ups.setString(3, tag);
                            ups.executeUpdate();
                        }
                    }
                }
            }
        }
    }
}