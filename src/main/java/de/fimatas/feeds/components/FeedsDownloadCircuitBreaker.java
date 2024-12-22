package de.fimatas.feeds.components;

import de.fimatas.feeds.model.FeedsConfig;
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FeedsDownloadCircuitBreaker {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final Map<String, CircuitBreaker> circuitBreakerMap = new ConcurrentHashMap<>();

    public FeedsDownloadCircuitBreaker() {
        CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(80)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofHours(5))
                .permittedNumberOfCallsInHalfOpenState(1)
                .minimumNumberOfCalls(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(defaultConfig);
    }

    public CircuitBreaker getCircuitBreaker(FeedsConfig.FeedConfig feedConfig) {
        return circuitBreakerMap.computeIfAbsent(feedConfig.getKey(),
                key -> circuitBreakerRegistry.circuitBreaker("FeedsDownloadCircuitBreaker-" + key));
    }
}
