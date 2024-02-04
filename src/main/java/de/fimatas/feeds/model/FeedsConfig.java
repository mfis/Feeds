package de.fimatas.feeds.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class FeedsConfig {
    private List<FeedConfig> feeds;
    private List<Map<String, List<String>>> lists;
}
