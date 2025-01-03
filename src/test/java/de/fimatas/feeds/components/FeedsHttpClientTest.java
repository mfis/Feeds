package de.fimatas.feeds.components;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class FeedsHttpClientTest {

    private FeedsHttpClient feedsHttpClient;

    @BeforeEach
    void beforeEach() {
        feedsHttpClient = new FeedsHttpClient();
        feedsHttpClient.downloadUrlFuseDuration = Duration.parse("PT2S");
    }

    @Test
    void getFeeds() throws IOException, InterruptedException {

        CloseableHttpClient httpClientMock = mock(CloseableHttpClient.class);
        when(httpClientMock.execute(any(HttpGet.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<String> handler = invocation.getArgument(1);
            ClassicHttpResponse responseMock = new BasicClassicHttpResponse(200);
            HttpEntity entity = new StringEntity("testresponse");
            responseMock.setEntity(entity);
            return handler.handleResponse(responseMock);
        });

        HttpClientBuilder httpClientBuilderMock = mock(HttpClientBuilder.class);
        when(httpClientBuilderMock.setDefaultRequestConfig(any(RequestConfig.class))).thenReturn(httpClientBuilderMock);
        when(httpClientBuilderMock.build()).thenReturn(httpClientMock);

        try (MockedStatic<HttpClients> mockedHttpClients = mockStatic(HttpClients.class)) {
            mockedHttpClients.when(HttpClients::custom).thenReturn(httpClientBuilderMock);
            mockedHttpClients.when(HttpClients::custom).thenReturn(httpClientBuilderMock);
            when(httpClientBuilderMock.setDefaultRequestConfig(any(RequestConfig.class))).thenReturn(httpClientBuilderMock);
            when(httpClientBuilderMock.build()).thenReturn(httpClientMock);

            feedsHttpClient.getFeeds("http://localhost:8080");
            verify(httpClientMock, times(1)).execute(any(HttpGet.class), any(HttpClientResponseHandler.class));

            Exception ex = assertThrows(IllegalStateException.class, () -> feedsHttpClient.getFeeds("http://localhost:8080"));
            assertEquals("too many calls to url: http://localhost:8080", ex.getMessage());
            verify(httpClientMock, times(1)).execute(any(HttpGet.class), any(HttpClientResponseHandler.class));

            Thread.sleep(2010L);
            feedsHttpClient.getFeeds("http://localhost:8080");
            verify(httpClientMock, times(2)).execute(any(HttpGet.class), any(HttpClientResponseHandler.class));
        }

    }
}
