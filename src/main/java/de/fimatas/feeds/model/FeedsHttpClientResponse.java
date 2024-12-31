package de.fimatas.feeds.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class FeedsHttpClientResponse {
    private Map<String, String> headers;
    private String body;
}
