package de.fimatas.feeds.configuration;

import de.fimatas.feeds.components.*;
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
    public FeedsHttpClient feedsHttpClient() {
        return new FeedsHttpClient();
    }

    @Bean
    public FeedsTimer feedsTimer() {
        return new FeedsTimer();
    }

    @Bean
    public FeedsDownloadService feedsDownloadService() {
        return new FeedsDownloadService(feedsConfigService(), feedsProcessingService(), feedsHttpClient(), feedsTimer());
    }
}
