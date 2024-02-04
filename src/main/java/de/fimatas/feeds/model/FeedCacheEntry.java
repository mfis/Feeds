package de.fimatas.feeds.model;

import lombok.Data;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;

@Data
public class FeedCacheEntry {

    private String key;
    private String content;
    private int refreshErrorCounter;
    private LocalDateTime lastRefresh;
    private Long headerLastModified;
    private MediaType headerContentType;

    public void increaseRefreshErrorCounter(){
        refreshErrorCounter++;
    }

    public boolean hasActualContent(){
        return refreshErrorCounter < 10 && lastRefresh.isAfter(LocalDateTime.now().minusDays(1)) && content != null;
    }
}
