package com.blockevidence.backend.security;

import java.util.List;

import com.blockevidence.backend.config.CorsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * A1/A3: the HTTP security rules. {@code @EnableMethodSecurity} is the RBAC mechanism: later
 * controllers restrict operations with {@code @PreAuthorize("hasRole('JUDGE')")} and so on. Phase 1
 * ships only the mechanism, not a permission matrix (see ARCHITECTURE.md 4.3).
 *
 * <p>Default-deny: every route requires authentication unless listed as public below.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
            ApiAuthenticationEntryPoint entryPoint, ApiAccessDeniedHandler accessDeniedHandler,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                // Frontend integration: the browser-based frontend is a separate origin (Next.js dev
                // server), so its axios calls need CORS approval before Spring Security even sees them.
                .cors(c -> c.configurationSource(corsConfigurationSource))
                // CSRF protects cookie-based sessions. This API is stateless and reads a bearer
                // token from a header that a cross-site form cannot set, so CSRF has nothing to protect.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh").permitAll()
                        // Status only for anonymous callers; component detail is gated by
                        // management.endpoint.health.show-details=when-authorized (ADMIN), see application.yml.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // K2: the DOCUMENTATION is browsable without a token - trying an operation from the UI
                        // still needs a real one, exactly like any other API client.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Frontend integration: allow-lists the frontend's origin(s) only (never {@code "*"} - this API
     * accepts a bearer token from JS, and a wildcard origin combined with credentials/headers is an
     * open CORS misconfiguration). {@code CorsProperties} defaults to the frontend dev server alone.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // No cookies are used (stateless JWT bearer auth), so credentialed CORS is not needed.
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Used only by AuthService.login: DaoAuthenticationProvider = load user + BCrypt compare. */
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
