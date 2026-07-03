package com.omnissa.access.approval.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Forwards SPA client-side routes to index.html so React Router handles them.
 * API routes and static assets are matched first by other controllers / resource handlers.
 */
@Controller
public class SpaController {

    @RequestMapping(value = {"/", "/login", "/dashboard", "/queue", "/requests/**", "/rules", "/settings/**", "/help"})
    public String spa() {
        return "forward:/index.html";
    }
}
