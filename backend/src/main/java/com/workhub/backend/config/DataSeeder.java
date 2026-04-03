package com.workhub.backend.config;

import com.workhub.backend.entity.Plan;
import com.workhub.backend.entity.Role;
import com.workhub.backend.entity.Tenant;
import com.workhub.backend.entity.User;
import com.workhub.backend.repository.TenantRepository;
import com.workhub.backend.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements CommandLineRunner {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(TenantRepository tenantRepository,
                      UserRepository userRepository,
                      PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }

        Tenant acme = tenantRepository.save(
                Tenant.builder()
                        .name("Acme Corp")
                        .plan(Plan.FREE)
                        .build()
        );

        userRepository.save(
                User.builder()
                        .email("admin@acme.com")
                        .passwordHash(passwordEncoder.encode("password123"))
                        .role(Role.TENANT_ADMIN)
                        .tenant(acme)
                        .build()
        );

        userRepository.save(
                User.builder()
                        .email("user@acme.com")
                        .passwordHash(passwordEncoder.encode("password123"))
                        .role(Role.TENANT_USER)
                        .tenant(acme)
                        .build()
        );
    }
}
