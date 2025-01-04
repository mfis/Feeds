package de.fimatas.feeds.components;

import com.rometools.rome.feed.rss.Channel;
import com.rometools.rome.feed.rss.Item;
import com.rometools.rome.io.WireFeedInput;
import com.rometools.rome.io.WireFeedOutput;
import de.fimatas.feeds.model.FeedsConfig;
import de.fimatas.feeds.model.FeedsHttpClientResponse;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.xml.sax.InputSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class FeedsProcessingService {

    public FeedsProcessingService(FeedsConfigService feedsConfigService) {
        this.feedsConfigService = feedsConfigService;
    }

    private final FeedsConfigService feedsConfigService;

    @Value("${feeds.relevantDescriptionLength}")
    private int relevantDescriptionLength;

    @SneakyThrows
    public String processFeed(FeedsHttpClientResponse originalFeed, FeedsConfig.FeedConfig feedConfig){

        if(originalFeed == null){
            return null;
        }

        Channel channel = (Channel) new WireFeedInput()
                .build(new InputSource(new ByteArrayInputStream(originalFeed.getBody().getBytes(StandardCharsets.UTF_8))));

        var originalDescription = channel.getDescription();
        channel.setDescription("THE ITEMS OF THIS FEED WERE FILTERED BY '" + feedsConfigService.getExternalURL() + "'. ORIGINAL DESCRIPTION = '" + originalDescription + "'.");

        List<Item> filteredEntries = processEntries(channel.getItems(), feedConfig);
        channel.setItems(filteredEntries);

        WireFeedOutput output = new WireFeedOutput();
        try (StringWriter writer = new StringWriter()) {
            output.output(channel, writer);
            return writer.toString();
        }
    }

    private List<Item> processEntries(List<Item> entries, FeedsConfig.FeedConfig feedConfig) {

        List<Item> processedEntries = new ArrayList<>();
        var excludes = feedsConfigService.getExcludesForFeedConfig(feedConfig);
        var includes = feedsConfigService.getIncludesForFeedConfig(feedConfig);

        for (Item item : entries) {
            String relevantContent;
                 relevantContent = StringUtils.trimToEmpty(
                         StringUtils.trimToEmpty(item.getTitle()) +
                         StringUtils.SPACE +
                         StringUtils.trimToEmpty(item.getDescription() == null ? null : StringUtils.left(item.getDescription().getValue(), relevantDescriptionLength))
                 );
            if(!excludes.isEmpty() && excludes.stream().anyMatch(excludeString -> StringUtils.containsIgnoreCase(relevantContent, excludeString))){
                continue; // delete
            }
            if(!includes.isEmpty() && includes.stream().noneMatch(includeString -> StringUtils.containsIgnoreCase(relevantContent, includeString))){
                continue; // delete
            }
            processedEntries.add(item);
        }
        return processedEntries;
    }
}
