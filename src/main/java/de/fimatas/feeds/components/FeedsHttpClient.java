package de.fimatas.feeds.components;

import de.fimatas.feeds.model.FeedsHttpClientResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.apachecommons.CommonsLog;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@CommonsLog
public class FeedsHttpClient {

    private final Map<String, LocalDateTime> lastUrlRequestCall = new HashMap<>();

    @Value("${feeds.downloadTimeoutSeconds}")
    private int downloadTimeoutSeconds;

    @Value("${feeds.downloadUrlFuseDuration}")
    protected Duration downloadUrlFuseDuration;

    @PostConstruct
    private void init() {
        log.info("downloadUrlFuseDuration Minutes: " + downloadUrlFuseDuration.toMinutes());
    }

    public FeedsHttpClientResponse getFeeds(String url) {
        if(lastUrlRequestCall.containsKey(url)){
            if(lastUrlRequestCall.get(url).plus(downloadUrlFuseDuration).isAfter(LocalDateTime.now())){
                throw new IllegalStateException("too many calls to url: " + url);
            }
        }
        lastUrlRequestCall.put(url, LocalDateTime.now());
        return doRequest(url);
    }

    private FeedsHttpClientResponse doRequest(String url) {

        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(downloadTimeoutSeconds))
                .setRedirectsEnabled(true)
                .build();

        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build()) {

            HttpGet request = new HttpGet(url);

            return client.execute(request, response -> {
                int statusCode = response.getCode();
                if (statusCode != org.apache.hc.core5.http.HttpStatus.SC_OK) {
                    throw new RuntimeException("HTTP Status Code: " + statusCode);
                }
                var headers = new HashMap<String, String>();
                for (var header : response.getHeaders()) {
                    headers.put(header.getName(), header.getValue());
                }
                return new FeedsHttpClientResponse(headers, statusCode, EntityUtils.toString(response.getEntity()));
            });

        } catch (IOException e) {
            throw new RuntimeException("error getting feed: " + e.getMessage(), e);
        }
    }
}
