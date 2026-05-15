package com.wex.currency.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the single-page UI. The page calls the REST API via fetch. */
@Controller
public class WebController {

    @GetMapping("/")
    public String index() {
        return "index";
    }
}
