package io.acme.gateway.data;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;

@Component
public class IdentityAssertion {
    private final RSAPrivateKey key;

    IdentityAssertion(@Value("${security.identity-private-key}") String encoded) throws Exception {
        key =
                (RSAPrivateKey)
                        KeyFactory.getInstance("RSA")
                                .generatePrivate(
                                        new PKCS8EncodedKeySpec(
                                                Base64.getDecoder().decode(encoded)));
    }

    String sign(Admission.Identity identity) {
        try {
            Instant now = Instant.now();
            var claims =
                    new JWTClaimsSet.Builder()
                            .issuer("enterprise-gateway")
                            .audience("example-services")
                            .subject(identity.subject())
                            .issueTime(Date.from(now))
                            .expirationTime(Date.from(now.plusSeconds(30)))
                            .claim("tenant", identity.tenant())
                            .claim("scopes", new ArrayList<>(identity.scopes()))
                            .claim("roles", new ArrayList<>(identity.roles()))
                            .build();
            var jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.RS256)
                                    .type(new JOSEObjectType("JWT"))
                                    .keyID("gateway-v1")
                                    .build(),
                            claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("identity signing failed");
        }
    }
}
