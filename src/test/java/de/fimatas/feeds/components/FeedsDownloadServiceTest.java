package de.fimatas.feeds.components;

import com.rometools.rome.feed.rss.Channel;
import com.rometools.rome.io.WireFeedOutput;
import de.fimatas.feeds.model.FeedsCache;
import de.fimatas.feeds.model.FeedsHttpClientResponse;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class FeedsDownloadServiceTest {

    @Mock
    private FeedsProcessingService feedsProcessingService;

    @Mock
    private FeedsHttpClient feedsHttpClient;

    private FeedsConfigService feedsConfigService;

    private FeedsDownloadService feedsDownloadService;

    @BeforeEach
    void beforeEach() {
        System.setProperty("active.profile", "test");
        FeedsCache.getInstance().deleteCacheFile();
        MockitoAnnotations.openMocks(this);

        feedsConfigService = new FeedsConfigService();
        feedsConfigService.useTestConfig = true;
        feedsDownloadService = new FeedsDownloadService(feedsConfigService, feedsProcessingService, feedsHttpClient);
    }

    @AfterEach
    void afterEach() {
        FeedsCache.getInstance().deleteCacheFile();
        System.clearProperty("active.profile");
    }

    @Test
    void refreshScheduler_callOnce() {
        // Arrange
        FeedsHttpClientResponse mockResponse = new FeedsHttpClientResponse(new HashMap<>(), minimalFeed(false));
        when(feedsHttpClient.getFeeds(anyString())).thenReturn(mockResponse);
        when(feedsProcessingService.processFeed(any(), any())).thenReturn(minimalFeed(true));
        // Act
        feedsDownloadService.refreshScheduler();
        // Assert
        verify(feedsHttpClient, times(getFeedsCount())).getFeeds(anyString());
    }

    private int getFeedsCount(){
        return (int) feedsConfigService.getFeedsGroups().stream().mapToLong(g -> g.getGroupFeeds().size()).sum();
    }

    @SneakyThrows
    private String minimalFeed(boolean processed){
        Channel channel = new Channel();
        channel.setFeedType("rss_2.0");
        channel.setTitle("Title");
        channel.setDescription(processed ? "processed" : "raw");
        channel.setLink("http://localhost");
        return new WireFeedOutput().outputString(channel);
    }
}