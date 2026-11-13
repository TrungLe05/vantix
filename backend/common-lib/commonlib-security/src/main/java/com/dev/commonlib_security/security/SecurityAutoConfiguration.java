package com.dev.commonlib_security.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
//@ConditionalOnWebApplication(type = SERVLET)
public class SecurityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(GatewayHeaderAuthFilter.class)
    public GatewayHeaderAuthFilter gatewayHeaderAuthFilter() {
        return new GatewayHeaderAuthFilter();
    }
}
