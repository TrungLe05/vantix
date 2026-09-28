package com.dev.user_service.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class OAuth2RedirectUrlBuilder {

    @Value("${app.oauth2.frontend-callback-url}")
    private String frontendCallbackUrl;

    public String buildSuccessUrl(String oneTimeCode) {
        return UriComponentsBuilder.fromUriString(frontendCallbackUrl)
                .queryParam("code", oneTimeCode)
                .build().toUriString();
    }

    public String buildErrorUrl(int errorCode) {
        return UriComponentsBuilder.fromUriString(frontendCallbackUrl)
                .queryParam("error", errorCode)
                .build().toUriString();
    }
}