package de.fimatas.feeds.components;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.fimatas.feeds.model.FeedsConfig;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.apachecommons.CommonsLog;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedList;
import java.util.List;

@CommonsLog
public class FeedsConfigService {

    @Value("${feeds.useTestConfig:true}")
    protected boolean useTestConfig;

    @Getter
    @Value("${feeds.logStackTrace}")
    protected boolean logStackTrace;

    @Getter
    @Value("${feeds.startupDelayMinutes}")
    protected long startupDelayMinutes;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FeedsConfig feedsConfig;

    private static final long listenerInterval = 15_000L;

    private long lastKnownFileDateModified = -1;

    @PostConstruct
    private void init() {
        log.info("useTestConfig=" + useTestConfig);
    }

    @Scheduled(initialDelay = 1, fixedDelay = listenerInterval)
    private void startConfigFileListener() {
        if(lastKnownFileDateModified != lookupConfigJsonLastModified()){
            lastKnownFileDateModified = lookupConfigJsonLastModified();
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

    public String getExternalURL(){
        return feedsConfig.getExternalURL();
    }

    public void overwriteStartupDelayMinutes(long startupDelayMinutes){
        assert System.getProperty("active.profile", "").equals("test");
        this.startupDelayMinutes = startupDelayMinutes;
    }

    private void resolveList(String in, List<String> allStrings) {
        feedsConfig.getLists().forEach(l -> l.keySet().forEach(k -> {
            if(k.equalsIgnoreCase(in)){
                allStrings.addAll(l.get(k));
            }
        }));
    }

    @SneakyThrows
    private synchronized void readFeedsConfig() {
        var localFeedsConfig = objectMapper.readValue(lookupConfigJsonDocument(), FeedsConfig.class);
        localFeedsConfig.getGroups().forEach(g -> {
            var groupFeeds = g.getGroupFeeds().stream().filter(FeedsConfig.FeedConfig::isActive).toList();
            g.setGroupFeeds(groupFeeds);
        });
        feedsConfig = localFeedsConfig;
        log.info("startupDelayMinutes=" + startupDelayMinutes);
    }

    private String lookupConfigJsonDocument() {
        try {
            if (useTestConfig) {
                try (var inputStream = FeedsConfigService.class.getClassLoader().getResourceAsStream("testFeeds.json")) {
                    assert inputStream != null;
                    return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                return FileUtils.readFileToString(lookupConfigJsonFile(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new RuntimeException("configJsonFile not readable!", e);
        }
    }

    private long lookupConfigJsonLastModified() {
        if(useTestConfig){
            return 0;
        }else{
            return lookupConfigJsonFile().lastModified();
        }
    }

    private File lookupConfigJsonFile(){
        return Path.of(System.getProperty("user.home") + "/Documents/config/feeds/feeds.json").toFile();
    }
}
