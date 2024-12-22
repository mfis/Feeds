package de.fimatas.feeds.components;

import com.rometools.rome.feed.rss.Channel;
import com.rometools.rome.feed.rss.Item;
import com.rometools.rome.io.WireFeedInput;
import com.rometools.rome.io.WireFeedOutput;
import de.fimatas.feeds.model.FeedsConfig;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class FeedsProcessingService {

    @Autowired
    private FeedsConfigService feedsConfigService;

    @Value("${relevantDescriptionLength}")
    private int relevantDescriptionLength;

    @Value("${externalURL}")
    private String externalURL;

    @SneakyThrows
    public String processFeed(@Nullable String originalFeed, FeedsConfig.FeedConfig feedConfig){

        if(originalFeed == null){
            return null;
        }

        Channel channel = (Channel) new WireFeedInput().build(new InputSource(new ByteArrayInputStream(originalFeed.getBytes(StandardCharsets.UTF_8))));

        var originalLink = channel.getLink();
        var originalDescription = channel.getDescription();
        channel.setLink(externalURL + "/api/feeds/" + feedConfig.getKey());
        channel.setDescription("FILTERED FEED. ORIGINAL LINK = '" + originalLink + "'. ORIGINAL DESCRIPTION = '" + originalDescription + "'.");

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
            String relevantContent = item.getTitle() + StringUtils.SPACE + StringUtils.left(item.getDescription().getValue(), relevantDescriptionLength);
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
