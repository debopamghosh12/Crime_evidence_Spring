package com.blockevidence.backend.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.blockevidence.backend.TestSecrets;
import com.blockevidence.backend.config.ClockConfig;
import com.blockevidence.backend.config.JwtProperties;
import com.blockevidence.backend.controller.AuthController;
import com.blockevidence.backend.dto.MeResponse;
import com.blockevidence.backend.dto.TokenResponse;
import com.blockevidence.backend.exception.ApiErrorWriter;
import com.blockevidence.backend.exception.GlobalExceptionHandler;
import com.blockevidence.backend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Exercises the real security filter chain, method security and error handling through MockMvc
 * (A1, A3, K1). AuthService is mocked; JwtService is real, so tokens are genuinely signed and verified.
 */
// A slice test does not process @ConfigurationPropertiesScan from the main class, hence the explicit registration.
@EnableConfigurationProperties(JwtProperties.class)
@WebMvcTest(controllers = { AuthController.class, RbacProbeController.class })
@Import({ SecurityConfig.class, JwtService.class, ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class,
        ApiErrorWriter.class, GlobalExceptionHandler.class, ClockConfig.class })
class SecurityAndErrorFormatTest {

    @DynamicPropertySource
    static void jwtSecret(DynamicPropertyRegistry registry) {
        registry.add("blockevidence.jwt.secret", () -> TestSecrets.JWT_SECRET);
    }

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @MockitoBean AuthService authService;
    @MockitoBean UserDetailsService userDetailsService; // needed only to build the AuthenticationManager bean

    final UUID userId = UUID.randomUUID();

    String bearer(Role role) {
        return "Bearer " + jwtService.issueAccessToken(userId, "u@example.com", role).token();
    }

    ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.error").value(code))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.path").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    // --------------------------------------------------------- 401 (authentication)

    @Test
    void protectedRouteWithoutTokenIs401InStandardFormat() throws Exception {
        expectError(mvc.perform(get("/test-probe/authenticated")), 401, "UNAUTHENTICATED")
                .andExpect(jsonPath("$.path").value("/test-probe/authenticated"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void garbageTokenIs401() throws Exception {
        expectError(mvc.perform(get("/test-probe/authenticated").header("Authorization", "Bearer not.a.jwt")),
                401, "UNAUTHENTICATED");
    }

    @Test
    void expiredTokenIs401() throws Exception {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), java.time.ZoneOffset.UTC);
        var props = new JwtProperties(
                TestSecrets.JWT_SECRET, Duration.ofMinutes(15), Duration.ofDays(7),
                "blockevidence-backend");
        String expired = new JwtService(props, past).issueAccessToken(userId, "u@example.com", Role.ADMIN).token();

        expectError(mvc.perform(get("/test-probe/admin").header("Authorization", "Bearer " + expired)),
                401, "UNAUTHENTICATED");
    }

    @Test
    void tokenSignedWithADifferentSecretIs401() throws Exception {
        var props = new JwtProperties(
                TestSecrets.OTHER_SECRET, Duration.ofMinutes(15), Duration.ofDays(7),
                "blockevidence-backend");
        String forged = new JwtService(props, Clock.systemUTC())
                .issueAccessToken(userId, "u@example.com", Role.ADMIN).token();

        expectError(mvc.perform(get("/test-probe/admin").header("Authorization", "Bearer " + forged)),
                401, "UNAUTHENTICATED");
    }

    // ------------------------------------------------------------ 403 (RBAC, A3)

    @Test
    void adminRouteIsForbiddenToACollector() throws Exception {
        expectError(mvc.perform(get("/test-probe/admin").header("Authorization", bearer(Role.COLLECTOR))),
                403, "ACCESS_DENIED");
    }

    @Test
    void adminRouteIsOpenToAnAdmin() throws Exception {
        mvc.perform(get("/test-probe/admin").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void hasAnyRoleAdmitsBothListedRolesAndNoOthers() throws Exception {
        mvc.perform(get("/test-probe/judge-or-auditor").header("Authorization", bearer(Role.JUDGE)))
                .andExpect(status().isOk());
        mvc.perform(get("/test-probe/judge-or-auditor").header("Authorization", bearer(Role.AUDITOR)))
                .andExpect(status().isOk());
        for (Role denied : new Role[] { Role.COLLECTOR, Role.FORENSIC_ANALYST, Role.PROSECUTOR, Role.ADMIN }) {
            expectError(mvc.perform(get("/test-probe/judge-or-auditor").header("Authorization", bearer(denied))),
                    403, "ACCESS_DENIED");
        }
    }

    // -------------------------------------------------------------- 400 (K1)

    @Test
    void invalidLoginBodyListsEveryOffendingField() throws Exception {
        expectError(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\",\"password\":\"\"}")), 400, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("password")));
    }

    @Test
    void malformedJsonIs400WithoutLeakingParserInternals() throws Exception {
        expectError(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{ this is not json")), 400, "BAD_REQUEST")
                .andExpect(jsonPath("$.message", not(containsString("Jackson"))))
                .andExpect(jsonPath("$.message", not(containsString("Unexpected"))));
    }

    @Test
    void loginIsPublicAndReturnsTheServiceResult() throws Exception {
        when(authService.login(org.mockito.ArgumentMatchers.any()))
                .thenReturn(TokenResponse.bearer("access", "refresh", 900));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"u@example.com\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").value("refresh"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    // ------------------------------------------------- other statuses through K1

    @Test
    void wrongHttpMethodIs405InStandardFormat() throws Exception {
        expectError(mvc.perform(get("/api/auth/login").header("Authorization", bearer(Role.ADMIN))),
                405, "METHOD_NOT_ALLOWED");
    }

    @Test
    void unknownPathIs404InStandardFormat() throws Exception {
        expectError(mvc.perform(get("/no/such/route").header("Authorization", bearer(Role.ADMIN))),
                404, "NOT_FOUND");
    }

    @Test
    void unexpectedExceptionIs500WithNoInternalDetail() throws Exception {
        expectError(mvc.perform(get("/test-probe/boom").header("Authorization", bearer(Role.ADMIN))),
                500, "INTERNAL_ERROR")
                .andExpect(jsonPath("$.message", not(containsString("secret"))))
                .andExpect(jsonPath("$.message", not(containsString("jdbc"))));
    }

    @Test
    void stubbedFeatureIs501() throws Exception {
        expectError(mvc.perform(get("/test-probe/not-implemented").header("Authorization", bearer(Role.ADMIN))),
                501, "NOT_IMPLEMENTED");
    }

    // ------------------------------------------- principal comes from the token (C-05)

    @Test
    void meUsesTheUserIdFromTheTokenOnly() throws Exception {
        when(authService.me(userId)).thenReturn(new MeResponse(userId, "u@example.com", "U", "Dept", Role.JUDGE));

        mvc.perform(get("/api/auth/me").header("Authorization", bearer(Role.JUDGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.role").value("JUDGE"));
        verify(authService).me(userId);
    }

    @Test
    void meRequiresAToken() throws Exception {
        expectError(mvc.perform(get("/api/auth/me")), 401, "UNAUTHENTICATED");
    }

    @Test
    void logoutRequiresATokenAndPassesTheTokenOwner() throws Exception {
        expectError(mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"r\"}")), 401, "UNAUTHENTICATED");

        mvc.perform(post("/api/auth/logout").header("Authorization", bearer(Role.COLLECTOR))
                .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"r\"}"))
                .andExpect(status().isNoContent());
        verify(authService).logout(userId, "r");
    }
}
