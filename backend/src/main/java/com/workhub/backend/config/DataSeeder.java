package com.workhub.backend.config;

import com.workhub.backend.entity.Plan;
import com.workhub.backend.entity.Role;
import com.workhub.backend.entity.Tenant;
import com.workhub.backend.entity.User;
import com.workhub.backend.repository.TenantRepository;
import com.workhub.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements CommandLineRunner {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String tenantName;
    private final Plan tenantPlan;
    private final String adminEmail;
    private final String adminPassword;
    private final String userEmail;
    private final String userPassword;
    private final String user2Email;
    private final String user2Password;
    private final String tenant2Name;
    private final Plan tenant2Plan;
    private final String tenant2AdminEmail;
    private final String tenant2AdminPassword;
    private final String tenant2UserEmail;
    private final String tenant2UserPassword;

    public DataSeeder(TenantRepository tenantRepository,
                      UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      @Value("${app.seed.tenant.name}") String tenantName,
                      @Value("${app.seed.tenant.plan}") Plan tenantPlan,
                      @Value("${app.seed.admin.email}") String adminEmail,
                      @Value("${app.seed.admin.password}") String adminPassword,
                      @Value("${app.seed.user.email}") String userEmail,
                      @Value("${app.seed.user.password}") String userPassword,
                      @Value("${app.seed.user2.email}") String user2Email,
                      @Value("${app.seed.user2.password}") String user2Password,
                      @Value("${app.seed.tenant2.name}") String tenant2Name,
                      @Value("${app.seed.tenant2.plan}") Plan tenant2Plan,
                      @Value("${app.seed.tenant2.admin.email}") String tenant2AdminEmail,
                      @Value("${app.seed.tenant2.admin.password}") String tenant2AdminPassword,
                      @Value("${app.seed.tenant2.user.email}") String tenant2UserEmail,
                      @Value("${app.seed.tenant2.user.password}") String tenant2UserPassword) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tenantName = tenantName;
        this.tenantPlan = tenantPlan;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.userEmail = userEmail;
        this.userPassword = userPassword;
        this.user2Email = user2Email;
        this.user2Password = user2Password;
        this.tenant2Name = tenant2Name;
        this.tenant2Plan = tenant2Plan;
        this.tenant2AdminEmail = tenant2AdminEmail;
        this.tenant2AdminPassword = tenant2AdminPassword;
        this.tenant2UserEmail = tenant2UserEmail;
        this.tenant2UserPassword = tenant2UserPassword;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }

        Tenant acme = tenantRepository.save(
                Tenant.builder()
                        .name(tenantName)
                        .plan(tenantPlan)
                        .build()
        );

        userRepository.save(
                User.builder()
                        .email(adminEmail)
                        .passwordHash(passwordEncoder.encode(adminPassword))
                        .role(Role.TENANT_ADMIN)
                        .tenant(acme)
                        .build()
        );

        userRepository.save(
                User.builder()
                        .email(userEmail)
                        .passwordHash(passwordEncoder.encode(userPassword))
                        .role(Role.TENANT_USER)
                        .tenant(acme)
                        .build()
        );

        userRepository.save(
                User.builder()
                        .email(user2Email)
                        .passwordHash(passwordEncoder.encode(user2Password))
                        .role(Role.TENANT_USER)
                        .tenant(acme)
                        .build()
        );

        Tenant globex = tenantRepository.save(
                Tenant.builder()
                        .name(tenant2Name)
                        .plan(tenant2Plan)
                        .build()
        );

        userRepository.save(
                User.builder()
                        .email(tenant2AdminEmail)
                        .passwordHash(passwordEncoder.encode(tenant2AdminPassword))
                        .role(Role.TENANT_ADMIN)
                        .tenant(globex)
                        .build()
        );

        userRepository.save(
                User.builder()
                        .email(tenant2UserEmail)
                        .passwordHash(passwordEncoder.encode(tenant2UserPassword))
                        .role(Role.TENANT_USER)
                        .tenant(globex)
                        .build()
        );
    }
}
