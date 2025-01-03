package de.fimatas.feeds.components;

import com.rometools.rome.feed.WireFeed;
import com.rometools.rome.feed.rss.Channel;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.WireFeedInput;
import de.fimatas.feeds.model.*;
import de.fimatas.feeds.util.FeedsUtil;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.annotation.PostConstruct;
import lombok.extern.apachecommons.CommonsLog;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.StringReader;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.*;

import static de.fimatas.feeds.model.FeedsLogMessages.*;

@CommonsLog
public class FeedsDownloadService {

    public FeedsDownloadService(
            FeedsConfigService feedsConfigService, FeedsProcessingService feedsProcessingService, FeedsHttpClient feedsHttpClient, FeedsTimer feedsTimer) {
        this.feedsConfigService = feedsConfigService;
        this.feedsProcessingService = feedsProcessingService;
        this.feedsHttpClient = feedsHttpClient;
        this.feedsTimer = feedsTimer;
    }

    private final FeedsConfigService feedsConfigService;
    private final FeedsProcessingService feedsProcessingService;
    private final FeedsHttpClient feedsHttpClient;
    private final FeedsTimer feedsTimer;

    private final static String schedulerDelayString = "PT5M";
    protected final Duration minimumSchedulerRunDuration = Duration.parse(schedulerDelayString);

    protected LocalDateTime lastSchedulerRun = null;
    protected LocalDateTime startupTime = null;

    private final LocalTime dailyStartTime = LocalTime.of(5, 20);
    private final LocalTime dailyEndTime = LocalTime.of(22, 30);

    protected FeedsCircuitBreaker feedsDownloadCircuitBreaker = new FeedsDownloadCircuitBreaker();

    @PostConstruct
    protected void init() {
        lastSchedulerRun = feedsTimer.localDateTimeNow().minus(minimumSchedulerRunDuration).minusSeconds(1);
        startupTime = feedsTimer.localDateTimeNow();
        if(FeedsCache.getInstance().isNotValid()){
            throw new IllegalStateException("FeedsCache is not valid");
        }
    }

    @Scheduled(initialDelay = 1000, fixedDelayString = schedulerDelayString)
    public void refreshScheduler() {
        var isUpdated = false;
        try {
            log.debug("call refreshScheduler");
            if (skip()) return;

            lastSchedulerRun = feedsTimer.localDateTimeNow();
            for(var group : feedsConfigService.getFeedsGroups()){
                if(refresh(group)){
                    isUpdated = true;
                }
            }
            if (isUpdated) {
                log.info("writeToCacheFile");
                FeedsCache.getInstance().writeToCacheFile();
            }
        } catch (Exception e) {
            FeedsCache.setExceptionTimestampAndWriteToFile();
            log.warn("refreshScheduler caught exception", e);
        }
    }

    private boolean skip() {

        if(startupTime == null){
            log.warn("service is not initialized");
            return true;
        }

        if(startupTime.plusMinutes(feedsConfigService.getStartupDelayMinutes()).isAfter(feedsTimer.localDateTimeNow())){
            log.info(STARTUP_DELAY);
            return true;
        }

        if(FeedsCache.getInstance().isNotValid()){
            log.warn(CACHE_IS_NOT_VALID);
            return true;
        }

        var now = feedsTimer.localTimeNow();
        if (now.isBefore(dailyStartTime)) {
            log.debug(REFRESH_SCHEDULER_DAILY_START_TIME_NOT_REACHED);
            return true;
        }
        if (now.isAfter(dailyEndTime)) {
            log.debug(REFRESH_SCHEDULER_DAILY_END_TIME_REACHED);
            return true;
        }
        if (lastSchedulerRun.plus(minimumSchedulerRunDuration).isAfter(feedsTimer.localDateTimeNow())) {
            log.warn(REFRESH_SCHEDULER_CALLED_TOO_FREQUENTLY);
            return true;
        }
        if (FeedsCache.getInstance().getExceptionTimestamp() != null && FeedsCache.getInstance().getExceptionTimestamp()
                .plus(getMaxRequestDelay()).isAfter(feedsTimer.localDateTimeNow())) {
            log.warn(REFRESH_SCHEDULER_WITH_EXCEPTION_CALLED_TOO_FREQUENTLY);
            return true;
        }
        return false;
    }

