package de.fimatas.feeds.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FeedsHttpClientResponse {
    private Map<String, String> headers;
    private int statusCode;
    private String body;
}
