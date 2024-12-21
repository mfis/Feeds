package de.fimatas.feeds.components;

import com.rometools.rome.feed.WireFeed;
import com.rometools.rome.feed.rss.Channel;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.WireFeedInput;
import de.fimatas.feeds.model.FeedCacheEntry;
import de.fimatas.feeds.model.FeedConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.apachecommons.CommonsLog;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
@CommonsLog
public class FeedsDownloadService {

    @Autowired
    private FeedsConfigService feedsConfigService;

    @Autowired
    private FeedsProcessingService feedsProcessingService;

    @Value("${downloadTimeoutSeconds}")
    private int downloadTimeoutSeconds;

    @Value("${defaultRefreshDurationMinutes}")
    private int defaultRefreshDurationMinutes;

    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> currentTask;
    private final LocalTime dailyEndTime = LocalTime.of(22, 30);   // Endzeit

    private WebClient webClient;

    private Map<String, FeedCacheEntry> cache = new HashMap<>();

    private final FeedsDownloadCircuitBreaker feedsDownloadCircuitBreaker = new FeedsDownloadCircuitBreaker();

    private LocalDateTime lastRefreshMethodCall = null;

    public FeedCacheEntry getFeedCacheEntry(String key){
        if(StringUtils.isEmpty(key)){
            return null;
        }
        return cache.get(key);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "30 20 5 * * *")
    private synchronized void refreshFeedDownloads() {
        if(lastRefreshMethodCall == null){
            lastRefreshMethodCall = LocalDateTime.now().minusMinutes(defaultRefreshDurationMinutes);
        }
        cancelCurrentTaskIfRunning();
        scheduleNextTask(true);
    }

    private void cancelCurrentTaskIfRunning() {
        if (currentTask != null && !currentTask.isDone()) {
            log.info("cancel current task");
            currentTask.cancel(true);
        }
    }

    private synchronized void scheduleNextTask(boolean isInitialRun) {
        LocalTime now = LocalTime.now();

        if (now.isAfter(dailyEndTime)) {
            log.info("daily end time reached");
            return;
        }

        currentTask = executorService.schedule(() -> {
            try {
                refresh(isInitialRun);
                scheduleNextTask(false);
            } catch(Exception ex) {
                log.error("Exception occured executingrefresh: ", ex);
            }
        // FIXME: }, getDelayMinutes(isInitialRun) + (isInitialRun? 0 : 1), TimeUnit.MINUTES);
        }, isInitialRun ? 0 : 5, TimeUnit.MINUTES);
    }

    private void refresh(boolean isInitialRun) {

        // check interval against cache
        if(!isInitialRun){
            var maxLastRefresh = cache.values().stream().map(FeedCacheEntry::getLastRefresh).max(LocalDateTime::compareTo).orElseThrow();
            var maxDurationSinceLastRefresh = Duration.between(maxLastRefresh, LocalDateTime.now());
            var delayMinutes = getDelayMinutes();
            if(maxDurationSinceLastRefresh.compareTo(Duration.ofMinutes(delayMinutes)) < 1){
                log.warn("skipping refresh (cache): " + maxDurationSinceLastRefresh + " / " + delayMinutes);
                // FIXME: return;
            }
        }

        // check interval against method call
        if(Duration.between(lastRefreshMethodCall, LocalDateTime.now()).toMinutes() < defaultRefreshDurationMinutes){
            log.warn("skipping refresh (method call): " + lastRefreshMethodCall);
            // FIXME: return;
        }

        // finally refresh
        lastRefreshMethodCall = LocalDateTime.now();
        Map<String, FeedCacheEntry> refreshedCache = new HashMap<>();
        for(var feedConfig : feedsConfigService.getFeedsConfigList()){
            var decoratedRunnable = CircuitBreaker.decorateRunnable(feedsDownloadCircuitBreaker.getCircuitBreaker(feedConfig),
                   () -> refreshFeed(feedConfig, refreshedCache));
            try {
                decoratedRunnable.run();
            } catch (Exception e) {
                fallback(feedConfig, e, refreshedCache);
            }
        }
        cache = refreshedCache;
    }