    private boolean refresh(FeedsConfig.FeedsGroup groupConfig) {

        var groupCache = FeedsCache.getInstance().lookupGroup(groupConfig.getGroupId());
        if (groupCache == null) {
            groupCache = FeedsCache.getInstance().defineGroup(groupConfig.getGroupId());
        }

        // check interval against cache
        if(!groupCache.getGroupFeeds().isEmpty()){
            var delayMinutes = getDelayMinutes(groupConfig);
            var maxLastRefresh = groupCache.getGroupFeeds().values().stream().map(FeedsCache.FeedCacheEntry::getLastRefresh).max(LocalDateTime::compareTo).orElseThrow();
            var maxDurationSinceLastRefresh = Duration.between(maxLastRefresh, feedsTimer.localDateTimeNow());
            if(maxDurationSinceLastRefresh.toMillis() < Duration.ofMinutes(delayMinutes).toMillis()){
                log.debug("group '" + groupConfig.getGroupId() + "' " + SKIPPING_REFRESH_CACHE + ": " + maxDurationSinceLastRefresh.toMinutes() + " / " + delayMinutes);
                return false;
            }
        }

        // check interval against method call
        if(groupCache.getLastRefreshMethodCall() != null &&
                Duration.between(groupCache.getLastRefreshMethodCall(), feedsTimer.localDateTimeNow()).toMinutes() < groupConfig.getGroupDefaultDurationMinutes()){
            log.debug("group '" + groupConfig.getGroupId() + "' " + SKIPPING_REFRESH_METHOD_CALL + ": " + groupCache.getLastRefreshMethodCall());
            return false;
        }

        // finally refresh
        groupCache.setLastRefreshMethodCall(feedsTimer.localDateTimeNow());

        Map<String, FeedsCache.FeedCacheEntry> refreshedCache = new HashMap<>();
        for(FeedsConfig.FeedConfig feedConfig : groupConfig.getGroupFeeds()){
            var decoratedRunnable = CircuitBreaker.decorateRunnable(feedsDownloadCircuitBreaker.getCircuitBreaker(feedConfig),
                   () -> refreshFeed(groupConfig, feedConfig, refreshedCache));
            try {
                decoratedRunnable.run();
            } catch (Exception e) {
                fallback(groupConfig, feedConfig, e, refreshedCache);
            }
        }

        FeedsCache.getInstance().updateGroupFeeds(groupCache, refreshedCache);

        log.info("group '" + groupConfig.getGroupId() + "' " + NEW_OVERALL_DELAY + ": " + getDelayMinutes(groupConfig) + " minutes (default: " +
                groupConfig.getGroupDefaultDurationMinutes() + ") - next refresh: " +
                (feedsTimer.localTimeNow().plusMinutes(getDelayMinutes(groupConfig))).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_TIME));

