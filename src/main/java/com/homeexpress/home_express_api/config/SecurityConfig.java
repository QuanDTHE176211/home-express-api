package com.homeexpress.home_express_api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import org.springframework.beans.factory.annotation.Value;
import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Value("${cors.allowed-methods}")
    private List<String> allowedMethods;

    @Value("${cors.allowed-headers}")
    private List<String> allowedHeaders;

    @Value("${cors.exposed-headers}")
    private List<String> exposedHeaders;

    @Value("${cors.allow-credentials}")
    private Boolean allowCredentials;

    @Value("${cors.max-age-seconds}")
    private Long maxAgeSeconds;

    @Value("${security.password-encoder.bcrypt-strength}")
    private Integer bcryptStrength;

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // Tat CSRF cho API
                .cors(cors -> cors.configurationSource(corsConfigurationSource())) // Bat CORS
                .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // Cho phep tat ca OPTIONS requests (CORS preflight)
                .requestMatchers("/api/v1/auth/**").permitAll() // Cho phep truy cap cac endpoint auth khong can login
                .requestMatchers("/api/v1/map/**").permitAll() // Cho phep truy cap map api (autocomplete)
                .requestMatchers("/swagger-ui/**").permitAll()
                .requestMatchers("/v3/api-docs/**").permitAll()
                .requestMatchers("/v3/api-docs/swagger-config").permitAll()
                .anyRequest().authenticated() // Cac endpoint khac can phai login
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class) // Them filter JWT truoc user/pass filter
                .httpBasic(httpBasic -> httpBasic.disable()) // Tat basic auth
                .formLogin(form -> form.disable()); // Tat form login

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(bcryptStrength); // Su dung BCrypt de hash password
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins); // Load tu application.yml
        configuration.setAllowedMethods(allowedMethods);
        configuration.setAllowedHeaders(allowedHeaders);
        configuration.setExposedHeaders(exposedHeaders);
        configuration.setAllowCredentials(allowCredentials);
        configuration.setMaxAge(maxAgeSeconds);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
