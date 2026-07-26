package com.omnissa.access.approval.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Forwards SPA client-side routes to index.html so React Router handles them.
 * API routes and static assets are matched first by other controllers / resource handlers.
 */
@Controller
public class SpaController {

    /**
     * Client-side routes, forwarded to the SPA shell so a deep link, refresh or
     * bookmark works rather than 404ing.
     *
     * <p><strong>This list must mirror the routes in {@code App.tsx}.</strong>
     * They are separate declarations of the same thing, and adding a page to one
     * without the other produces a route that works when navigated to in-app but
     * 404s on refresh — which is how {@code /users} shipped broken.
     */
    @RequestMapping(value = {"/", "/login", "/dashboard", "/queue", "/requests/**",
            "/rules", "/users", "/settings/**", "/help"})
    public String spa() {
        return "forward:/index.html";
    }
}
