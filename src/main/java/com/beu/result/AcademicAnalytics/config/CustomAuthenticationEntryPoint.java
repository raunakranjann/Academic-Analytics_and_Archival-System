package com.beu.result.AcademicAnalytics.config; // Corrected to match your base package

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {

        // If the request is for an API, return a 401 instead of a redirect to avoid HTML responses in JSON APIs
        String path = request.getServletPath();
        if (path.startsWith("/api/")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
        } else {
            // For browser requests, redirect to the login page
            response.sendRedirect("/login");
        }
    }
}