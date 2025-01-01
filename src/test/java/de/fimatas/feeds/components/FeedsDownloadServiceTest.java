package de.fimatas.feeds.components;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAmount;
import java.util.HashMap;
import java.util.stream.IntStream;

import static de.fimatas.feeds.model.FeedsLogMessages.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class FeedsDownloadServiceTest {

    @Mock
    private FeedsProcessingService feedsProcessingService;

    @Mock
    private FeedsHttpClient feedsHttpClient;

    @Mock
    private FeedsTimer feedsTimer;

    private FeedsConfigService feedsConfigService;
    private FeedsDownloadService feedsDownloadService;
    private Logger logger;
    private ListAppender<ILoggingEvent> loggingListAppender;

    private final static int COUNT_MULTIPLE_CALLS = 20;

    @BeforeEach
    void beforeEach() {
        //noinspection LoggerInitializedWithForeignClass
        logger = (Logger) LoggerFactory.getLogger(FeedsDownloadService.class);
        logger.setLevel(Level.DEBUG);
        loggingListAppender = new ListAppender<>();
        loggingListAppender.start();
        logger.addAppender(loggingListAppender);

        System.setProperty("active.profile", "test");
        FeedsCache.getInstance().destroyCache();
        MockitoAnnotations.openMocks(this);

        feedsConfigService = new FeedsConfigService();
        feedsConfigService.useTestConfig = true;
        feedsDownloadService = new FeedsDownloadService(feedsConfigService, feedsProcessingService, feedsHttpClient, feedsTimer);
    }

    @AfterEach
    void afterEach() {
        FeedsCache.getInstance().destroyCache();
        System.clearProperty("active.profile");

        logger.setLevel(null);
        logger.detachAppender(loggingListAppender);
        loggingListAppender.stop();
    }

    @Test
    void refreshScheduler_OutOfTimeDailyStartTime() {
        // Arrange
        arrangeTimerBase1200(Duration.ofHours(-8)); // 04:00
        arrangeTestRefreshScheduler();
        // Act
        feedsDownloadService.refreshScheduler();
        // Assert
        verify(feedsHttpClient, times(0)).getFeeds(anyString());
        assertEquals(1, countLogging(REFRESH_SCHEDULER_DAILY_START_TIME_NOT_REACHED));
    }

    @Test
    void refreshScheduler_OutOfTimeDailyEndTime() {
        // Arrange
        arrangeTimerBase1200(Duration.ofHours(11).plusMinutes(30)); // 23:30
        arrangeTestRefreshScheduler();
        // Act
        feedsDownloadService.refreshScheduler();
        // Assert
        verify(feedsHttpClient, times(0)).getFeeds(anyString());
        assertEquals(1, countLogging(REFRESH_SCHEDULER_DAILY_END_TIME_REACHED));
    }

    @Test
    void refreshScheduler_InvalidCache() {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler();
        FeedsCache.getInstance().invalidateCache();
        // Act
        feedsDownloadService.refreshScheduler();
        // Assert
        verify(feedsHttpClient, times(0)).getFeeds(anyString());
        assertEquals(1, countLogging(CACHE_IS_NOT_VALID));
    }

    @Test
    void refreshScheduler_callOnce() {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler();
        // Act
        feedsDownloadService.refreshScheduler();
        // Assert
        verify(feedsHttpClient, times(getFeedsCount())).getFeeds(anyString());
    }

    @Test
    void refreshScheduler_callMultipleSimple() {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler();
        // Act
        IntStream.range(0, COUNT_MULTIPLE_CALLS).forEach(i -> feedsDownloadService.refreshScheduler());
        // Assert
        verify(feedsHttpClient, times(getFeedsCount())).getFeeds(anyString());
        assertEquals(COUNT_MULTIPLE_CALLS - 1, countLogging(REFRESH_SCHEDULER_CALLED_TOO_FREQUENTLY));
    }

    @Test
    void refreshScheduler_callMultipleSimpleWithException() {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler();
        feedsConfigService.getFeedsGroups().set(0, null);
        // Act
        IntStream.range(0, COUNT_MULTIPLE_CALLS).forEach(i -> {
            feedsDownloadService.lastSchedulerRun = LocalDateTime.now().minusDays(1);
            feedsDownloadService.refreshScheduler();
        });
        // Assert
        verify(feedsHttpClient, times(0)).getFeeds(anyString());
        assertEquals(COUNT_MULTIPLE_CALLS - 1, countLogging(REFRESH_SCHEDULER_WITH_EXCEPTION_CALLED_TOO_FREQUENTLY));
    }

    @Test
    void refreshScheduler_callMultipleSCheckIntervalAgainstCache() {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler();
        arrangeDefaultRefreshDuration(1000);
        // Act
        IntStream.range(0, COUNT_MULTIPLE_CALLS).forEach(i -> {
            arrangeTimerBase1200(feedsDownloadService.minimumSchedulerRunDuration.multipliedBy(i + 1));
            feedsDownloadService.lastSchedulerRun = LocalDateTime.now().minusDays(1);
            feedsDownloadService.refreshScheduler();
        });
        // Assert
        verify(feedsHttpClient, times(getFeedsCount())).getFeeds(anyString()); // calls
        assertEquals((COUNT_MULTIPLE_CALLS * getGroupsCount()) - getGroupsCount(), countLogging(SKIPPING_REFRESH_CACHE)); // returns
    }

    @Test
    void refreshScheduler_callMultipleSCheckIntervalAgainstMethodCallWithEmptyGroup() {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler();
        arrangeDefaultRefreshDuration(1000);
        // Act
        IntStream.range(0, COUNT_MULTIPLE_CALLS).forEach(i -> {
            arrangeTimerBase1200(feedsDownloadService.minimumSchedulerRunDuration.multipliedBy(i + 1));
            feedsDownloadService.lastSchedulerRun = LocalDateTime.now().minusDays(1);
            feedsDownloadService.refreshScheduler();
            feedsConfigService.getFeedsGroups().forEach(fg -> FeedsCache.getInstance().lookupGroup(fg.getGroupId()).getGroupFeeds().clear()); // cheat feed update timestamp
        });
        // Assert
        verify(feedsHttpClient, times(getFeedsCount())).getFeeds(anyString()); // calls
        assertEquals((COUNT_MULTIPLE_CALLS * getGroupsCount()) - getGroupsCount(), countLogging(SKIPPING_REFRESH_METHOD_CALL)); // returns
    }

    @Test
    void refreshScheduler_callMultipleSCheckIntervalAgainstMethodCallOldFeedTimestamp() {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler();
        arrangeDefaultRefreshDuration(100);
        // Act
        IntStream.range(0, COUNT_MULTIPLE_CALLS).forEach(i -> {
            arrangeTimerBase1200(feedsDownloadService.minimumSchedulerRunDuration.multipliedBy(i + 1));
            feedsDownloadService.lastSchedulerRun = LocalDateTime.now().minusDays(1);
            feedsDownloadService.refreshScheduler();
            feedsConfigService.getFeedsGroups().forEach(fg
                    -> FeedsCache.getInstance().lookupGroup(fg.getGroupId()).getGroupFeeds().forEach((k, v)
                    -> v.setLastRefresh(v.getLastRefresh().minusMinutes(96)))); // cheat feed update timestamp
        });
        // Assert
        verify(feedsHttpClient, times(getFeedsCount())).getFeeds(anyString()); // calls
        assertEquals((COUNT_MULTIPLE_CALLS * getGroupsCount()) - getGroupsCount(), countLogging(SKIPPING_REFRESH_METHOD_CALL)); // returns
    }

    // TODO: UNCHEATED REFRESH CASE
    // TODO: NEW BEAN INSTANCE, EXISTING CACHE
    // TODO: CACHE READ/WRITE ERROR
    // TODO: COUNT 'new overall delay'
    // TODO: FALLBACK CASE
    // TODO: CIRCUIT BREAKER

    private int getFeedsCount(){
        return (int) feedsConfigService.getFeedsGroups().stream().mapToLong(g -> g.getGroupFeeds().size()).sum();
    }

    private int getGroupsCount(){
        return feedsConfigService.getFeedsGroups().size();
    }

    private int countLogging(String messagePart) {
        return (int) loggingListAppender.list.stream().filter(log -> log.getFormattedMessage().contains(messagePart)).count();
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

    private void arrangeTimerBase1200(TemporalAmount temporalAmount) {
        LocalDateTime base = LocalDateTime.of(2025, 1, 1, 12, 0);
        lenient().when(feedsTimer.localDateTimeNow()).thenReturn(base.plus(temporalAmount));
        lenient().when(feedsTimer.localTimeNow()).thenReturn(base.toLocalTime().plus(temporalAmount));
        lenient().when(feedsTimer.zonedDateTimeNow()).thenReturn(base.plus(temporalAmount).atZone(ZoneId.systemDefault()));
    }

    private void arrangeTestRefreshScheduler() {
        feedsDownloadService.init();
        FeedsHttpClientResponse mockResponse = new FeedsHttpClientResponse(new HashMap<>(), minimalFeed(false));
        lenient().when(feedsHttpClient.getFeeds(anyString())).thenReturn(mockResponse);
        lenient().when(feedsProcessingService.processFeed(any(), any())).thenReturn(minimalFeed(true));
    }

    private void arrangeDefaultRefreshDuration(int minutes) {
        feedsConfigService.getFeedsGroups().forEach(g -> g.setGroupDefaultDurationMinutes(minutes));
    }
}