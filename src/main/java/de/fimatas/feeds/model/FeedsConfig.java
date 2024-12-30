package de.fimatas.feeds.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class FeedsConfig {
    private boolean logStackTrace;
    private String externalURL;
    private List<FeedsGroup> groups;
    private List<Map<String, List<String>>> lists;

    @Data
    public static class FeedsGroup {
        private String groupId;
        private long groupDefaultDurationMinutes;
        private List<FeedConfig> groupFeeds;
    }

    @Data
    public static class FeedConfig {
        private String name;
        private String key;
        private String url;
        private List<String> includeRefs;
        private List<String> excludeRefs;
        private boolean active;
    }
}
