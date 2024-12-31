package de.fimatas.feeds.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class FeedsCache {

    private static FeedsCache instance;

    private FeedsCache() {
        super();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        readFromCacheFile();
    }

    public static synchronized FeedsCache getInstance() {
        if (instance == null) {
            instance = new FeedsCache();
        }
        return instance;
    }

    private FeedsCacheRoot cache = null;

    private final ObjectMapper objectMapper;

    @SneakyThrows
    public void checkCacheFile(){
        var file = lookupCacheFile();
        if(!file.exists()){
            FileUtils.touch(file);
        }
        if(file.exists()){
            if(!file.isFile() || !file.canRead() || !file.canWrite()){
                throw new RuntimeException("Cache file is not file/readable/writable");
            }
        }
    }

    public boolean isNotValid() {
        return cache == null;
    }

    public void updateGroupFeeds(FeedsCacheGroup group, Map<String, FeedsCache.FeedCacheEntry> newGroupFeeds) {
        lookupGroup(group.groupId).setGroupFeeds(newGroupFeeds);
    }

    public FeedsCacheGroup lookupGroup(String groupId) {
        return cache.getCacheGroups().stream().filter(g -> g.groupId.equals(groupId)).findFirst().orElse(null);
    }

    public FeedsCacheGroup defineGroup(String groupId) {
        var existingGroup = lookupGroup(groupId);
        if(existingGroup!=null){
            return existingGroup;
        }
        var newGroup = new FeedsCacheGroup();
        newGroup.groupId = groupId;
        cache.getCacheGroups().add(newGroup);
        return newGroup;
    }

    public FeedCacheEntry lookupFeed(String feedId) {
        for(FeedsCacheGroup group : cache.getCacheGroups()) {
            if(group.groupFeeds.containsKey(feedId)) {
                return group.groupFeeds.get(feedId);
            }
        }
        return null;
    }

    private synchronized void readFromCacheFile() {
        var file = lookupCacheFile();
        if(cache != null){
            cache = null;
            throw new RuntimeException("Cache object already existed!");
        }
        if(file.exists() && file.length() > 0){
            try {
                cache = objectMapper.readValue(file, FeedsCacheRoot.class);
            } catch (IOException e) {
                cache = null;
                throw new RuntimeException("Cache could not be read", e);
            }
        }else {
            cache = new FeedsCacheRoot();
            writeToCacheFile();
        }
    }

    public synchronized void writeToCacheFile() {
        if(cache == null){
            throw new RuntimeException("Cache object is null!");
        }
        var file = lookupCacheFile();
        try {
            objectMapper.writeValue(file, cache);
        } catch (IOException e) {
            cache = null;
            throw new RuntimeException("Cache could not be written", e);
        }
    }

    public void setExceptionTimestampAndWriteToFile() {
        cache.setLastException(LocalDateTime.now());
        writeToCacheFile();
    }

    public LocalDateTime getExceptionTimestamp() {
        return cache.getLastException();
    }

    private File lookupCacheFile(){
        return Path.of(System.getProperty("user.home") + "/Documents/config/feeds/cache.json").toFile();
    }

    @Data
    public static class FeedsCacheRoot {
        private LocalDateTime lastException = null;
        private List<FeedsCacheGroup> cacheGroups = new LinkedList<>();
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
