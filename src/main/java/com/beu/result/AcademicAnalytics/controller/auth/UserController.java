package com.beu.result.AcademicAnalytics.controller.auth;

import com.beu.result.AcademicAnalytics.entity.Role;
import com.beu.result.AcademicAnalytics.entity.User;
import com.beu.result.AcademicAnalytics.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;

import java.util.Set;

@Controller
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @GetMapping("/user-management")
    @PreAuthorize("hasAuthority('ADMIN')")
    public String userManagement(Model model) {
        model.addAttribute("users", userRepository.findAll());
        return "user-management";
    }

    @PostMapping("/api/users")
    @PreAuthorize("hasAuthority('ADMIN')")
    @ResponseBody
    public ResponseEntity<?> createUser(@RequestBody UserCreateRequest userCreateRequest) {
        User user = new User();
        user.setUsername(userCreateRequest.getUsername());
        user.setFullName(userCreateRequest.getFullName());
        user.setPassword(passwordEncoder.encode(userCreateRequest.getPassword()));
        user.setRoles(userCreateRequest.getRoles());
        userRepository.save(user);
        return ResponseEntity.ok("User created successfully");
    }

    @DeleteMapping("/api/users/{id}")
    @PreAuthorize("hasAuthority('ADMIN')")
    @ResponseBody
    public ResponseEntity<?> deleteUser(@PathVariable Long id) {
        userRepository.deleteById(id);
        return ResponseEntity.ok("User deleted successfully");
    }
}

class UserCreateRequest {
    private String username;
    private String fullName;
    private String password;
    private Set<Role> roles;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }
}
