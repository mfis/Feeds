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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.StringReader;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.*;

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

    private final WebClient webClient = WebClient.builder().clientConnector(
            new ReactorClientHttpConnector(HttpClient.create().followRedirect(true))).build();

    private final Map<String, FeedCacheEntry> cache = new HashMap<>();

    private final FeedsDownloadCircuitBreaker feedsDownloadCircuitBreaker = new FeedsDownloadCircuitBreaker();

    public FeedCacheEntry getFeedCacheEntry(String key){
        if(StringUtils.isEmpty(key)){
            return null;
        }
        return cache.get(key);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "30 20 5-22 * * *")
    private void refreshFeedDownloads() {
        refresh();
    }

    private void refresh() {
        for(var feedConfig : feedsConfigService.getFeedsConfigList()){
            var decoratedRunnable = CircuitBreaker.decorateRunnable(feedsDownloadCircuitBreaker.getCircuitBreaker(feedConfig),
                   () -> refreshFeed(feedConfig));
            try {
                decoratedRunnable.run();
            } catch (Exception e) {
                fallback(feedConfig, e);
            }
        }
        var maxRefreshDurationMinutes = cache.values().stream().map(FeedCacheEntry::getTtl).max(Duration::compareTo).orElseThrow();
        System.out.println("MAX_REFRESH_DURATION_MINUTES: " + maxRefreshDurationMinutes);
    }

    private void refreshFeed(FeedConfig feedConfig) {

        ResponseEntity<String> response = webClient.get()
                .uri(feedConfig.getUrl())
                .retrieve()
                .toEntity(String.class)
                .block(Duration.ofSeconds(downloadTimeoutSeconds));

        if (response != null && response.getBody() != null) {
            String processedFeed = feedsProcessingService.processFeed(response.getBody(), feedConfig);
            handleRefreshSuccess(feedConfig, processedFeed, response);
        } else {
            throw new IllegalStateException("Leere Antwort vom Server erhalten.");
        }
    }

    private void fallback(FeedConfig feedConfig, Exception e) {
        log.info("FALLBACK: " + feedConfig.getName() + ": " + e.getMessage());
        handleRefreshError(feedConfig);
    }

    private void handleRefreshSuccess(FeedConfig feedConfig, String feed, ResponseEntity<String> responseEntity){
        newEmptyFeedCacheEntry(feedConfig, feed, responseEntity);
    }

    private void handleRefreshError(FeedConfig feedConfig){
        if(!cache.containsKey(feedConfig.getKey())){
            newEmptyFeedCacheEntry(feedConfig, null, null);
        }
        cache.get(feedConfig.getKey()).increaseRefreshErrorCounter();
    }

    private void newEmptyFeedCacheEntry(FeedConfig feedConfig, String feed, ResponseEntity<String> responseEntity) {
        var ttl = getTtlMinutes(responseEntity, feedConfig.getKey());
        log.info("refreshFeed OK: " + feedConfig.getName() + " - TTL: " + (ttl.map(d -> d.toMinutes() + " minutes").orElse("n/a")));
        FeedCacheEntry feedCacheEntry = new FeedCacheEntry();
        feedCacheEntry.setKey(feedConfig.getKey());
        feedCacheEntry.setLastRefresh(LocalDateTime.now());
        feedCacheEntry.setRefreshErrorCounter(0);
        feedCacheEntry.setContent(feed);
        feedCacheEntry.setHeaderLastModified(responseEntity != null ? responseEntity.getHeaders().getLastModified() : null);
        feedCacheEntry.setHeaderContentType(responseEntity != null ? responseEntity.getHeaders().getContentType() : null);
        feedCacheEntry.setTtl(ttl.orElseGet(() -> Duration.ofMinutes(defaultRefreshDurationMinutes)));
        cache.put(feedConfig.getKey(), feedCacheEntry);
    }

    private Optional<Duration> getTtlMinutes(ResponseEntity<String> responseEntity, String key) {
        var optionals = List.of(
                getTtlMinutesFromHeaderMaxAge(responseEntity),
                getTtlMinutesFromHeaderRetryAfter(responseEntity, key),
                getTtlMinutesFromFeed(responseEntity));
        return optionals.stream().filter(Optional::isPresent).map(Optional::get).max(Duration::compareTo);
    }

    private Optional<Duration> getTtlMinutesFromHeaderMaxAge(ResponseEntity<String> responseEntity) {
        if (responseEntity.getHeaders().containsKey(HttpHeaders.CACHE_CONTROL)) {
            String cacheControl = responseEntity.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
            if (cacheControl != null && cacheControl.contains("max-age=")) {
                try {
                    long maxAgeSeconds = Long.parseLong(cacheControl.split("max-age=")[1].split(",")[0]);
                    return Optional.of(Duration.ofSeconds(maxAgeSeconds));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Duration> getTtlMinutesFromHeaderRetryAfter(ResponseEntity<String> responseEntity, String key) {
        if (responseEntity.getHeaders().containsKey("Retry-After")) {
            String retryAfter = responseEntity.getHeaders().getFirst("Retry-After");
            if (Objects.nonNull(retryAfter)) {
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
        }
        return Optional.empty();
    }

    private Optional<Duration> getTtlMinutesFromFeed(ResponseEntity<String> responseEntity) {
        try {
            WireFeed wireFeed = new WireFeedInput().build(new StringReader(Objects.requireNonNull(responseEntity.getBody())));
            if (wireFeed instanceof Channel channel && channel.getTtl() > 0) {
                return Optional.of(Duration.ofMinutes(channel.getTtl()));
            }
        } catch (FeedException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
