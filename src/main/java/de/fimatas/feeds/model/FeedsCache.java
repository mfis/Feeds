package de.fimatas.feeds.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class FeedsCache {

    private static FeedsCache instance;

    private FeedsCache() {
        super();
    }

    public static synchronized FeedsCache getInstance() {
        if (instance == null) {
            instance = new FeedsCache();
        }
        return instance;
    }

    private final List<FeedsCacheGroup> cache = new LinkedList<>();

    public void updateGroupFeeds(FeedsCacheGroup group, Map<String, FeedsCache.FeedCacheEntry> newGroupFeeds) {
        lookupGroup(group.groupId).setGroupFeeds(newGroupFeeds);
    }

    public FeedsCacheGroup lookupGroup(String groupId) {
        return cache.stream().filter(g -> g.groupId.equals(groupId)).findFirst().orElse(null);
    }

    public FeedsCacheGroup defineGroup(String groupId) {
        var existingGroup = lookupGroup(groupId);
        if(existingGroup!=null){
            return existingGroup;
        }
        var newGroup = new FeedsCacheGroup();
        newGroup.groupId = groupId;
        cache.add(newGroup);
        return newGroup;
    }

    public FeedCacheEntry lookupFeed(String feedId) {
        for(FeedsCacheGroup group : cache) {
            if(group.groupFeeds.containsKey(feedId)) {
                return group.groupFeeds.get(feedId);
            }
        }
        return null;
    }

    @Data
    public static class FeedsCacheGroup {
        private String groupId;
        private LocalDateTime lastRefreshMethodCall = null;
        private Map<String, FeedCacheEntry> groupFeeds = new HashMap<>();
    }

    @Data
    public static class FeedCacheEntry {

        private String key;
        private String content;
        private int refreshErrorCounter;
        private LocalDateTime lastRefresh;
        private String headerLastModified;
        private String headerContentType;
        private TtlInfo ttl;

        public void increaseRefreshErrorCounter(){
            refreshErrorCounter++;
        }

        public boolean hasActualContent(){
            return refreshErrorCounter < 10 && lastRefresh.isAfter(LocalDateTime.now().minusDays(1)) && content != null;
        }
    }
}
