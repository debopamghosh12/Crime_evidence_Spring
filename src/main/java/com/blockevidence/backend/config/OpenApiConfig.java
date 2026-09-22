package com.blockevidence.backend.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

/**
 * K2: springdoc-openapi generates the spec from the annotations on the controllers/DTOs themselves (source of
 * truth = the actual code, not a hand-maintained document that drifts) - this class only supplies the top-level
 * metadata and the one security scheme every protected endpoint shares. UI at {@code /swagger-ui.html}, raw
 * spec at {@code /v3/api-docs}.
 *
 * <p>{@code bearerFormat = "JWT"} and the scheme name "bearerAuth" match exactly what
 * {@code JwtAuthenticationFilter} actually reads (an {@code Authorization: Bearer <token>} header) - this is
 * documentation of the real filter chain, not an assumption.
 */
@OpenAPIDefinition(
        info = @Info(
                title = "BlockEvidence API",
                version = "1.0",
                description = """
                        Evidence management backed by a Hyperledger Fabric ledger (docs/ARCHITECTURE.md). \
                        Every write is recorded on-chain; the ledger itself holds only ids, hashes and CIDs \
                        (constraint C-06) - descriptive content (the metadata document and, for DIGITAL \
                        evidence, the file) lives off-chain on IPFS, AES-256-GCM encrypted per evidence item \
                        with the key RSA-wrapped per authorised user (F2/F3): the public CID alone reveals \
                        nothing. `POST /api/auth/login` first; every other endpoint below needs the resulting \
                        access token as `Authorization: Bearer <token>` (15-minute lifetime; use \
                        `POST /api/auth/refresh` to rotate it). Each protected endpoint's description below \
                        names exactly which role(s) may call it - `@PreAuthorize` on the real controller \
                        method, not aspirational.\
                        """),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
