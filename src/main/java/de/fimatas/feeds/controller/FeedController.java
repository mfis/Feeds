package de.fimatas.feeds.controller;

import de.fimatas.feeds.components.FeedsConfigService;
import de.fimatas.feeds.model.FeedsCache;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.apachecommons.CommonsLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;

@Controller
@RequestMapping("/api/feeds")
@CommonsLog
public class FeedController {

    @GetMapping("/{key}")
    @ResponseBody
    public void getFeed(@PathVariable String key, HttpServletResponse response) throws IOException {

        final FeedsCache.FeedCacheEntry feedCacheEntry = FeedsCache.getInstance().lookupFeed(key);
        if(feedCacheEntry == null){
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            log.info("getFeed '" + key + "' NOT_FOUND");
            return;
        }
        if(!feedCacheEntry.hasActualContent()){
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            log.info("getFeed '" + key + "' SERVICE_UNAVAILABLE");
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(buildContentTypeHeaderField(feedCacheEntry));
        response.setHeader("Last-Modified", buildLastModifiedHeaderField(feedCacheEntry));
        response.getWriter().print(feedCacheEntry.getContent());
    }

    private static String buildLastModifiedHeaderField(FeedsCache.FeedCacheEntry feedCacheEntry) {
        if(feedCacheEntry.getHeaderLastModified() != null){
            return feedCacheEntry.getHeaderLastModified();
        }else{
            return Long.toString(System.currentTimeMillis());
        }
    }

    private static String buildContentTypeHeaderField(FeedsCache.FeedCacheEntry feedCacheEntry) {
        if(feedCacheEntry.getHeaderContentType() != null){
                return feedCacheEntry.getHeaderContentType();
        }else{
            return "application/xml";
        }
    }
}
