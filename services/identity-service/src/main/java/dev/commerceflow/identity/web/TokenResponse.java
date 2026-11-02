package dev.commerceflow.identity.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/** refreshToken is present only when the client asked for body delivery. */
@JsonInclude(JsonInclude.Include.NON_NULL)
record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken) {}
