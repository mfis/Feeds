package de.fimatas.feeds.model;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

public interface FeedsCircuitBreaker {
    CircuitBreaker getCircuitBreaker(FeedsConfig.FeedConfig feedConfig);
}
