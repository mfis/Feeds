package de.fimatas.feeds.components;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rometools.rome.feed.rss.Channel;
import com.rometools.rome.io.WireFeedOutput;
import de.fimatas.feeds.controller.ExampleController;
import de.fimatas.feeds.model.FeedsCache;
import de.fimatas.feeds.model.FeedsCircuitBreaker;
import de.fimatas.feeds.model.FeedsConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.TemporalAmount;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static de.fimatas.feeds.model.FeedsLogMessages.*;
import static org.junit.jupiter.api.Assertions.*;
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

    private LocalDateTime testLocalDateTime;
    private LocalTime testLocalTime;
    private ZonedDateTime testZonedDateTime;

    private final static int COUNT_MULTIPLE_CALLS = 20;

    @BeforeEach
    void beforeEach() {
        testLocalDateTime = null;
        testLocalTime = null;
        testZonedDateTime = null;
        //noinspection LoggerInitializedWithForeignClass
        logger = (Logger) LoggerFactory.getLogger(FeedsDownloadService.class);
        logger.setLevel(Level.DEBUG);
        loggingListAppender = new ListAppender<>();
        loggingListAppender.start();
        logger.addAppender(loggingListAppender);

        System.setProperty("active.profile", "test");
        FeedsCache.destroyCache();
        MockitoAnnotations.openMocks(this);

        feedsConfigService = new FeedsConfigService();
        feedsConfigService.useTestConfig = true;
        feedsDownloadService = new FeedsDownloadService(feedsConfigService, feedsProcessingService, feedsHttpClient, feedsTimer);
    }

    @AfterEach
    void afterEach() {
        FeedsCache.destroyCache();
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
        FeedsCache.invalidateCache();
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
            feedsDownloadService.lastSchedulerRun = testLocalDateTime.minusDays(1);
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
            feedsDownloadService.lastSchedulerRun = testLocalDateTime.minusDays(1);
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
            feedsDownloadService.lastSchedulerRun = testLocalDateTime.minusDays(1);
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
            feedsDownloadService.lastSchedulerRun = testLocalDateTime.minusDays(1);
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
            arrangeTimerBase1200(Duration.ofMinutes(15).multipliedBy(i + 1));
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
            arrangeTimerBase1200(Duration.ofMinutes(15).multipliedBy(i + 1));
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
            arrangeTimerBase1200(Duration.ofMinutes(15).multipliedBy(i + 1));
            feedsDownloadService.refreshScheduler();
        });
        // Assert
        var numberOfExpectedHttpCalls = getFeedsCount() * cb.getCircuitBreakerConfig().getMinimumNumberOfCalls();
        verify(feedsHttpClient, times(numberOfExpectedHttpCalls)).getFeeds(anyString()); // calls
        assertEquals(0, countLogging(SKIPPING_REFRESH_METHOD_CALL)); // returns
        assertEquals(getGroupsCount() * COUNT_MULTIPLE_CALLS, countLogging(NEW_OVERALL_DELAY));

        arrangeTimerBase1200(Duration.ofMinutes(340));
        Thread.sleep(2010L);

        feedsDownloadService.refreshScheduler();
        verify(feedsHttpClient, times(numberOfExpectedHttpCalls + getFeedsCount())).getFeeds(anyString()); // calls
        assertEquals(0, countLogging(SKIPPING_REFRESH_METHOD_CALL)); // returns
        assertEquals((getGroupsCount() * COUNT_MULTIPLE_CALLS) + getGroupsCount(), countLogging(NEW_OVERALL_DELAY));
    }

    @ParameterizedTest()
    @ValueSource(ints = {0, 1, 2}) // 0=none, 1=httpClient, 2=processing
    void refreshScheduler_callMultipleCacheFileReadErrorWhileInit(int errorType) {
        try {
            // Arrange
            FeedsCache.destroyCache();
            arrangeCacheFileReadError();
            beforeEach();
            arrangeCacheFileReadError();
            arrangeTimerBase1200(Duration.ofSeconds(0));
            arrangeTestRefreshScheduler(errorType);
            arrangeDefaultRefreshDuration(10);
            // Act
            IntStream.range(0, COUNT_MULTIPLE_CALLS).forEach(i -> {
                arrangeTimerBase1200(Duration.ofMinutes(15).multipliedBy(i + 1));
                feedsDownloadService.refreshScheduler();
            });
            // Assert
            fail();
        } catch (Exception e) {
            assertTrue(true);
        }
    }

    @ParameterizedTest()
    @ValueSource(ints = {0, 1, 2}) // 0=none, 1=httpClient, 2=processing
    void refreshScheduler_callMultipleCacheFileWriteErrorNotWriteable(int errorType) {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler(errorType);
        arrangeDefaultRefreshDuration(10);
        // Act
        IntStream.range(0, COUNT_MULTIPLE_CALLS).forEach(i -> {
            arrangeTimerBase1200(Duration.ofMinutes(15).multipliedBy(i + 1));
            arrangeCacheFileWriteErrorFileNotWriteable(); // <<--
            feedsDownloadService.refreshScheduler();
        });
        // Assert
        verify(feedsHttpClient, times(0)).getFeeds(anyString());
        assertEquals(COUNT_MULTIPLE_CALLS, countLogging(CACHE_IS_NOT_VALID));
        assertEquals(0, countLogging(NEW_OVERALL_DELAY));
    }

    @ParameterizedTest()
    @ValueSource(ints = {0, 1, 2}) // 0=none, 1=httpClient, 2=processing
    void refreshScheduler_callMultipleCacheFileWriteErrorNoSpaceLeft(int errorType) {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler(errorType);
        arrangeDefaultRefreshDuration(10);
        try (MockedStatic<Files> mockedFiles = mockStatic(Files.class)) {
            mockedFiles.when(() -> Files.writeString(any(Path.class), any(String.class)))
                    .thenThrow(new IOException("No space left on device"));
            // Act
            IntStream.range(0, COUNT_MULTIPLE_CALLS).forEach(i -> {
                arrangeTimerBase1200(Duration.ofMinutes(15).multipliedBy(i + 1));
                feedsDownloadService.refreshScheduler();
            });
        }
        // Assert
        verify(feedsHttpClient, times(getFeedsCount())).getFeeds(anyString());
        assertEquals(COUNT_MULTIPLE_CALLS - 1, countLogging(CACHE_IS_NOT_VALID));
        assertEquals(getGroupsCount(), countLogging(NEW_OVERALL_DELAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2}) // 0=none, 1=httpClient, 2=processing
    void refreshScheduler_callOnceNewInstanceCallAgain(int errorType) {
        // Arrange 1 (call)
        arrangeTimerBase1200(Duration.ofMinutes(0));
        arrangeTestRefreshScheduler(errorType);
        arrangeDefaultRefreshDuration(90);
        // Act 1
        feedsDownloadService.refreshScheduler();
        // Assert 1
        verify(feedsHttpClient, times(getFeedsCount())).getFeeds(anyString());
        assertEquals(getGroupsCount(), countLogging(NEW_OVERALL_DELAY));

        // Arrange 2 (skip call)
        // new instance
        feedsDownloadService.init();
        arrangeTimerBase1200(Duration.ofMinutes(30));
        // Act 2
        feedsDownloadService.refreshScheduler();
        // Assert 2
        verify(feedsHttpClient, times(getFeedsCount())).getFeeds(anyString());
        assertEquals(getGroupsCount(), countLogging(NEW_OVERALL_DELAY));

        // Arrange 3 (call again)
        // new instance
        arrangeTimerBase1200(Duration.ofMinutes(95));
        // Act 3
        feedsDownloadService.refreshScheduler();
        // Assert 3
        verify(feedsHttpClient, times(getFeedsCount() * 2)).getFeeds(anyString());
        assertEquals(getGroupsCount() * 2, countLogging(NEW_OVERALL_DELAY ));
    }

    @ParameterizedTest
    @ValueSource(ints = {0}) // 0=none
    void refreshScheduler_startupDelay(int errorType) throws InterruptedException {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler(errorType);
        feedsConfigService.overwriteStartupDelayMinutes(1L);
        // Act
        feedsDownloadService.refreshScheduler();
        Thread.sleep(1500);
        feedsDownloadService.refreshScheduler();
        // Assert
        assertEquals(2, countLogging(STARTUP_DELAY));
        verify(feedsHttpClient, times(0)).getFeeds(anyString());
        assertEquals(0, countLogging(NEW_OVERALL_DELAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {0}) // 0=none
    void refreshScheduler_ttl(int errorType) {
        // Arrange
        arrangeTimerBase1200(Duration.ofSeconds(0));
        arrangeTestRefreshScheduler(errorType);
        // Act
        feedsDownloadService.refreshScheduler();
        // Assert
        @AllArgsConstructor
        class TtlTestData {
            String group;
            String feed;
            long ttlMinutes;
        }
        var ttlTestDataList = new LinkedList<TtlTestData>();
        ttlTestDataList.add(new TtlTestData("ExampleGroup1", "example_G1A", 1));
        ttlTestDataList.add(new TtlTestData("ExampleGroup1", "example_G1B", 2));
        ttlTestDataList.add(new TtlTestData("ExampleGroup1", "example_G1C", 3));
        ttlTestDataList.add(new TtlTestData("ExampleGroup2", "example_G2D", 4));
        ttlTestDataList.add(new TtlTestData("ExampleGroup2", "example_G2E", 5));
        ttlTestDataList.add(new TtlTestData("ExampleGroup2", "example_G2F", 6));
        ttlTestDataList.add(new TtlTestData("ExampleGroup2", "example_G2G", 11));
        ttlTestDataList.add(new TtlTestData("ExampleGroup2", "example_G2H", 14));
        ttlTestDataList.forEach(td -> {
            var actual = FeedsCache.getInstance().lookupGroup(td.group).getGroupFeeds().get(td.feed).getTtl().getTtl().toMinutes();
            assertEquals(td.ttlMinutes, actual, td.feed);
        });
    }

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
    private String minimalFeed(){
        Channel channel = new Channel();
        channel.setFeedType("rss_2.0");
        channel.setTitle("Title");
        channel.setDescription("processed");
        channel.setLink("http://localhost");
        return new WireFeedOutput().outputString(channel);
    }

    private void arrangeTimerBase1200(TemporalAmount temporalAmount) {
        LocalDateTime base = LocalDateTime.of(2025, 1, 1, 12, 0);
        testLocalDateTime = base.plus(temporalAmount);
        testLocalTime = base.toLocalTime().plus(temporalAmount);
        testZonedDateTime = base.plus(temporalAmount).atZone(ZoneId.systemDefault());
        lenient().when(feedsTimer.localDateTimeNow()).thenReturn(testLocalDateTime);
        lenient().when(feedsTimer.localTimeNow()).thenReturn(testLocalTime);
        lenient().when(feedsTimer.zonedDateTimeNow()).thenReturn(testZonedDateTime);

    }

    private void arrangeTestRefreshScheduler(int errorType) {
        feedsDownloadService.init();
        lenient().when(feedsHttpClient.getFeeds(anyString()))
                .thenAnswer(invocation -> {
                    if(errorType==1){
                        throw new RuntimeException("test exception httpclient");
                    }
                    URL url = new URL(invocation.getArgument(0));
                    var key = StringUtils.substringAfter(url.getQuery(), "key=");
                    return new ExampleController(feedsTimer).getFeedResponse(key);
                });

        if(errorType == 2){
            lenient().when(feedsProcessingService.processFeed(any(), any())).thenThrow(new RuntimeException("test exception processing"));
        }else {
            lenient().when(feedsProcessingService.processFeed(any(), any())).thenReturn(minimalFeed());
        }
    }

    private void arrangeDefaultRefreshDuration(int minutes) {
        feedsConfigService.getFeedsGroups().forEach(g -> g.setGroupDefaultDurationMinutes(minutes));
    }

    private void arrangeTestCircuitBreaker() {
        feedsDownloadService.feedsDownloadCircuitBreaker = new FeedsTestCircuitBreaker();
    }

    @SneakyThrows
    private void arrangeCacheFileReadError() {
        FileUtils.writeStringToFile(FeedsCache.lookupCacheFile(), "---", StandardCharsets.UTF_8);
    }

    @SneakyThrows
    private void arrangeCacheFileWriteErrorFileNotWriteable() {
        if(!FeedsCache.lookupCacheFile().setWritable(false)){
            throw new RuntimeException("error with arrangeCacheFileWriteError");
        }
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