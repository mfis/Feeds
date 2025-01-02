package de.fimatas.feeds.components;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rometools.rome.feed.rss.Channel;
import com.rometools.rome.io.WireFeedOutput;
import de.fimatas.feeds.model.FeedsCache;
import de.fimatas.feeds.model.FeedsCircuitBreaker;
import de.fimatas.feeds.model.FeedsConfig;
import de.fimatas.feeds.model.FeedsHttpClientResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAmount;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2}) // 0=none, 1=httpClient, 2=processing
    void refreshScheduler_OutOfTimeDailyStartTime(int errorType) {
        // Arrange
        arrangeTimerBase1200(Duration.ofHours(-8)); // 04:00
        arrangeTestRefreshScheduler(errorType);
        // Act
        feedsDownloadService.refreshScheduler();
        // Assert
        verify(feedsHttpClient, times(0)).getFeeds(anyString());
        assertEquals(1, countLogging(REFRESH_SCHEDULER_DAILY_START_TIME_NOT_REACHED));
        assertEquals(0, countLogging(NEW_OVERALL_DELAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2}) // 0=none, 1=httpClient, 2=processing
    void refreshScheduler_OutOfTimeDailyEndTime(int errorType) {
        // Arrange
        arrangeTimerBase1200(Duration.ofHours(11).plusMinutes(30)); // 23:30
        arrangeTestRefreshScheduler(errorType);
        // Act
        feedsDownloadService.refreshScheduler();
        // Assert
        verify(feedsHttpClient, times(0)).getFeeds(anyString());
        assertEquals(1, countLogging(REFRESH_SCHEDULER_DAILY_END_TIME_REACHED));
        assertEquals(0, countLogging(NEW_OVERALL_DELAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2}) // 0=none, 1=httpClient, 2=processing
    void refreshScheduler_InvalidCache(int errorType) {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler(errorType);
        FeedsCache.getInstance().invalidateCache();
        // Act
        feedsDownloadService.refreshScheduler();
        // Assert
        verify(feedsHttpClient, times(0)).getFeeds(anyString());
        assertEquals(1, countLogging(CACHE_IS_NOT_VALID));
        assertEquals(0, countLogging(NEW_OVERALL_DELAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2}) // 0=none, 1=httpClient, 2=processing
    void refreshScheduler_callOnce(int errorType) {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler(errorType);
        // Act
        feedsDownloadService.refreshScheduler();
        // Assert
        verify(feedsHttpClient, times(getFeedsCount())).getFeeds(anyString());
        assertEquals(getGroupsCount(), countLogging(NEW_OVERALL_DELAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2}) // 0=none, 1=httpClient, 2=processing
    void refreshScheduler_callMultipleSimple(int errorType) {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler(errorType);
        // Act
        IntStream.range(0, COUNT_MULTIPLE_CALLS).forEach(i -> feedsDownloadService.refreshScheduler());
        // Assert
        verify(feedsHttpClient, times(getFeedsCount())).getFeeds(anyString());
        assertEquals(COUNT_MULTIPLE_CALLS - 1, countLogging(REFRESH_SCHEDULER_CALLED_TOO_FREQUENTLY));
        assertEquals(getGroupsCount(), countLogging(NEW_OVERALL_DELAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2}) // 0=none, 1=httpClient, 2=processing
    void refreshScheduler_callMultipleSimpleWithException(int errorType) {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler(errorType);
        feedsConfigService.getFeedsGroups().set(0, null); // <<--
        // Act
        IntStream.range(0, COUNT_MULTIPLE_CALLS).forEach(i -> {
            feedsDownloadService.lastSchedulerRun = LocalDateTime.now().minusDays(1);
            feedsDownloadService.refreshScheduler();
        });
        // Assert
        verify(feedsHttpClient, times(0)).getFeeds(anyString());
        assertEquals(COUNT_MULTIPLE_CALLS - 1, countLogging(REFRESH_SCHEDULER_WITH_EXCEPTION_CALLED_TOO_FREQUENTLY));
        assertEquals(0, countLogging(NEW_OVERALL_DELAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2}) // 0=none, 1=httpClient, 2=processing
    void refreshScheduler_callMultipleCheckIntervalAgainstCache(int errorType) {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler(errorType);
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
        assertEquals(getGroupsCount(), countLogging(NEW_OVERALL_DELAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2}) // 0=none, 1=httpClient, 2=processing
    void refreshScheduler_callMultipleCheckIntervalAgainstMethodCallWithEmptyGroup(int errorType) {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler(errorType);
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
        assertEquals(getGroupsCount(), countLogging(NEW_OVERALL_DELAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2}) // 0=none, 1=httpClient, 2=processing
    void refreshScheduler_callMultipleCheckIntervalAgainstMethodCallOldFeedTimestamp(int errorType) {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler(errorType);
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
        assertEquals(getGroupsCount(), countLogging(NEW_OVERALL_DELAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {0}) // 0 = none
    void refreshScheduler_callMultipleAllOkay(int errorType) {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler(errorType);
        arrangeDefaultRefreshDuration(10);
        // Act
        IntStream.range(0, COUNT_MULTIPLE_CALLS).forEach(i -> {
            arrangeTimerBase1200(Duration.ofMinutes(10).multipliedBy(i + 1));
            feedsDownloadService.refreshScheduler();
        });
        // Assert
        verify(feedsHttpClient, times(getFeedsCount() * COUNT_MULTIPLE_CALLS)).getFeeds(anyString()); // calls
        assertEquals(0, countLogging(SKIPPING_REFRESH_METHOD_CALL)); // returns
        assertEquals(getGroupsCount() * COUNT_MULTIPLE_CALLS, countLogging(NEW_OVERALL_DELAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2}) // 1=httpClient, 2=processing
    void refreshScheduler_callMultipleWithErrorForCircuitBreakerOpenState(int errorType) {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler(errorType);
        arrangeDefaultRefreshDuration(10);
        var fc = new FeedsConfig.FeedConfig();
        fc.setKey("key");
        var cb = new FeedsDownloadCircuitBreaker().getCircuitBreaker(fc);
        // Act
        IntStream.range(0, COUNT_MULTIPLE_CALLS).forEach(i -> {
            arrangeTimerBase1200(Duration.ofMinutes(10).multipliedBy(i + 1));
            feedsDownloadService.refreshScheduler();
        });
        // Assert
        verify(feedsHttpClient, times(getFeedsCount() * cb.getCircuitBreakerConfig().getMinimumNumberOfCalls())).getFeeds(anyString()); // calls
        assertEquals(0, countLogging(SKIPPING_REFRESH_METHOD_CALL)); // returns
        assertEquals(getGroupsCount() * COUNT_MULTIPLE_CALLS, countLogging(NEW_OVERALL_DELAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2}) // 1=httpClient, 2=processing
    void refreshScheduler_callMultipleWithErrorForCircuitBreakerWithTransitionToClosedState(int errorType) throws InterruptedException {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler(errorType);
        arrangeDefaultRefreshDuration(10);
        arrangeTestCircuitBreaker();
        var fc = new FeedsConfig.FeedConfig();
        fc.setKey("key");
        var cb = new FeedsDownloadCircuitBreaker().getCircuitBreaker(fc);
        // Act
        IntStream.range(0, COUNT_MULTIPLE_CALLS).forEach(i -> {
            arrangeTimerBase1200(Duration.ofMinutes(10).multipliedBy(i + 1));
            feedsDownloadService.refreshScheduler();
        });
        // Assert
        var numberOfExpectedHttpCalls = getFeedsCount() * cb.getCircuitBreakerConfig().getMinimumNumberOfCalls();
        verify(feedsHttpClient, times(numberOfExpectedHttpCalls)).getFeeds(anyString()); // calls
        assertEquals(0, countLogging(SKIPPING_REFRESH_METHOD_CALL)); // returns
        assertEquals(getGroupsCount() * COUNT_MULTIPLE_CALLS, countLogging(NEW_OVERALL_DELAY));

        arrangeTimerBase1200(Duration.ofHours(4));
        Thread.sleep(2010L);

        feedsDownloadService.refreshScheduler();
        verify(feedsHttpClient, times(numberOfExpectedHttpCalls + getFeedsCount())).getFeeds(anyString()); // calls
        assertEquals(0, countLogging(SKIPPING_REFRESH_METHOD_CALL)); // returns
        assertEquals((getGroupsCount() * COUNT_MULTIPLE_CALLS) + getGroupsCount(), countLogging(NEW_OVERALL_DELAY));
    }

    // TODO: NEW BEAN INSTANCE, EXISTING CACHE
    // TODO: CACHE READ/WRITE ERROR
    // TODO: TTL
    // TODO: PROCESSING_SERVICE

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

    private void arrangeTestRefreshScheduler(int errorType) {
        feedsDownloadService.init();
        FeedsHttpClientResponse mockResponse = new FeedsHttpClientResponse(new HashMap<>(), minimalFeed(false));
        lenient().when(feedsHttpClient.getFeeds(anyString())).thenReturn(mockResponse);
        lenient().when(feedsProcessingService.processFeed(any(), any())).thenReturn(minimalFeed(true));
        if(errorType==1){
            lenient().when(feedsHttpClient.getFeeds(anyString())).thenThrow(new RuntimeException("test exception httpclient"));
        }else if(errorType == 2){
            lenient().when(feedsProcessingService.processFeed(any(), any())).thenThrow(new RuntimeException("test exception processing"));
        }
    }

    private void arrangeDefaultRefreshDuration(int minutes) {
        feedsConfigService.getFeedsGroups().forEach(g -> g.setGroupDefaultDurationMinutes(minutes));
    }

    private void arrangeTestCircuitBreaker() {
        feedsDownloadService.feedsDownloadCircuitBreaker = new FeedsTestCircuitBreaker();
    }

    private static class FeedsTestCircuitBreaker implements FeedsCircuitBreaker {

        private final CircuitBreakerRegistry circuitBreakerRegistry;
        private final Map<String, CircuitBreaker> circuitBreakerMap = new ConcurrentHashMap<>();

        public FeedsTestCircuitBreaker() {
            CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
                    .failureRateThreshold(66)
                    .slidingWindowSize(3)
                    .waitDurationInOpenState(Duration.ofSeconds(2)) // <<--
                    .permittedNumberOfCallsInHalfOpenState(1)
                    .minimumNumberOfCalls(3)
                    .automaticTransitionFromOpenToHalfOpenEnabled(true)
                    .build();
            this.circuitBreakerRegistry = CircuitBreakerRegistry.of(defaultConfig);
        }

        public CircuitBreaker getCircuitBreaker(FeedsConfig.FeedConfig feedConfig) {
            return circuitBreakerMap.computeIfAbsent(feedConfig.getKey(),
                    key -> circuitBreakerRegistry.circuitBreaker("FeedsDownloadCircuitBreaker-" + key));
        }
    }
}