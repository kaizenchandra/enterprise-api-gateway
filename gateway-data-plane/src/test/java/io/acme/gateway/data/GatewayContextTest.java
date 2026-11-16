package io.acme.gateway.data;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "control.token=context-test-secret-at-least-thirty-two-characters",
                "control.url=http://127.0.0.1:9",
                "security.issuer=https://issuer.test",
                "security.jwks=http://127.0.0.1:9/jwks",
                "management.server.port=0"
        })
class GatewayContextTest {
    @MockitoBean
    Heartbeat heartbeat;
    @Autowired
    ApplicationContext context;

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry properties) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String key =
                Base64.getEncoder()
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        properties.add("security.identity-private-key", () -> key);
    }

    @Test
    void applicationStartsWithRealGatewayFiltersAndBoundedClient() {
        assertNotNull(context.getBean(org.springframework.web.cors.reactive.CorsWebFilter.class));
        assertTrue(context.getBeansOfType(org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4jBulkheadProvider.class).isEmpty(),
                "Route admission must be the sole local concurrency limit");
        assertNotNull(context.getBean(org.springframework.cloud.gateway.route.RouteLocator.class));
        assertEquals(
                512,
                context.getBean(reactor.netty.resources.ConnectionProvider.class).maxConnections());
    }
}
