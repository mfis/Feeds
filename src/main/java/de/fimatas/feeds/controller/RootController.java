package de.fimatas.feeds.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RootController {

    @Value("${rootRedirectUrl}")
    private String rootRedirectUrl;

    @GetMapping("/")
    public String root() {
        return "redirect:" + rootRedirectUrl;
    }
}
