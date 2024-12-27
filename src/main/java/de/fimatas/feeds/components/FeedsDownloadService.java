package de.fimatas.feeds.components;

import com.rometools.rome.feed.WireFeed;
import com.rometools.rome.feed.rss.Channel;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.WireFeedInput;
import de.fimatas.feeds.model.FeedsCache;
import de.fimatas.feeds.model.FeedsConfig;
import de.fimatas.feeds.model.TtlInfo;
import de.fimatas.feeds.util.FeedsUtil;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.apachecommons.CommonsLog;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.*;

@CommonsLog
public class FeedsDownloadService {

    public FeedsDownloadService(FeedsConfigService feedsConfigService, FeedsProcessingService feedsProcessingService) {
        this.feedsConfigService = feedsConfigService;
        this.feedsProcessingService = feedsProcessingService;
    }

    private final FeedsConfigService feedsConfigService;

    private final FeedsProcessingService feedsProcessingService;

    @Value("${downloadTimeoutSeconds}")
    private int downloadTimeoutSeconds;

    private final LocalTime dailyStartTime = LocalTime.of(5, 20);
    private final LocalTime dailyEndTime = LocalTime.of(22, 30);

    private final FeedsDownloadCircuitBreaker feedsDownloadCircuitBreaker = new FeedsDownloadCircuitBreaker();

    @Scheduled(initialDelay = 1000 * 2, fixedDelay = 1000 * 60 * 3)
    private void refreshScheduler() {
        feedsConfigService.getFeedsGroups().forEach(this::refresh);
    }

