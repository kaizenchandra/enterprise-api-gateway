# Compatibility evidence

Java 21, Spring Boot **4.0.9**, Spring Cloud **2025.1.4**, Gateway Server WebFlux **5.0.4**. The data plane uses
reactive Netty; the servlet control plane runs separately.

Official sources checked on 2026-11-15:

* [Cloud 2025.1.4 release](https://spring.io/blog/2026/10/23/spring-cloud-2025-1-4-has-been-released/): based on Boot
  4.0.9, includes Gateway 5.0.4.
* [Boot requirements](https://docs.spring.io/spring-boot/4.0/system-requirements.html): Java 17–27, Framework 7.0.10+.
  Java 21 is selected for builds and images.
* [Boot migration](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide): modular
  starters, Jackson 3, Spring 7 header API. Flyway and OpenTelemetry use dedicated Boot starters.
* [Managed coordinates](https://docs.spring.io/spring-boot/4.0/appendix/dependency-versions/coordinates.html): Boot
  manages Framework 7.0.10, Security 7.0.8, Reactor Netty 1.3.8, Netty 4.2.19.Final, Jackson 3.1.7 and Tomcat 11.0.27.
* [Reactive JWT](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html): Nimbus
  resource server verifies trusted JWKs; application adds audience, type, subject and tenant validators.
* [Tracing](https://docs.spring.io/spring-boot/4.0/reference/actuator/tracing.html):
  `spring-boot-starter-opentelemetry`, `management.opentelemetry.tracing.export.otlp.endpoint`.

BOMs govern framework versions; do not substitute individual Spring jars. `results/bom.json` records resolved public
Maven components, and `results/dependency-scan.json` records the OSV query outcome and date. These are a dependency
check, not an operating-system image scan or a guarantee of absence of vulnerabilities. Recheck before deployment.

OpenTelemetry uses the complete **1.67.0 BOM** through Boot’s `opentelemetry.version` property. This explicit override
patches [tracestate CPU exhaustion](https://github.com/open-telemetry/opentelemetry-java/security/advisories/GHSA-ccrj-gw8r-854m)
and [baggage allocation](https://github.com/open-telemetry/opentelemetry-java/security/advisories/GHSA-rcgg-9c38-7xpx).
Framework libraries remain BOM-managed; OTel binary compatibility is exercised by the reactor and live trace tests.
