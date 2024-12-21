package de.fimatas.feeds.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Duration;

@Data
@AllArgsConstructor
public class TtlInfo {
    private Duration ttl;
    private String source;
}
