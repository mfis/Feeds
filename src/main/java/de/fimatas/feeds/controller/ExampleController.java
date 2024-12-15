package de.fimatas.feeds.controller;

import com.rometools.rome.feed.rss.Channel;
import com.rometools.rome.feed.rss.Description;
import com.rometools.rome.feed.rss.Item;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.WireFeedOutput;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.apachecommons.CommonsLog;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

@Controller
@RequestMapping("/example")
@CommonsLog
public class ExampleController {

    @GetMapping
    @ResponseBody
    public void getDataFromExternalApi(HttpServletResponse response,
                                       @RequestParam(name = "key") String key) throws IOException, FeedException {

        Channel channel = new Channel();

        if(key.equals("example_A")){
            channel.setTtl(10); // minutes
        }
        if(key.equals("example_B")){
            response.setHeader("Cache-Control", "Cache-control: max-age=1200, public"); // seconds
        }
        if(key.equals("example_C")){
            response.setHeader("Cache-Control", "Cache-control: max-age=1200"); // seconds
        }
        if(key.equals("example_D")){
            response.setHeader("Retry-After", "2400"); // seconds
        }
        if(key.equals("example_E")){
            response.setHeader("Retry-After", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().plusSeconds(2400))); // seconds
        }

        channel.setFeedType("rss_2.0");
        channel.setTitle(key);
        channel.setDescription("ExampleFeed description");
        channel.setLink("http://localhost:8081/example");

        Item entry1 = new Item();
        entry1.setTitle("Example title 1");
        entry1.setLink("https://localhost:8081/example/entry/1");
        entry1.setPubDate(new Date());
        var content1 = new Description();
        content1.setValue("This is content1");
        entry1.setDescription(content1);

        Item entry2 = new Item();
        entry2.setTitle("Example title 2");
        entry2.setLink("https://localhost:8081/example/entry/2");
        entry2.setPubDate(new Date());
        var content2 = new Description();
        content2.setValue("This is content2");
        entry2.setDescription(content2);

        channel.getItems().add(entry1);
        channel.getItems().add(entry2);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/rss+xml");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Last-Modified", Long.toString(System.currentTimeMillis()));
        response.getWriter().print(new WireFeedOutput().outputString(channel));
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
