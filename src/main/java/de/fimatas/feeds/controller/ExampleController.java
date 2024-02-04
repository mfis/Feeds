package de.fimatas.feeds.controller;

import com.rometools.rome.feed.synd.*;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedOutput;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.apachecommons.CommonsLog;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.util.Date;

@Controller
@RequestMapping("/example")
@CommonsLog
public class ExampleController {

    @GetMapping
    @ResponseBody
    public void getDataFromExternalApi(HttpServletResponse response) throws IOException, FeedException {

        SyndFeed feed = new SyndFeedImpl();
        feed.setFeedType("rss_2.0");
        feed.setTitle("ExampleFeed");
        feed.setDescription("ExampleFeed description");
        feed.setLink("https://feeds.fimatas.de/example");

        SyndEntry entry1 = new SyndEntryImpl();
        entry1.setTitle("Example title 1");
        entry1.setLink("https://feeds.fimatas.de/example/1");
        entry1.setPublishedDate(new Date());
        var content1 = new SyndContentImpl();
        content1.setValue("This is content1");
        entry1.setDescription(content1);

        SyndEntry entry2 = new SyndEntryImpl();
        entry2.setTitle("Example title 2");
        entry1.setLink("https://feeds.fimatas.de/example/2");
        entry2.setPublishedDate(new Date());
        var content2 = new SyndContentImpl();
        content2.setValue("This is content2");
        entry2.setDescription(content2);

        feed.getEntries().add(entry1);
        feed.getEntries().add(entry2);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/rss+xml");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Last-Modified", Long.toString(System.currentTimeMillis()));
        response.getWriter().print(new SyndFeedOutput().outputString(feed));
    }

    @GetMapping("/{key}")
    @ResponseBody
    public String getEntry(@PathVariable String key) {
        if (key.equals("1") || key.equals("2")) {
            return "--" + key + "--";
        }
        return "--unknown--";
    }
}
