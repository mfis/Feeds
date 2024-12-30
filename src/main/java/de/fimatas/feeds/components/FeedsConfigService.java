package de.fimatas.feeds.components;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.fimatas.feeds.model.FeedsConfig;
import lombok.SneakyThrows;
import lombok.extern.apachecommons.CommonsLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.*;
import java.util.LinkedList;
import java.util.List;

@CommonsLog
public class FeedsConfigService {

    @Value("${config}")
    private String configFile;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Path path;

    private FeedsConfig feedsConfig;

    private static final long listenerInterval = 15_000L;

    private long lastKnownFileDateModified = 0;

    @Scheduled(initialDelay = 1, fixedDelay = listenerInterval)
    private void startConfigFileListener() {
        if(lastKnownFileDateModified != lookupPath().toFile().lastModified()){
            lastKnownFileDateModified = lookupPath().toFile().lastModified();
            log.info("refreshing config");
            readFeedsConfig();
        }
    }

    public List<FeedsConfig.FeedsGroup> getFeedsGroups(){
        if(feedsConfig == null){
            readFeedsConfig();
        }
        return feedsConfig.getGroups();
    }

    public List<String> getIncludesForFeedConfig(FeedsConfig.FeedConfig feedConfig){
        final List<String> allStrings = new LinkedList<>();
        feedConfig.getIncludeRefs().forEach(in -> resolveList(in, allStrings));
        return allStrings;
    }

    public List<String> getExcludesForFeedConfig(FeedsConfig.FeedConfig feedConfig){
        final List<String> allStrings = new LinkedList<>();
        feedConfig.getExcludeRefs().forEach(in -> resolveList(in, allStrings));
        return allStrings;
    }

    public boolean isLogStackTrace(){
        return feedsConfig.isLogStackTrace();
    }

    private void resolveList(String in, List<String> allStrings) {
        feedsConfig.getLists().forEach(l -> l.keySet().forEach(k -> {
            if(k.equalsIgnoreCase(in)){
                allStrings.addAll(l.get(k));
            }
        }));
    }

    @SneakyThrows
    private void readFeedsConfig() {
        var localFeedsConfig = objectMapper.readValue(lookupPath().toFile(), FeedsConfig.class);
        localFeedsConfig.getGroups().forEach(g -> {
            var groupFeeds = g.getGroupFeeds().stream().filter(FeedsConfig.FeedConfig::isActive).toList();
            g.setGroupFeeds(groupFeeds);
        });
        feedsConfig = localFeedsConfig;
    }

    private Path lookupPath() {
        if(path == null){
            path = Path.of(System.getProperty("user.home") + "/Documents/config/feeds/" + configFile);
        }
        return path;
    }
    
}
