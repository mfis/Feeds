package de.fimatas.feeds.configuration;

import de.fimatas.feeds.components.FeedsConfigService;
import de.fimatas.feeds.components.FeedsDownloadService;
import de.fimatas.feeds.components.FeedsProcessingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeedsConfiguration {

    @Bean
    public FeedsConfigService feedsConfigService() {
        return new FeedsConfigService();
    }

    @Bean
    public FeedsProcessingService feedsProcessingService() {
        return new FeedsProcessingService(feedsConfigService());
    }

    @Bean
    public FeedsDownloadService feedsDownloadService() {
        return new FeedsDownloadService(feedsConfigService(), feedsProcessingService());
    }
}
