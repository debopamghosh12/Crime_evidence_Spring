package com.blockevidence.backend.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.blockevidence.backend.TestSecrets;
import com.blockevidence.backend.config.ClockConfig;
import com.blockevidence.backend.config.JwtProperties;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.dto.RegisterEvidenceRequest;
import com.blockevidence.backend.dto.VerificationResponse;
import com.blockevidence.backend.exception.ApiErrorWriter;
import com.blockevidence.backend.exception.GlobalExceptionHandler;
import com.blockevidence.backend.ledger.LedgerErrorCode;
import com.blockevidence.backend.ledger.LedgerException;
import com.blockevidence.backend.security.ApiAccessDeniedHandler;
import com.blockevidence.backend.security.ApiAuthenticationEntryPoint;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.JwtService;
import com.blockevidence.backend.security.Role;
import com.blockevidence.backend.security.SecurityConfig;
import com.blockevidence.backend.service.EvidenceService;
import com.blockevidence.backend.storage.StorageUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * A3, B1-B5, C-02, C-05 at the HTTP layer: the real security chain and method security, with
 * EvidenceService mocked. Verifies who may call what, how requests bind, and how failures are rendered.
 */
@EnableConfigurationProperties(JwtProperties.class)
@WebMvcTest(controllers = EvidenceController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class,
        ApiErrorWriter.class, GlobalExceptionHandler.class, ClockConfig.class })
class EvidenceControllerTest {

    @DynamicPropertySource
    static void jwtSecret(DynamicPropertyRegistry registry) {
        registry.add("blockevidence.jwt.secret", () -> TestSecrets.JWT_SECRET);
    }

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @MockitoBean EvidenceService evidenceService;
    @MockitoBean UserDetailsService userDetailsService;

    static final String ID = "EV-3f2b7c1e-9a4d-4b8e-8c11-0a1b2c3d4e5f";
    final UUID userId = UUID.randomUUID();

    String bearer(Role role) {
        return "Bearer " + jwtService.issueAccessToken(userId, "u@example.org", role).token();
    }

    MockMultipartHttpServletRequestBuilder registration(String metadataJson) {
        return multipart("/api/evidence")
                .file(new MockMultipartFile("metadata", "", "application/json", metadataJson.getBytes()))
                .file(new MockMultipartFile("file", "phone.txt", "text/plain", "data".getBytes()));
    }

    static final String VALID_METADATA =
            "{\"caseId\":\"CASE-1\",\"type\":\"DIGITAL\",\"description\":\"Phone\",\"location\":\"Locker\"}";

