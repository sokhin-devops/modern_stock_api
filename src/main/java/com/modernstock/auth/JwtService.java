package com.modernstock.auth;

import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.modernstock.config.AppProperties;

import lombok.RequiredArgsConstructor;

/** Access tokens identify only the account (user ID and super admin flag), never a business. */
@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtEncoder encoder;
    private final AppProperties props;

    public Duration accessTokenLifetime() {
        return Duration.ofMinutes(props.jwt().accessTokenMinutes());
    }

    public String issue(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("modern_stock_api")
                .issuedAt(now)
                .expiresAt(now.plus(accessTokenLifetime()))
                .subject(user.getId().toString())
                .claim("name", user.getFullName())
                .claim("sa", user.isSuperAdmin())
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