    private void refresh(FeedsConfig.FeedsGroup groupConfig) {

        LocalTime now = LocalTime.now();
        if (now.isBefore(dailyStartTime)) {
            log.debug("group '" + groupConfig.getGroupId() + "' daily start time not reached");
            return;
        }
        if (now.isAfter(dailyEndTime)) {
            log.debug("group '" + groupConfig.getGroupId() + "' daily end time reached");
            return;
        }

        var groupCache = FeedsCache.getInstance().lookupGroup(groupConfig.getGroupId());
        if (groupCache == null) {
            groupCache = FeedsCache.getInstance().defineGroup(groupConfig.getGroupId());
        }

        // check interval against cache
        if(!groupCache.getGroupFeeds().isEmpty()){
            var delayMinutes = getDelayMinutes(groupConfig);
            var maxLastRefresh = groupCache.getGroupFeeds().values().stream().map(FeedsCache.FeedCacheEntry::getLastRefresh).max(LocalDateTime::compareTo).orElseThrow();
            var maxDurationSinceLastRefresh = Duration.between(maxLastRefresh, LocalDateTime.now());
            if(maxDurationSinceLastRefresh.compareTo(Duration.ofMinutes(delayMinutes)) < 1){
                log.debug("group '" + groupConfig.getGroupId() + "' skipping refresh (cache): " + maxDurationSinceLastRefresh + " / " + delayMinutes);
                return;
            }
        }

        // check interval against method call
        if(groupCache.getLastRefreshMethodCall() != null &&
                Duration.between(groupCache.getLastRefreshMethodCall(), LocalDateTime.now()).toMinutes() < groupConfig.getGroupDefaultDurationMinutes()){
            log.debug("group '" + groupConfig.getGroupId() + "' skipping refresh (method call): " + groupCache.getLastRefreshMethodCall());
            return;
        }

        // finally refresh
        groupCache.setLastRefreshMethodCall(LocalDateTime.now());
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

        log.info("group '" + groupConfig.getGroupId() + "' new overall delay: " + getDelayMinutes(groupConfig) + " minutes (default: " +
                groupConfig.getGroupDefaultDurationMinutes() + ") - next refresh: " +
                (LocalTime.now().plusMinutes(getDelayMinutes(groupConfig))).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_TIME));
    }

    private void refreshFeed(FeedsConfig.FeedsGroup groupConfig, FeedsConfig.FeedConfig feedConfig, Map<String, FeedsCache.FeedCacheEntry> refreshedCache) {

        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(downloadTimeoutSeconds))
                .setRedirectsEnabled(true)
                .build();

        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build()) {

            HttpGet request = new HttpGet(feedConfig.getUrl());

            client.execute(request, response -> {
                int statusCode = response.getCode();
                if(statusCode != org.apache.hc.core5.http.HttpStatus.SC_OK){
                    throw new RuntimeException("HTTP Status Code: " + statusCode);
                }
                String responseBody = EntityUtils.toString(response.getEntity());
                String processedFeed = feedsProcessingService.processFeed(responseBody, feedConfig);
                handleRefreshSuccess(groupConfig, feedConfig, processedFeed, response.getHeaders(), responseBody, refreshedCache);
                return responseBody;
            });

        } catch (IOException e) {
            throw new RuntimeException("error getting feed: " + e.getMessage(), e);
        }
    }

    private void fallback(FeedsConfig.FeedsGroup groupConfig,  FeedsConfig.FeedConfig feedConfig, Exception e, Map<String, FeedsCache.FeedCacheEntry> refreshedCache) {
        log.info("-> refreshFeed FALLBACK: " + feedConfig.getName() + ": " + e.getMessage());
        handleRefreshError(groupConfig, feedConfig, refreshedCache);
    }

    private void handleRefreshSuccess(FeedsConfig.FeedsGroup groupConfig, FeedsConfig.FeedConfig feedConfig, String feed, Header[] responseHeader, String responseBody, Map<String, FeedsCache.FeedCacheEntry> refreshedCache){
        var ttl = newEmptyFeedCacheEntry(groupConfig, feedConfig, feed, responseHeader, responseBody, refreshedCache);
        log.info("-> refreshFeed OK: " + feedConfig.getName() + " - TTL: " + (ttl.getTtl().toMinutes() + " min (" + ttl.getSource() + ")"));
    }

    private void handleRefreshError(FeedsConfig.FeedsGroup groupConfig, FeedsConfig.FeedConfig feedConfig, Map<String, FeedsCache.FeedCacheEntry> refreshedCache){
        var groupCache = FeedsCache.getInstance().lookupGroup(groupConfig.getGroupId());
        if(groupCache.getGroupFeeds().containsKey(feedConfig.getKey())){
           refreshedCache.put(feedConfig.getKey(), groupCache.getGroupFeeds().get(feedConfig.getKey()));
        }else{
            newEmptyFeedCacheEntry(groupConfig, feedConfig, null, null, null, refreshedCache);
        }
        refreshedCache.get(feedConfig.getKey()).increaseRefreshErrorCounter();
    }

    private TtlInfo newEmptyFeedCacheEntry(FeedsConfig.FeedsGroup groupConfig, FeedsConfig.FeedConfig feedConfig, String feed, Header[] responseHeader, String responseBody, Map<String, FeedsCache.FeedCacheEntry> refreshedCache) {
        var ttl = getTtlMinutes(responseHeader, responseBody, feedConfig.getKey());
        FeedsCache.FeedCacheEntry feedCacheEntry = new FeedsCache.FeedCacheEntry();
        feedCacheEntry.setKey(feedConfig.getKey());
        feedCacheEntry.setLastRefresh(LocalDateTime.now());
        feedCacheEntry.setRefreshErrorCounter(0);
        feedCacheEntry.setContent(feed);
        feedCacheEntry.setHeaderLastModified(getHeaderValue(responseHeader, HttpHeaders.LAST_MODIFIED));
        feedCacheEntry.setHeaderContentType(getHeaderValue(responseHeader, HttpHeaders.CONTENT_TYPE));
        feedCacheEntry.setTtl(ttl.orElse(defaultTtl(groupConfig)));
        refreshedCache.put(feedConfig.getKey(), feedCacheEntry);
        return feedCacheEntry.getTtl();
    }

    private Optional<TtlInfo> getTtlMinutes(Header[] responseHeader, String responseBody, String key) {
        var optionals = List.of(
                getTtlMinutesFromHeaderMaxAge(responseHeader),
                getTtlMinutesFromHeaderRetryAfter(responseHeader, key),
                getTtlMinutesFromFeed(responseBody));
        return optionals.stream().filter(Optional::isPresent).map(Optional::get).max(Comparator.comparing(TtlInfo::getTtl));
    }

    private Optional<TtlInfo> getTtlMinutesFromHeaderMaxAge(Header[] responseHeader) {
        String cacheControl = getHeaderValue(responseHeader, HttpHeaders.CACHE_CONTROL);
            if (cacheControl != null && cacheControl.contains("max-age=")) {
                try {
                    long maxAgeSeconds = Long.parseLong(cacheControl.split("max-age=")[1].split(",")[0]);
                    return Optional.of(new TtlInfo(Duration.ofSeconds(maxAgeSeconds), "MaxAge"));
                } catch (NumberFormatException ignored) {
                }
            }
        return Optional.empty();
    }

    private Optional<TtlInfo> getTtlMinutesFromHeaderRetryAfter(Header[] responseHeader, String key) {
        String retryAfter = getHeaderValue(responseHeader, HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) {
                if (NumberUtils.isParsable(retryAfter)) {
                    long retrySeconds = Long.parseLong(retryAfter);
                    return Optional.of(new TtlInfo(Duration.ofSeconds(retrySeconds), "RetryAfterSec"));
                } else {
                    try {
                        TemporalAccessor retryAfterTime = DateTimeFormatter.RFC_1123_DATE_TIME.parse(retryAfter);
                        LocalDateTime localDateTime = LocalDateTime.from(retryAfterTime);
                        Duration duration = Duration.between(LocalDateTime.now(), localDateTime);
                        return duration.isNegative() ? Optional.empty() : Optional.of(new TtlInfo(duration, "RetryAfterTS"));
                    } catch (Exception ignored) {
                        log.warn("Could not parse retry-after: " + retryAfter + " for feed:" + key);
                    }
                }
            }
        return Optional.empty();
    }

    private Optional<TtlInfo> getTtlMinutesFromFeed(String responseBody) {
        if(responseBody == null || responseBody.isEmpty()){
            return Optional.empty();
        }
        try {
            WireFeed wireFeed = new WireFeedInput().build(new StringReader(Objects.requireNonNull(responseBody)));
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
                        if(dateTime.isAfter(ZonedDateTime.now())) {
                            var baseDuration = Duration.between(ZonedDateTime.now(), dateTime);
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

    private static String getHeaderValue(Header[] headers, String headerName) {
        if(headers != null) {
            for (Header header : headers) {
                if (header.getName().equalsIgnoreCase(headerName)) {
                    return header.getValue();
                }
            }
        }
        return null;
    }

}
