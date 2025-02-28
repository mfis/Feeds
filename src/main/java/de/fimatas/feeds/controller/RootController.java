package de.fimatas.feeds.controller;

import de.fimatas.feeds.model.FeedsCache;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RootController {

    @Value("${feeds.rootRedirectUrl}")
    private String rootRedirectUrl;

    @GetMapping("/")
    public String root() {
        return "redirect:" + rootRedirectUrl;
    }


    @GetMapping("api/healthcheck")
    public void healthcheck(HttpServletResponse response) {
        if(FeedsCache.getInstance().isNotValid()){
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }else{
            response.setStatus(HttpServletResponse.SC_OK);
        }
    }
}
