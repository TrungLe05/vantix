package com.dev.user_service.config;

import com.dev.commonlib_security.security.GatewayHeaderAuthFilter;
import com.dev.user_service.security.OAuth2LoginFailureHandler;
import com.dev.user_service.security.OAuth2LoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

     private final GatewayHeaderAuthFilter gatewayHeaderAuthFilter; // cần xác nhận cách khởi tạo
     private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
     private final OAuth2LoginFailureHandler oAuth2LoginFailureHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // API thuần, không dùng session/cookie CSRF
//                .formLogin(form -> form.disable())
//                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Route công khai — khớp bảng "Tổng hợp API" trong P1_auth_api_design.md mục 2
                        .requestMatchers(
                                "/auth/register", "/auth/verify-email", "/auth/resend-verification",
                                "/auth/login", "/auth/oauth2/**", "/auth/refresh",
                                "/auth/forgot-password", "/auth/reset-password",
                                "/oauth2/authorization/**", "/login/oauth2/code/**"
                        ).permitAll()
                        .requestMatchers("/users/staff").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(oAuth2LoginSuccessHandler)
                        .failureHandler(oAuth2LoginFailureHandler)
                )
         .addFilterBefore(gatewayHeaderAuthFilter, UsernamePasswordAuthenticationFilter.class)
        ;

        return http.build();
    }

    @Bean
    public RestClient googleRestClient() {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(5));

        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

}
