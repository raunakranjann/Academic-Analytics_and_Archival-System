package com.beu.result.AcademicAnalytics.config;

import com.beu.result.AcademicAnalytics.entity.Role;
import com.beu.result.AcademicAnalytics.entity.User;
import com.beu.result.AcademicAnalytics.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class DataSeeder implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${admin.username}")
    private String adminUsername;

    @Value("${admin.password}")
    private String adminPassword;

    @Value("${admin.fullname}")
    private String adminFullName;

    @Override
    public void run(String... args) throws Exception {
        if (userRepository.findByUsername(adminUsername).isEmpty()) {
            User admin = new User();
            admin.setUsername(adminUsername);
            admin.setFullName(adminFullName);
            admin.setPassword(passwordEncoder.encode(adminPassword));
            admin.setRoles(Set.of(Role.ADMIN));
            userRepository.save(admin);
            System.out.println("Default admin user created: " + adminUsername);
        }
    }
}
