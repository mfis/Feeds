package de.fimatas.feeds.components;

import de.fimatas.feeds.model.FeedCacheEntry;
import de.fimatas.feeds.model.FeedConfig;
import lombok.extern.apachecommons.CommonsLog;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Component
@CommonsLog
public class FeedsDownloadService {

    @Autowired
    private FeedsConfigService feedsConfigService;

    @Autowired
    private FeedsProcessingService feedsProcessingService;

    @Value("${downloadTimeoutSeconds}")
    private int downloadTimeoutSeconds;

    private final WebClient webClient = WebClient.builder().clientConnector(
            new ReactorClientHttpConnector(HttpClient.create().followRedirect(true))).build();

    private final Map<String, FeedCacheEntry> cache = new HashMap<>();

    public FeedCacheEntry getFeedCacheEntry(String key){
        if(StringUtils.isEmpty(key)){
            return null;
        }
        return cache.get(key);
    }

    @Scheduled(initialDelay = 300, fixedDelayString = "${refreshInterval}")
    private void refreshFeedDownloads() {
        if(LocalDateTime.now().getHour() < 5
                || (LocalDateTime.now().getHour() == 5 && LocalDateTime.now().getMinute() < 20)){
            // no refresh at night
            return;
        }
        refresh();
    }

    private void refresh() {
        feedsConfigService.getFeedsConfigList().forEach(this::refreshFeed);
    }

    private void refreshFeed(FeedConfig feedConfig) {

        log.info("refreshFeed:" + feedConfig.getName());
        Mono<ResponseEntity<String>> mono = webClient.get()
                .uri(feedConfig.getUrl())
                .retrieve()
                .toEntity(String.class)
                .timeout(Duration.ofSeconds(downloadTimeoutSeconds));
        mono.subscribe(result -> {
                try {
                    String processedFeed = feedsProcessingService.processFeed(result.getBody(), feedConfig);
                    handleRefreshSuccess(feedConfig, processedFeed, result);
                }catch(Exception e){
                    log.error("refreshFeed (" + feedConfig.getName() + ") nicht erfolgreich: ", e);
                    handleRefreshError(feedConfig);
                }
            }, error -> {
                log.error("refreshFeed (" + feedConfig.getName() + ") nicht erfolgreich: " + error);
                handleRefreshError(feedConfig);
            }
        );
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
        FeedCacheEntry feedCacheEntry = new FeedCacheEntry();
        feedCacheEntry.setKey(feedConfig.getKey());
        feedCacheEntry.setLastRefresh(LocalDateTime.now());
        feedCacheEntry.setRefreshErrorCounter(0);
        feedCacheEntry.setContent(feed);
        feedCacheEntry.setHeaderLastModified(responseEntity != null ? responseEntity.getHeaders().getLastModified() : null);
        feedCacheEntry.setHeaderContentType(responseEntity != null ? responseEntity.getHeaders().getContentType() : null);
        cache.put(feedConfig.getKey(), feedCacheEntry);
    }
}
