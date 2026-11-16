package io.acme.gateway.control;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminKeyTest {
    @Test
    void issuanceUsesInjectedRepositoryNotProxyFields() {
        Store store = mock(Store.class); // Proxy/mock fields intentionally remain null.
        JdbcTemplate db = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(db.queryForObject(anyString(), eq(String.class), eq("consumer"))).thenReturn("tenant");
        Admin admin = new Admin(store, redis, db, "01234567890123456789012345678901");
        var key =
                admin.issueKey(
                        "consumer",
                        new Admin.KeyRequest(Set.of("orders:read"), Set.of(), 1),
                        "operator");
        String secret = (String) key.get("secret");
        assertEquals(80, secret.length());
        assertEquals(key.get("id"), secret.substring(0, 36));
        verify(db)
                .update(
                        contains("insert into api_keys"),
                        eq(key.get("id")),
                        eq("consumer"),
                        eq(Admin.hash(secret)),
                        anyLong());
        verify(values)
                .set(
                        eq("key:" + key.get("id")),
                        startsWith(Admin.hash(secret) + "|consumer|tenant|"),
                        any(java.time.Duration.class));
    }

    @Test
    void revocationDeletesLiveVerifierBeforeDurableMark() {
        Store store = mock(Store.class);
        JdbcTemplate db = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Authentication actor = mock(Authentication.class);
        when(actor.getName()).thenReturn("operator");
        new Admin(store, redis, db, "01234567890123456789012345678901").revoke("key-id", actor);
        var order = inOrder(redis, db, store);
        order.verify(redis).delete("key:key-id");
        order.verify(db).update(contains("revoked=true"), eq("key-id"));
        order.verify(store).audit("operator", "revoke-key", "key-id");
    }
}