        return true;
    }

    private void refreshFeed(FeedsConfig.FeedsGroup groupConfig, FeedsConfig.FeedConfig feedConfig, Map<String, FeedsCache.FeedCacheEntry> refreshedCache) {

        var response = feedsHttpClient.getFeeds(feedConfig.getUrl());
        String processedFeed = feedsProcessingService.processFeed(response, feedConfig);
        handleRefreshSuccess(groupConfig, feedConfig, processedFeed, response, refreshedCache);
    }

    private void fallback(FeedsConfig.FeedsGroup groupConfig,  FeedsConfig.FeedConfig feedConfig, Exception e, Map<String, FeedsCache.FeedCacheEntry> refreshedCache) {
        var msg = "-> refreshFeed FALLBACK: " + feedConfig.getName() + ": " + e.getMessage();
        if(feedsConfigService.isLogStackTrace()){
            log.warn(msg, e);
        }else{
            log.warn(msg);
        }
        handleRefreshError(groupConfig, feedConfig, refreshedCache);
    }

    private void handleRefreshSuccess(FeedsConfig.FeedsGroup groupConfig, FeedsConfig.FeedConfig feedConfig, String feed, FeedsHttpClientResponse response, Map<String, FeedsCache.FeedCacheEntry> refreshedCache){
        var ttl = newEmptyFeedCacheEntry(groupConfig, feedConfig, feed, response, refreshedCache);
        log.info("-> refreshFeed OK: " + feedConfig.getName() + " - TTL: " + (ttl.getTtl().toMinutes() + " min (" + ttl.getSource() + ")"));
    }

    private void handleRefreshError(FeedsConfig.FeedsGroup groupConfig, FeedsConfig.FeedConfig feedConfig, Map<String, FeedsCache.FeedCacheEntry> refreshedCache){
        var groupCache = FeedsCache.getInstance().lookupGroup(groupConfig.getGroupId());
        if(groupCache.getGroupFeeds().containsKey(feedConfig.getKey())){
           refreshedCache.put(feedConfig.getKey(), groupCache.getGroupFeeds().get(feedConfig.getKey()));
        }else{
            newEmptyFeedCacheEntry(groupConfig, feedConfig, null, new FeedsHttpClientResponse(null, -1, null), refreshedCache);
        }
        refreshedCache.get(feedConfig.getKey()).increaseRefreshErrorCounter();
    }

    private TtlInfo newEmptyFeedCacheEntry(FeedsConfig.FeedsGroup groupConfig, FeedsConfig.FeedConfig feedConfig, String feed, FeedsHttpClientResponse response, Map<String, FeedsCache.FeedCacheEntry> refreshedCache) {
        var ttl = getTtlMinutes(response, feedConfig.getKey());
        FeedsCache.FeedCacheEntry feedCacheEntry = new FeedsCache.FeedCacheEntry();
        feedCacheEntry.setKey(feedConfig.getKey());
        feedCacheEntry.setLastRefresh(feedsTimer.localDateTimeNow());
        feedCacheEntry.setRefreshErrorCounter(0);
        feedCacheEntry.setContent(feed);
        feedCacheEntry.setHeaderLastModified(getHeaderValue(response, HttpHeaders.LAST_MODIFIED));
        feedCacheEntry.setHeaderContentType(getHeaderValue(response, HttpHeaders.CONTENT_TYPE));
        feedCacheEntry.setTtl(ttl.orElse(defaultTtl(groupConfig)));
        refreshedCache.put(feedConfig.getKey(), feedCacheEntry);
        return feedCacheEntry.getTtl();
    }

    private Optional<TtlInfo> getTtlMinutes(FeedsHttpClientResponse response, String key) {
        var optionals = List.of(
                getTtlMinutesFromHeaderMaxAge(response),
                getTtlMinutesFromHeaderRetryAfter(response, key),
                getTtlMinutesFromFeed(response));
        return optionals.stream().filter(Optional::isPresent).map(Optional::get).max(Comparator.comparing(TtlInfo::getTtl));
    }

    private Optional<TtlInfo> getTtlMinutesFromHeaderMaxAge(FeedsHttpClientResponse response) {
        String cacheControl = getHeaderValue(response, HttpHeaders.CACHE_CONTROL);
            if (cacheControl != null && cacheControl.contains("max-age=")) {
                try {
                    long maxAgeSeconds = Long.parseLong(cacheControl.split("max-age=")[1].split(",")[0]);
                    return Optional.of(new TtlInfo(Duration.ofSeconds(maxAgeSeconds), "MaxAge"));
                } catch (NumberFormatException ignored) {
                }
            }
        return Optional.empty();
    }

    private Optional<TtlInfo> getTtlMinutesFromHeaderRetryAfter(FeedsHttpClientResponse response, String key) {
        String retryAfter = getHeaderValue(response, HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) {
                if (NumberUtils.isParsable(retryAfter)) {
                    long retrySeconds = Long.parseLong(retryAfter);
                    return Optional.of(new TtlInfo(Duration.ofSeconds(retrySeconds), "RetryAfterSec"));
                } else {
                    try {
                        TemporalAccessor retryAfterTime = DateTimeFormatter.RFC_1123_DATE_TIME.parse(retryAfter);
                        LocalDateTime localDateTime = LocalDateTime.from(retryAfterTime);
                        Duration duration = Duration.between(feedsTimer.localDateTimeNow(), localDateTime);
                        return duration.isNegative() ? Optional.empty() : Optional.of(new TtlInfo(duration, "RetryAfterTS"));
                    } catch (Exception ignored) {
                        log.warn("Could not parse retry-after: " + retryAfter + " for feed:" + key);
                    }
                }
            }
        return Optional.empty();
    }

    private Optional<TtlInfo> getTtlMinutesFromFeed(FeedsHttpClientResponse response) {
        if(response.getBody() == null || response.getBody().isEmpty()){
            return Optional.empty();
        }
        try {
            WireFeed wireFeed = new WireFeedInput().build(new StringReader(Objects.requireNonNull(response.getBody())));
            if (wireFeed instanceof Channel channel && channel.getTtl() > 0) {
                return Optional.of(new TtlInfo(Duration.ofMinutes(channel.getTtl()), "ttl"));
            }
            if(wireFeed.getForeignMarkup() != null) {
                var updatePeriod = FeedsUtil.getForeignMarkupValue(wireFeed, "updatePeriod");
                var updateFrequency =FeedsUtil.getForeignMarkupValue(wireFeed, "updateFrequency");
                var updateBase = FeedsUtil.getForeignMarkupValue(wireFeed, "updateBase");
                if(updatePeriod != null && updateFrequency != null) {
                    var updateDuration = Duration.ofMinutes(getPeriodMinutes(updatePeriod) / Long.parseLong(updateFrequency));
                    if(updateBase != null) {
                        DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
                        ZonedDateTime dateTime = ZonedDateTime.parse(updateBase, formatter);
                        if(dateTime.isAfter(feedsTimer.zonedDateTimeNow())) {
                            var baseDuration = Duration.between(feedsTimer.zonedDateTimeNow(), dateTime);
                            return Optional.of(new TtlInfo(baseDuration.plus(updateDuration), "updatePeriod+base"));
                        }
                    }
                    return Optional.of(new TtlInfo(updateDuration, "updatePeriod"));
                }

            }
        } catch (FeedException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private long getPeriodMinutes(String updatePeriod) {
        return switch (updatePeriod) {
            case "hourly" ->  60;
            case "daily" ->  60 * 24;
            case "weekly" ->  60 * 24 * 7;
            case "monthly" ->  60 * 24 * 30;
            case "yearly" ->  60 * 24 * 365;
            default -> throw new IllegalStateException("Unexpected period: " + updatePeriod);
        };
    }

    private long getDelayMinutes(FeedsConfig.FeedsGroup groupConfig) {
        var groupCache = FeedsCache.getInstance().lookupGroup(groupConfig.getGroupId());
        var ttlList = groupCache.getGroupFeeds().values().stream().map(FeedsCache.FeedCacheEntry::getTtl).toList();
        var mutableTtlList = new ArrayList<>(ttlList);
        mutableTtlList.add(defaultTtl(groupConfig));
        return mutableTtlList.stream().max(Comparator.comparing(TtlInfo::getTtl)).map(t -> t.getTtl().toMinutes()).orElseThrow();
    }

    private static TtlInfo defaultTtl(FeedsConfig.FeedsGroup groupConfig) {
        return new TtlInfo(Duration.ofMinutes(groupConfig.getGroupDefaultDurationMinutes()), "default");
    }

    private static String getHeaderValue(FeedsHttpClientResponse response, String headerName) {
        if(response.getHeaders() != null) {
            for (Map.Entry<String, String> header : response.getHeaders().entrySet()) {
                if (header.getKey().equalsIgnoreCase(headerName)) {
                    return header.getValue();
                }
            }
        }
        return null;
    }

    private Duration getMaxRequestDelay(){
        try {
            return Duration.ofMinutes(feedsConfigService.getFeedsGroups().stream().map(FeedsConfig.FeedsGroup::getGroupDefaultDurationMinutes)
                    .toList().stream().max(Comparator.naturalOrder()).orElseThrow());
        } catch (Exception e) {
            log.warn("could not get max response delay", e);
            return Duration.ofMinutes(90);
        }
    }

}