    private void refreshFeed(FeedConfig feedConfig, Map<String, FeedCacheEntry> refreshedCache) {

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
                handleRefreshSuccess(feedConfig, processedFeed, response.getHeaders(), responseBody, refreshedCache);
                return responseBody;
            });

        } catch (IOException e) {
            throw new RuntimeException("error getting feed: " + e.getMessage(), e);
        }
    }

    private void fallback(FeedConfig feedConfig, Exception e, Map<String, FeedCacheEntry> refreshedCache) {
        log.info("FALLBACK: " + feedConfig.getName() + ": " + e.getMessage());
        handleRefreshError(feedConfig, refreshedCache);
    }

    private void handleRefreshSuccess(FeedConfig feedConfig, String feed, Header[] responseHeader, String responseBody, Map<String, FeedCacheEntry> refreshedCache){
        var ttl = newEmptyFeedCacheEntry(feedConfig, feed, responseHeader, responseBody, refreshedCache);
        log.info("refreshFeed OK: " + feedConfig.getName() + " - TTL: " + (ttl.map(d -> d.toMinutes() + " minutes").orElse("n/a")));
    }

    private void handleRefreshError(FeedConfig feedConfig, Map<String, FeedCacheEntry> refreshedCache){
        if(cache.containsKey(feedConfig.getKey())){
           refreshedCache.put(feedConfig.getKey(), cache.get(feedConfig.getKey()));
        }else{
            newEmptyFeedCacheEntry(feedConfig, null, null, null, refreshedCache);
        }
        refreshedCache.get(feedConfig.getKey()).increaseRefreshErrorCounter();
    }

    private Optional<Duration> newEmptyFeedCacheEntry(FeedConfig feedConfig, String feed, Header[] responseHeader, String responseBody, Map<String, FeedCacheEntry> refreshedCache) {
        var ttl = getTtlMinutes(responseHeader, responseBody, feedConfig.getKey());
        FeedCacheEntry feedCacheEntry = new FeedCacheEntry();
        feedCacheEntry.setKey(feedConfig.getKey());
        feedCacheEntry.setLastRefresh(LocalDateTime.now());
        feedCacheEntry.setRefreshErrorCounter(0);
        feedCacheEntry.setContent(feed);
        feedCacheEntry.setHeaderLastModified(getHeaderValue(responseHeader, HttpHeaders.LAST_MODIFIED));
        feedCacheEntry.setHeaderContentType(getHeaderValue(responseHeader, HttpHeaders.CONTENT_TYPE));
        feedCacheEntry.setTtl(ttl.orElseGet(() -> Duration.ofMinutes(defaultRefreshDurationMinutes)));
        refreshedCache.put(feedConfig.getKey(), feedCacheEntry);
        return ttl;
    }

    private Optional<Duration> getTtlMinutes(Header[] responseHeader, String responseBody, String key) {
        var optionals = List.of(
                getTtlMinutesFromHeaderMaxAge(responseHeader),
                getTtlMinutesFromHeaderRetryAfter(responseHeader, key),
                getTtlMinutesFromFeed(responseBody));
        return optionals.stream().filter(Optional::isPresent).map(Optional::get).max(Duration::compareTo);
    }

    private Optional<Duration> getTtlMinutesFromHeaderMaxAge(Header[] responseHeader) {
        String cacheControl = getHeaderValue(responseHeader, HttpHeaders.CACHE_CONTROL);
            if (cacheControl != null && cacheControl.contains("max-age=")) {
                try {
                    long maxAgeSeconds = Long.parseLong(cacheControl.split("max-age=")[1].split(",")[0]);
                    return Optional.of(Duration.ofSeconds(maxAgeSeconds));
                } catch (NumberFormatException ignored) {
                }
            }
        return Optional.empty();
    }

    private Optional<Duration> getTtlMinutesFromHeaderRetryAfter(Header[] responseHeader, String key) {
        String retryAfter = getHeaderValue(responseHeader, HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) {
                if (NumberUtils.isParsable(retryAfter)) {
                    long retrySeconds = Long.parseLong(retryAfter);
                    return Optional.of(Duration.ofSeconds(retrySeconds));
                } else {
                    try {
                        TemporalAccessor retryAfterTime = DateTimeFormatter.RFC_1123_DATE_TIME.parse(retryAfter);
                        LocalDateTime localDateTime = LocalDateTime.from(retryAfterTime);
                        Duration duration = Duration.between(LocalDateTime.now(), localDateTime);
                        return duration.isNegative() ? Optional.empty() : Optional.of(duration);
                    } catch (Exception ignored) {
                        log.warn("Could not parse retry-after: " + retryAfter + " for feed:" + key);
                    }
                }
            }
        return Optional.empty();
    }

    private Optional<Duration> getTtlMinutesFromFeed(String responseBody) {
        if(responseBody == null || responseBody.isEmpty()){
            return Optional.empty();
        }
        try {
            WireFeed wireFeed = new WireFeedInput().build(new StringReader(Objects.requireNonNull(responseBody)));
            if (wireFeed instanceof Channel channel && channel.getTtl() > 0) {
                return Optional.of(Duration.ofMinutes(channel.getTtl()));
            }
            if(wireFeed.getForeignMarkup() != null) {
                var updatePeriod = foreignMarkupValue(wireFeed, "updatePeriod");
                var updateFrequency =foreignMarkupValue(wireFeed, "updateFrequency");
                var updateBase = foreignMarkupValue(wireFeed, "updateBase");
                if(updatePeriod != null && updateFrequency != null) {
                    var updateDuration = Duration.ofMinutes(getPeriodMinutes(updatePeriod) / Long.parseLong(updateFrequency));
                    if(updateBase != null) {
                        DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
                        ZonedDateTime dateTime = ZonedDateTime.parse(updateBase, formatter);
                        if(dateTime.isAfter(ZonedDateTime.now())) {
                            var baseDuration = Duration.between(ZonedDateTime.now(), dateTime);
                            return Optional.of(baseDuration.plus(updateDuration));
                        }
                    }
                    return Optional.of(updateDuration);
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

    private static String foreignMarkupValue(WireFeed wireFeed, String name) {
        return wireFeed.getForeignMarkup().stream().filter(fm -> fm.getName().equals(name)).findFirst().map(e -> StringUtils.trimToNull(e.getValue())).orElse(null);
    }

    private long getDelayMinutes() {
        return cache.values().stream().map(FeedCacheEntry::getTtl).max(Duration::compareTo)
                        .orElse(Duration.ofMinutes(defaultRefreshDurationMinutes)).toMinutes();
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
