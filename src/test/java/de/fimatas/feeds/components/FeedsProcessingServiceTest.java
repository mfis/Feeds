package de.fimatas.feeds.components;

import de.fimatas.feeds.controller.ExampleController;
import de.fimatas.feeds.model.FeedsConfig;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static de.fimatas.feeds.components.FeedsProcessingService.ORIGINAL_DESCRIPTION;
import static de.fimatas.feeds.components.FeedsProcessingService.THE_ITEMS_OF_THIS_FEED_WERE_FILTERED_BY;
import static org.junit.jupiter.api.Assertions.*;

public class FeedsProcessingServiceTest {

    private FeedsProcessingService feedsProcessingService;

    private FeedsConfigService feedsConfigService;

    private ExampleController exampleController;

    @BeforeEach
    void beforeEach() {
        FeedsTimer feedsTimer = new FeedsTimer();
        exampleController = new ExampleController(feedsTimer);
        exampleController.useTestConfig = true;
        feedsConfigService = new FeedsConfigService();
        feedsConfigService.useTestConfig = true;
        feedsConfigService.startupDelayMinutes = 0;
        feedsConfigService.logStackTrace = false;
        feedsProcessingService = new FeedsProcessingService(feedsConfigService);
        feedsProcessingService.relevantDescriptionLength = 5000;
    }

    @Test
    void getFeeds() {
        feedsConfigService.getFeedsGroups().forEach(gc -> gc.getGroupFeeds().forEach(fc -> {
            var key = StringUtils.substringAfter(fc.getUrl(), "key=");
            if(!key.equals("example_X")){
                testFeed(fc, key);
            }
        }));
    }

    private void testFeed(FeedsConfig.FeedConfig fc, String key) {

        var originalFeedObject = exampleController.getFeedResponse(key);
        var originalFeed = originalFeedObject.getBody();
        var processedFeed = feedsProcessingService.processFeed(originalFeedObject, fc);

        var originalDescription = getDescription(originalFeed);
        var processedDescription = getDescription(processedFeed);
        var originalFeedWithoutDescriptionAndItems = removeItems(removeDescription(originalFeed));
        var processedFeedWithoutDescriptionAndItems = removeItems(removeDescription(processedFeed));

        assertTrue(processedDescription.contains(originalDescription));
        assertTrue(processedDescription.contains(THE_ITEMS_OF_THIS_FEED_WERE_FILTERED_BY));
        assertTrue(processedDescription.contains(ORIGINAL_DESCRIPTION));
        assertEquals(originalFeedWithoutDescriptionAndItems, processedFeedWithoutDescriptionAndItems);
        if(key.equals("example_A") || key.equals("example_D")){ // no includes, excludes
            assertEquals(removeDescription(originalFeed), removeDescription(processedFeed));
        }

        // TODO: Test includes, excludes
    }

    private static String removeDescription(String feed) {
        return feed
                .replaceAll("<description>(.*?)</description>", "")
                .replaceAll("[\\r\\n\\t ]+", "");
    }

    private static String removeItems(String feed) {
        return feed
                .replaceAll("<item\\b[^>]*>([\\s\\S]*?)</item>", "")
                .replaceAll("[\\r\\n\\t ]+", "");
    }

    private static String getDescription(String feed) {
        return StringUtils.substringBetween(feed, "<description>", "</description>");
    }
}
