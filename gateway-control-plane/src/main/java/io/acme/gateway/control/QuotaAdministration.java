package io.acme.gateway.control;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
public class QuotaAdministration {
    private final Store store;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>();

    QuotaAdministration(Store store, StringRedisTemplate redis) {
        this.store = store;
        this.redis = redis;
        script.setResultType(Long.class);
        script.setLocation(new ClassPathResource("quota-adjust.lua"));
    }

    @PostMapping({
            "/api/v1/admin/quotas/{tenant}/{api}/adjustments",
            "/api/v1/admin/quotas/{tenant}/{api}/reset"
    })
    Object adjust(
            @PathVariable String tenant,
            @PathVariable String api,
            @RequestHeader("If-Match") long revision,
            @RequestBody Adjustment body,
            Authentication actor) {
        if (!tenant.matches("[a-zA-Z0-9_-]{1,64}")
                || !api.matches("[a-zA-Z0-9_-]{1,64}")
                || body.dailyUsed() < 0
                || body.monthlyUsed() < 0
                || body.dailyUsed() > 9007199254740991L
                || body.monthlyUsed() > 9007199254740991L
                || body.idempotencyKey() == null
                || !body.idempotencyKey().matches("[a-zA-Z0-9_-]{1,64}")
                || body.reason() == null
                || body.reason().isBlank()
                || body.reason().length() > 256)
            throw new IllegalArgumentException("invalid quota correction");
        if (store.latest().routes().stream()
                .noneMatch(r -> r.tenant().equals(tenant) && r.apiId().equals(api)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String target = tenant + ":" + api + ":" + body.idempotencyKey();
        store.audit(actor.getName(), "quota-adjust-attempt", target);
        Long applied =
                redis.execute(
                        script,
                        List.of(
                                "{t:" + tenant + "}:quota:" + api,
                                "{t:" + tenant + "}:adjust:" + api + ":" + body.idempotencyKey()),
                        "" + revision,
                        "" + body.dailyUsed(),
                        "" + body.monthlyUsed());
        if (!Long.valueOf(1).equals(applied))
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
        store.audit(actor.getName(), "quota-adjust", target);
        return Map.of("applied", true, "idempotencyKey", body.idempotencyKey());
    }

    record Adjustment(long dailyUsed, long monthlyUsed, String idempotencyKey, String reason) {
    }
}
