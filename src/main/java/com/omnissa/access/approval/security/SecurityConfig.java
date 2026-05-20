package com.omnissa.access.approval.security;

import com.omnissa.access.approval.repository.UserAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Omnissa Access POSTs callout requests here — must be unauthenticated
                .requestMatchers(HttpMethod.POST, "/api/approvals/new").permitAll()
                // OpenAPI / Swagger UI
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                // Health probe for Docker
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults())
            .headers(headers -> headers
                // HSTS — tell browsers to always use HTTPS for this host (2 years)
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(63072000)
                    .preload(true)
                )
                // Prevent clickjacking
                .frameOptions(frame -> frame.deny())
                // Stop browsers from MIME-sniffing
                .contentTypeOptions(Customizer.withDefaults())
                // Referrer policy
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                )
            );
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            var user = userAccountRepository.findByUsername(username);
            if (user == null) throw new UsernameNotFoundException("User not found: " + username);
            return user;
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
