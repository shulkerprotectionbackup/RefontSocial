package ru.rizonchik.refontsocial.storage;

import ru.rizonchik.refontsocial.storage.model.PlayerRep;
import ru.rizonchik.refontsocial.storage.model.VoteLogEntry;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface Storage {

    final class VoteState {
        public final Long lastTime;
        public final Integer value;
        public final String reason;

        public VoteState(Long lastTime, Integer value, String reason) {
            this.lastTime = lastTime;
            this.value = value;
            this.reason = reason;
        }
    }

    enum VoteResult {
        CREATED,
        CHANGED,
        REMOVED
    }

    void init();

    void close();

    PlayerRep getOrCreate(UUID uuid, String name);

    String getLastKnownName(UUID uuid);

    List<PlayerRep> getTop(int limit, int offset);

    List<PlayerRep> getTop(TopCategory category, int limit, int offset);

    VoteState getVoteState(UUID voter, UUID target);

    VoteResult applyVote(UUID voter, UUID target, int value, long timeMillis, String targetName, String reason);

    int countVotesByVoterSince(UUID voter, long sinceMillis);

    void markSeen(UUID uuid, String name, String ipHash);

    int getRank(UUID uuid);

    Map<String, Integer> getTopTags(UUID target, int limit);

    List<VoteLogEntry> getRecentVotes(UUID target, int limit, boolean includeVoterName);

    String getIpHash(UUID uuid);
}