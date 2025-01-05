package de.fimatas.feeds.controller;

import com.rometools.rome.feed.rss.Channel;
import com.rometools.rome.feed.rss.Description;
import com.rometools.rome.feed.rss.Item;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.WireFeedOutput;
import de.fimatas.feeds.components.FeedsTimer;
import de.fimatas.feeds.model.FeedsHttpClientResponse;
import de.fimatas.feeds.util.FeedsUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import lombok.extern.apachecommons.CommonsLog;
import org.jdom2.Namespace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;

@Controller
@RequestMapping("/example")
@CommonsLog
public class ExampleController {

    @Value("${feeds.useTestConfig:true}")
    public boolean useTestConfig;

    private final FeedsTimer feedsTimer;

    public ExampleController(FeedsTimer feedsTimer){
        super();
        this.feedsTimer = feedsTimer;
    }

    @GetMapping
    @ResponseBody
    public void getDataFromExternalApi(HttpServletResponse response,
                                       @RequestParam(name = "key", required = false) String key) throws IOException, FeedException {

        if(!useTestConfig){
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        final var feedsResponse = getFeedResponse(key);
        response.setStatus(feedsResponse.getStatusCode());
        response.setContentType("application/rss+xml");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME.format(feedsTimer.zonedDateTimeNow()));
        feedsResponse.getHeaders().forEach(response::addHeader);
        if(feedsResponse.getBody() != null) {
            response.getWriter().print(feedsResponse.getBody());
        }
    }

    @SneakyThrows
    public FeedsHttpClientResponse getFeedResponse(String key) {

        var response = new FeedsHttpClientResponse();
        response.setStatusCode(HttpServletResponse.SC_OK);
        response.setHeaders(new HashMap<>());

        if(key.equals("example_X")){
            response.setStatusCode(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return response;
        }

        Channel channel = new Channel();

        if(key.equals("example_A")){
            channel.setTtl(1); // minutes
        }
        if(key.equals("example_B")){
            response.getHeaders().put("Cache-Control", "Cache-control: max-age=120, public"); // seconds
        }
        if(key.equals("example_C")){
            response.getHeaders().put("Cache-Control", "Cache-control: max-age=180"); // seconds
        }
        if(key.equals("example_D")){
            response.getHeaders().put("Retry-After", "240"); // seconds
        }
        if(key.equals("example_E")){
            response.getHeaders().put("Retry-After", DateTimeFormatter.RFC_1123_DATE_TIME.format(feedsTimer.zonedDateTimeNow().plusSeconds(310))); // seconds
        }

        if(key.equals("example_F")){
            Namespace syNamespace = Namespace.getNamespace("sy", "http://localhost");
            channel.getForeignMarkup().add(FeedsUtil.createElement("updatePeriod", "hourly", syNamespace));
            channel.getForeignMarkup().add(FeedsUtil.createElement("updateFrequency", "10", syNamespace));
        }

        if(key.equals("example_G")){
            Namespace syNamespace = Namespace.getNamespace("sy", "http://localhost");
            channel.getForeignMarkup().add(FeedsUtil.createElement("updatePeriod", "hourly", syNamespace));
            channel.getForeignMarkup().add(FeedsUtil.createElement("updateFrequency", "6", syNamespace));
            channel.getForeignMarkup().add(FeedsUtil.createElement("updateBase", DateTimeFormatter.ISO_DATE_TIME.format(feedsTimer.zonedDateTimeNow().plusMinutes(1)), syNamespace));
        }

        channel.setFeedType("rss_2.0");
        channel.setTitle(key);
        channel.setDescription("ExampleFeed description");
        channel.setLink("http://localhost:8081/example");

        Item entry1 = new Item();
        entry1.setTitle("Example title 1 - " + key);
        entry1.setLink("https://localhost:8081/example/entry/1");
        entry1.setPubDate(new Date());
        var content1 = new Description();
        content1.setValue("This is content1");
        entry1.setDescription(content1);

        Item entry2 = new Item();
        entry2.setTitle("Example title 2 - " + key);
        entry2.setLink("https://localhost:8081/example/entry/2");
        entry2.setPubDate(new Date());
        var content2 = new Description();
        content2.setValue("This is content2");
        entry2.setDescription(content2);

        channel.getItems().add(entry1);
        channel.getItems().add(entry2);

        response.setBody(new WireFeedOutput().outputString(channel));
        return response;
    }

    @GetMapping("/entry/{key}")
    @ResponseBody
    public String getEntry(@PathVariable String key) {
        if (key.equals("1") || key.equals("2")) {
            return "--" + key + "--";
        }
        return "--unknown--";
    }

}
