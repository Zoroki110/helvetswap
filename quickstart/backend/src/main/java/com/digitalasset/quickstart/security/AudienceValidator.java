package com.digitalasset.quickstart.security;

import java.util.Collection;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

public class AudienceValidator implements OAuth2TokenValidator<Jwt> {
  private final String audience;

  public AudienceValidator(String audience) {
    this.audience = audience;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    Object audClaim = token.getClaims().get("aud");

    // Check if audience is a single string
    if (audClaim instanceof String s && s.equals(audience)) {
      return OAuth2TokenValidatorResult.success();
    }

    // Check if audience is a collection
    if (audClaim instanceof Collection<?> c && c.contains(audience)) {
      return OAuth2TokenValidatorResult.success();
    }

    OAuth2Error err = new OAuth2Error(
      "invalid_token",
      "Required audience '" + audience + "' not present",
      null
    );
    return OAuth2TokenValidatorResult.failure(err);
  }
}