    void expectForbidden(ResultActions r) throws Exception {
        r.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    // ---------------------------------------------------------------------------- role matrix (A3)

    @Test
    void onlyCollectorAndAnalystMayRegister() throws Exception {
        for (Role role : Role.values()) {
            ResultActions r = mvc.perform(registration(VALID_METADATA).header("Authorization", bearer(role)));
            if (role == Role.COLLECTOR || role == Role.FORENSIC_ANALYST) {
                r.andExpect(status().isCreated());
            } else {
                expectForbidden(r);
            }
        }
    }

    @Test
    void onlyCollectorAndAnalystMayUpdate() throws Exception {
        String body = "{\"expectedVersion\":1,\"reason\":\"typo\",\"description\":\"fixed\"}";
        for (Role role : Role.values()) {
            ResultActions r = mvc.perform(put("/api/evidence/" + ID).contentType(MediaType.APPLICATION_JSON)
                    .content(body).header("Authorization", bearer(role)));
            if (role == Role.COLLECTOR || role == Role.FORENSIC_ANALYST) {
                r.andExpect(status().isOk());
            } else {
                expectForbidden(r);
            }
        }
    }

    @Test
    void disposalRequestByCollectorOrProsecutorAndDecisionByJudgeOnly() throws Exception {
        String request = "{\"expectedVersion\":1,\"reason\":\"case closed\"}";
        String decision = "{\"expectedVersion\":2,\"note\":\"reviewed\"}";
        for (Role role : Role.values()) {
            ResultActions asked = mvc.perform(post("/api/evidence/" + ID + "/disposal")
                    .contentType(MediaType.APPLICATION_JSON).content(request).header("Authorization", bearer(role)));
            if (role == Role.COLLECTOR || role == Role.PROSECUTOR) {
                asked.andExpect(status().isOk());
            } else {
                expectForbidden(asked);
            }
            for (String action : List.of("approve", "reject")) {
                ResultActions decided = mvc.perform(post("/api/evidence/" + ID + "/disposal/" + action)
                        .contentType(MediaType.APPLICATION_JSON).content(decision).header("Authorization", bearer(role)));
                if (role == Role.JUDGE) {
                    decided.andExpect(status().isOk());
                } else {
                    expectForbidden(decided);
                }
            }
        }
    }

    @Test
    void everyRoleCanRead() throws Exception {
        when(evidenceService.verify(ID)).thenReturn(VerificationResponse.notChecked(ID));
        for (Role role : Role.values()) {
            for (String path : List.of("", "/history", "/verify", "/versions/1")) {
                mvc.perform(get("/api/evidence/" + ID + path).header("Authorization", bearer(role)))
                        .andExpect(status().isOk());
            }
            mvc.perform(get("/api/evidence/by-cid/bafkreiabc").header("Authorization", bearer(role)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void everyEndpointRequiresAuthentication() throws Exception {
        for (String path : List.of("/api/evidence/" + ID, "/api/evidence/" + ID + "/history",
                "/api/evidence/" + ID + "/verify", "/api/evidence/by-cid/x")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
        }
        mvc.perform(registration(VALID_METADATA)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/evidence/" + ID).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------- C-02: no delete

    @Test
    void thereIsNoDeleteEndpointForEvidence() throws Exception {
        for (Role role : Role.values()) {
            mvc.perform(delete("/api/evidence/" + ID).header("Authorization", bearer(role)))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
            mvc.perform(delete("/api/evidence").header("Authorization", bearer(role)))
                    .andExpect(status().isMethodNotAllowed());
            mvc.perform(delete("/api/evidence/" + ID + "/disposal").header("Authorization", bearer(role)))
                    .andExpect(status().isMethodNotAllowed());
        }
        org.mockito.Mockito.verifyNoInteractions(evidenceService);
    }

    // ------------------------------------------------------------------------- C-05: the officer

    @Test
    void aCollectorFieldInTheRequestIsIgnoredAndTheTokenUserIsUsed() throws Exception {
        String metadataWithForgedOfficer = "{\"caseId\":\"CASE-1\",\"type\":\"DIGITAL\",\"description\":\"Phone\","
                + "\"collector\":\"someone-else@example.org\",\"collectorId\":\"00000000-0000-0000-0000-000000000000\","
                + "\"createdBy\":\"attacker\"}";

        mvc.perform(registration(metadataWithForgedOfficer).header("Authorization", bearer(Role.COLLECTOR)))
                .andExpect(status().isCreated());

        ArgumentCaptor<AuthenticatedUser> actor = ArgumentCaptor.forClass(AuthenticatedUser.class);
        ArgumentCaptor<RegisterEvidenceRequest> body = ArgumentCaptor.forClass(RegisterEvidenceRequest.class);
        verify(evidenceService).register(body.capture(), any(), actor.capture());
        org.assertj.core.api.Assertions.assertThat(actor.getValue().userId()).isEqualTo(userId);   // from the JWT
        org.assertj.core.api.Assertions.assertThat(actor.getValue().role()).isEqualTo(Role.COLLECTOR);
        org.assertj.core.api.Assertions.assertThat(body.getValue().caseId()).isEqualTo("CASE-1");
        // The DTO has no officer-like component at all, so there is nothing a client could inject.
        org.assertj.core.api.Assertions.assertThat(RegisterEvidenceRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .noneMatch(n -> n.toLowerCase().contains("collector") || n.toLowerCase().contains("officer")
                        || n.toLowerCase().contains("createdby"));
    }

    // -------------------------------------------------------------------------------- validation

    @Test
    void registrationValidationReportsEachBadField() throws Exception {
        String bad = "{\"caseId\":\"bad case id!\",\"type\":null,\"description\":\"\"}";

        mvc.perform(registration(bad).header("Authorization", bearer(Role.COLLECTOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("caseId")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("type")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("description")));
        verify(evidenceService, never()).register(any(), any(), any());
    }

    @Test
    void updateRequiresAReasonAnExpectedVersionAndAtLeastOneChange() throws Exception {
        mvc.perform(put("/api/evidence/" + ID).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"\"}").header("Authorization", bearer(Role.COLLECTOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("reason")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("expectedVersion")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("atLeastOneChange")));
    }

    @Test
    void disposalRequiresAReason() throws Exception {
        mvc.perform(post("/api/evidence/" + ID + "/disposal").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"reason\":\" \"}").header("Authorization", bearer(Role.COLLECTOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("reason")));
    }

    @Test
    void anIdThatIsNotAnEvidenceIdIsA404BeforeAnyServiceCall() throws Exception {
        mvc.perform(get("/api/evidence/not-an-id").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isNotFound());
        org.mockito.Mockito.verifyNoInteractions(evidenceService);
    }

    // ------------------------------------------------------------------------- error rendering

    @Test
    void ledgerAndStorageFailuresRenderWithTheirOwnStatusAndCode() throws Exception {
        when(evidenceService.get(eq(ID), eq(false)))
                .thenThrow(new LedgerException(LedgerErrorCode.VERSION_CONFLICT, "Expected version 1 but at 2"));
        mvc.perform(get("/api/evidence/" + ID).header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Expected version 1 but at 2"));

        when(evidenceService.verify(ID)).thenThrow(new StorageUnavailableException("IPFS node not reachable"));
        mvc.perform(get("/api/evidence/" + ID + "/verify").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("STORAGE_UNAVAILABLE"));

        when(evidenceService.history(ID)).thenThrow(new LedgerException(LedgerErrorCode.EVIDENCE_NOT_FOUND, "nope"));
        mvc.perform(get("/api/evidence/" + ID + "/history").header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("EVIDENCE_NOT_FOUND"));
    }
}
