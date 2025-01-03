package de.fimatas.feeds.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.apachecommons.CommonsLog;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@CommonsLog
public class FeedsCache {

    private FeedsCache() {
        super();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        readFromCacheFile();
    }

    public static synchronized FeedsCache getInstance() {
        if (instance == null) {
            try {
                instance = new FeedsCache();
            }catch (Throwable t){
                instance = null;
                throw t;
            }
        }
        return instance;
    }

    private static FeedsCache instance;
    private FeedsCacheRoot cache = null;
    private final ObjectMapper objectMapper;
    private boolean readError = false;
    private boolean writeError = false;
    private boolean cacheError = false;


    @SneakyThrows
    public boolean isNotValid() {
        if(readError || writeError || cacheError) {
            return true;
        }
        var file = lookupCacheFile();
        if(!file.exists()){
            FileUtils.touch(file);
        }
        if(file.exists()){
            if(!file.isFile() || !file.canRead() ){
                log.error("Cache file is not file/readable");
                readError = true;
                return true;
            }
            if(!file.canWrite()){
                log.error("Cache file is not writable");
                writeError = true;
                return true;
            }
        }
        if(cache == null) {
            log.error("Cache is null");
            cacheError = true;
            return true;
        }
        return false;
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
            cacheError = true;
            throw new RuntimeException("Cache object already existed!");
        }
        if(file.exists() && file.length() > 0){
            try {
                cache = objectMapper.readValue(file, FeedsCacheRoot.class);
            } catch (Throwable t) {
                cache = null;
                readError = true;
                throw new RuntimeException("Cache could not be read", t);
            }
        }else {
            cache = new FeedsCacheRoot();
            writeToCacheFile();
        }
    }

    public synchronized void writeToCacheFile() {
        if(cache == null){
            cacheError = true;
            throw new RuntimeException("Cache object is null!");
        }
        var file = lookupCacheFile();
        try {
            var json = objectMapper.writeValueAsString(cache);
            Files.writeString(file.toPath(), json);
        } catch (Throwable t) {
            writeError = true;
            throw new RuntimeException("Cache could not be written", t);
        }
    }

    public static void setExceptionTimestampAndWriteToFile() {
        if(instance != null && instance.cache != null) {
            instance.cache.setLastException(LocalDateTime.now());
            try {
                instance.writeToCacheFile();
            } catch (Throwable t) {
                log.debug("could not save setExceptionTimestampAndWriteToFile");
            }
        }
    }

    public LocalDateTime getExceptionTimestamp() {
        return cache.getLastException();
    }

    public static File lookupCacheFile(){
        var profile = System.getProperty("active.profile", "");
        return Path.of(System.getProperty("user.home") + "/Documents/config/feeds/cache" + profile +".json").toFile();
    }

    public static void destroyCache() {
        assert System.getProperty("active.profile", "").equals("test");
        FileUtils.deleteQuietly(lookupCacheFile());
        instance = null;
    }

    public static void invalidateCache() {
        assert System.getProperty("active.profile", "").equals("test");
        if(instance != null){
            instance.cache = null;
        }
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
