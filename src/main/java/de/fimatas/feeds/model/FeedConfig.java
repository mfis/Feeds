package de.fimatas.feeds.model;

import lombok.Data;

import java.util.List;

@Data
public class FeedConfig {
    private String name;
    private String key;
    private String url;
    private List<String> includeRefs;
    private List<String> excludeRefs;
}
