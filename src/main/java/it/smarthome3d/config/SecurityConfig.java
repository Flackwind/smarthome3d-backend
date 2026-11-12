package it.smarthome3d.config;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.security.crypto.password.*;
import org.springframework.security.web.context.*;
import org.springframework.web.cors.*;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }
    @Bean SecurityContextRepository contexts() { return new HttpSessionSecurityContextRepository(); }
    @Bean CorsConfigurationSource corsConfigurationSource(@Value("${sh3d.origins}") List<String> origins) {
        var cors = new CorsConfiguration(); cors.setAllowedOrigins(origins); cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-SH3D-CSRF", "If-Match", "If-None-Match"));
        cors.setExposedHeaders(List.of("ETag", "X-Project-Id", "X-Asset-Name", "Content-Disposition"));
        var source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**", cors); return source;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, SecurityContextRepository contexts, CorsConfigurationSource corsConfigurationSource) throws Exception {
        var csrf = new HttpSessionCsrfTokenRepository(); csrf.setHeaderName("X-SH3D-CSRF");
        http.cors(c -> c.configurationSource(corsConfigurationSource)).csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .securityContext(c -> c.securityContextRepository(contexts))
            .authorizeHttpRequests(c -> c.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/v1/session", "/api/v1/auth/setup", "/api/v1/auth/login", "/health", "/error").permitAll()
                .anyRequest().authenticated())
            .formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable())
            .requestCache(c -> c.disable())
            .exceptionHandling(c -> c.authenticationEntryPoint((req,res,e) -> {res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"message\":\"Accedi al server\"}");})
                .accessDeniedHandler((req,res,e) -> {res.setStatus(403);res.setContentType("application/json");res.getWriter().write("{\"message\":\"Sessione o verifica CSRF non valida. Aggiorna l’accesso.\"}");}));
        return http.build();
    }
}
