package de.fimatas.feeds.components;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.SyndFeedOutput;
import de.fimatas.feeds.model.FeedConfig;
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
    public String processFeed(@Nullable String originalFeed, FeedConfig feedConfig){

        if(originalFeed == null){
            return null;
        }

        SyndFeed syndFeed = new SyndFeedInput()
                .build(new InputSource(new ByteArrayInputStream(originalFeed.getBytes(StandardCharsets.UTF_8))));

        var originalLink = syndFeed.getLink();
        var originalDescription = syndFeed.getDescription();
        syndFeed.setLink(externalURL + "/api/feeds/" + feedConfig.getKey());
        syndFeed.setDescription("FILTERED FEED. ORIGINAL LINK = '" + originalLink + "'. ORIGINAL DESCRIPTION = '" + originalDescription + "'.");

        List<SyndEntry> filteredEntries = processEntries(syndFeed.getEntries(), feedConfig);
        syndFeed.setEntries(filteredEntries);

        SyndFeedOutput output = new SyndFeedOutput();
        try (StringWriter writer = new StringWriter()) {
            output.output(syndFeed, writer);
            return writer.toString();
        }
    }

    private List<SyndEntry> processEntries(List<SyndEntry> entries, FeedConfig feedConfig) {

        List<SyndEntry> processedEntries = new ArrayList<>();
        var excludes = feedsConfigService.getExcludesForFeedConfig(feedConfig);
        var includes = feedsConfigService.getIncludesForFeedConfig(feedConfig);

        for (SyndEntry entry : entries) {
            String relevantContent = entry.getTitle() + StringUtils.SPACE + StringUtils.left(entry.getDescription().getValue(), relevantDescriptionLength);
            if(!excludes.isEmpty() && excludes.stream().anyMatch(excludeString -> StringUtils.containsIgnoreCase(relevantContent, excludeString))){
                continue; // delete
            }
            if(!includes.isEmpty() && includes.stream().noneMatch(includeString -> StringUtils.containsIgnoreCase(relevantContent, includeString))){
                continue; // delete
            }
            processedEntries.add(entry);
        }
        return processedEntries;
    }
}
