package com.dev.commonlibjwt.jwt;


import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@EnableConfigurationProperties(com.dev.commonlibjwt.jwt.JwtProperties.class)
@Configuration
public class JwtAutoConfiguration {
    @Bean
    public JwtUtils jwtUtil(JwtProperties props) {
        return new JwtUtils(props.getSecretKey(), props.getExpiration(), props.getExpirationRefresh());
    }
}
