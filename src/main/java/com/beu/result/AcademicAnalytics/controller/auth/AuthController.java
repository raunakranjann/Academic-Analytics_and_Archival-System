package com.beu.result.AcademicAnalytics.controller.auth;

import com.beu.result.AcademicAnalytics.util.JwtUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class AuthController {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private com.beu.result.AcademicAnalytics.util.JwtUtil jwtUtil;

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    // 1. Web Dashboard Login (Form submission, sets Cookie)
    @PostMapping("/login")
    public String webLogin(@RequestParam String username,
                           @RequestParam String password,
                           HttpServletResponse response) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password)
            );

            final UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            final String jwt = jwtUtil.generateToken(userDetails);

            Cookie jwtCookie = new Cookie("jwt", jwt);
            jwtCookie.setHttpOnly(true);
            jwtCookie.setPath("/");
            jwtCookie.setMaxAge(60 * 60 * 10);
            response.addCookie(jwtCookie);

            return "redirect:/";

        } catch (BadCredentialsException e) {
            return "redirect:/login?error=true";
        } catch (Exception e) {
            return "redirect:/login?error=unknown";
        }
    }

    // 2. Pure API Login (JSON Payload, returns JWT Token)
    @PostMapping("/api/login")
    @ResponseBody
    public ResponseEntity<?> apiLogin(@RequestBody AuthenticationRequest authenticationRequest) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(authenticationRequest.getUsername(), authenticationRequest.getPassword())
            );
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(401).body(new ErrorResponse("Invalid username or password"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new ErrorResponse("An internal error occurred"));
        }

        final UserDetails userDetails = userDetailsService.loadUserByUsername(authenticationRequest.getUsername());
        final String jwt = jwtUtil.generateToken(userDetails);

        return ResponseEntity.ok(new AuthenticationResponse(jwt));
    }
}

class AuthenticationRequest {
    private String username;
    private String password;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}

class AuthenticationResponse {
    private final String jwt;
    public AuthenticationResponse(String jwt) { this.jwt = jwt; }
    public String getJwt() { return jwt; }
}

class ErrorResponse {
    private final String message;
    public ErrorResponse(String message) { this.message = message; }
    public String getMessage() { return message; }
}
