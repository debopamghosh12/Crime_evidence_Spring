package com.blockevidence.backend.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.blockevidence.backend.TestSecrets;
import com.blockevidence.backend.config.ClockConfig;
import com.blockevidence.backend.config.JwtProperties;
import com.blockevidence.backend.dto.TransferRequest;
import com.blockevidence.backend.exception.ApiErrorWriter;
import com.blockevidence.backend.exception.GlobalExceptionHandler;
import com.blockevidence.backend.security.ApiAccessDeniedHandler;
import com.blockevidence.backend.security.ApiAuthenticationEntryPoint;
import com.blockevidence.backend.security.AuthenticatedUser;
import com.blockevidence.backend.security.JwtService;
import com.blockevidence.backend.security.Role;
import com.blockevidence.backend.security.SecurityConfig;
import com.blockevidence.backend.service.CaseService;
import com.blockevidence.backend.service.CustodyService;
import com.blockevidence.backend.service.StatusService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

/** A3, D1-D3, E1/E2, C-02, C-05 at the HTTP layer: the real security chain with the services mocked. */
@EnableConfigurationProperties(JwtProperties.class)
@WebMvcTest(controllers = { EvidenceLifecycleController.class, CaseController.class })
@Import({ SecurityConfig.class, JwtService.class, ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class,
        ApiErrorWriter.class, GlobalExceptionHandler.class, ClockConfig.class })
class Phase3ControllersTest {

    @DynamicPropertySource
    static void jwtSecret(DynamicPropertyRegistry registry) {
        registry.add("blockevidence.jwt.secret", () -> TestSecrets.JWT_SECRET);
    }

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @MockitoBean StatusService statusService;
    @MockitoBean CustodyService custodyService;
    @MockitoBean CaseService caseService;
    @MockitoBean UserDetailsService userDetailsService;
    @MockitoBean com.blockevidence.backend.audit.AuditService auditService;

    static final String ID = "EV-3f2b7c1e-9a4d-4b8e-8c11-0a1b2c3d4e5f";
    final UUID userId = UUID.randomUUID();
    final UUID someone = UUID.randomUUID();
    final UUID caseId = UUID.randomUUID();

    String bearer(Role role) {
        return "Bearer " + jwtService.issueAccessToken(userId, "u@example.org", role).token();
    }

    void assertOnly(Set<Role> allowed, java.util.function.Function<Role, ResultActions> call, int okStatus) throws Exception {
        for (Role role : Role.values()) {
            ResultActions r = call.apply(role);
            if (allowed.contains(role)) {
                r.andExpect(status().is(okStatus));
            } else {
                r.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
            }
        }
    }

    static final Set<Role> STATUS_ROLES = Set.of(Role.COLLECTOR, Role.FORENSIC_ANALYST, Role.PROSECUTOR);
    static final Set<Role> CUSTODY_ROLES = Set.of(Role.COLLECTOR, Role.FORENSIC_ANALYST, Role.PROSECUTOR, Role.JUDGE);
    static final Set<Role> CASE_MANAGERS = Set.of(Role.ADMIN, Role.PROSECUTOR);

    ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, Role role, String body) throws Exception {
        return mvc.perform(b.contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", bearer(role)));
    }

    // ------------------------------------------------------------------------------------ D1, D2, D3

    @Test
    void onlyCollectorAnalystAndProsecutorMayChangeStatus() throws Exception {
        assertOnly(STATUS_ROLES, role -> {
            try {
                return json(post("/api/evidence/" + ID + "/status"), role, "{\"expectedVersion\":1,\"status\":\"PROCESSING\",\"reason\":\"r\"}");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, 200);
    }

    @Test
    void custodyHoldersMayInitiateAcceptRejectCancelAndListPendingAndNobodyElse() throws Exception {
        String transfer = "{\"expectedVersion\":1,\"toUserId\":\"" + someone + "\",\"reason\":\"r\"}";
        String decision = "{\"expectedVersion\":2,\"note\":\"n\"}";
        for (String[] c : new String[][] { { "", transfer }, { "/accept", decision }, { "/reject", decision }, { "/cancel", decision } }) {
            assertOnly(CUSTODY_ROLES, role -> {
                try {
                    return json(post("/api/evidence/" + ID + "/transfers" + c[0]), role, c[1]);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, 200);
        }
        assertOnly(CUSTODY_ROLES, role -> {
            try {
                return mvc.perform(get("/api/transfers/pending").header("Authorization", bearer(role)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, 200);
    }

    @Test
    void everyRoleCanReadTheCustodyTimeline() throws Exception {
        for (Role role : Role.values()) {
            mvc.perform(get("/api/evidence/" + ID + "/chain-of-custody").header("Authorization", bearer(role))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/evidence/" + ID + "/chain-of-custody")).andExpect(status().isUnauthorized());
    }

    @Test
    void theSenderIsTheTokenUserEvenIfTheBodyNamesSomeoneElse() throws Exception {
        String body = "{\"expectedVersion\":1,\"toUserId\":\"" + someone + "\",\"reason\":\"r\","
                + "\"from\":\"" + UUID.randomUUID() + "\",\"sender\":\"attacker\",\"actorId\":\"x\"}";

        json(post("/api/evidence/" + ID + "/transfers"), Role.COLLECTOR, body).andExpect(status().isOk());

        ArgumentCaptor<AuthenticatedUser> actor = ArgumentCaptor.forClass(AuthenticatedUser.class);
        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(custodyService).initiate(eq(ID), req.capture(), actor.capture());
        org.assertj.core.api.Assertions.assertThat(actor.getValue().userId()).isEqualTo(userId);
        org.assertj.core.api.Assertions.assertThat(req.getValue().toUserId()).isEqualTo(someone);   // the RECEIVER, a target not an actor
        org.assertj.core.api.Assertions.assertThat(TransferRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("expectedVersion", "toUserId", "reason", "notes");
    }

    @Test
    void validationReportsEachBadField() throws Exception {
        json(post("/api/evidence/" + ID + "/status"), Role.COLLECTOR, "{\"status\":\"NOT_A_STATUS\"}")
                .andExpect(status().isBadRequest());
        json(post("/api/evidence/" + ID + "/status"), Role.COLLECTOR, "{\"reason\":\" \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("expectedVersion")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("status")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("reason")));
        json(post("/api/evidence/" + ID + "/transfers"), Role.COLLECTOR, "{\"expectedVersion\":1,\"toUserId\":null,\"reason\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("toUserId")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("reason")));
        verify(statusService, never()).change(any(), any(), any());
        verify(custodyService, never()).initiate(any(), any(), any());
    }

    @Test
    void thereIsStillNoDeleteOnAnyEvidenceRoute() throws Exception {
        for (String path : List.of("/status", "/transfers", "/transfers/accept", "/chain-of-custody")) {
            mvc.perform(delete("/api/evidence/" + ID + path).header("Authorization", bearer(Role.ADMIN)))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    // ------------------------------------------------------------------------------------ E1, E2

    @Test
    void onlyAdminAndProsecutorManageCases() throws Exception {
        String create = "{\"caseNumber\":\"CASE-2026-9\",\"title\":\"Burglary\",\"leadOfficerId\":\"" + someone + "\"}";
        assertOnly(CASE_MANAGERS, role -> {
            try {
                return json(post("/api/cases"), role, create);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, 201);
        assertOnly(CASE_MANAGERS, role -> {
            try {
                return json(put("/api/cases/" + caseId), role, "{\"title\":\"New title\"}");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, 200);
        assertOnly(CASE_MANAGERS, role -> {
            try {
                return json(post("/api/cases/" + caseId + "/members"), role, "{\"userId\":\"" + someone + "\",\"caseRole\":\"INVESTIGATOR\"}");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, 200);
        assertOnly(CASE_MANAGERS, role -> {
            try {
                return mvc.perform(delete("/api/cases/" + caseId + "/members/" + someone).header("Authorization", bearer(role)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, 200);
    }

    @Test
    void everyAuthenticatedRoleCanReadCasesAndAnonymousCannot() throws Exception {
        for (Role role : Role.values()) {
            mvc.perform(get("/api/cases").header("Authorization", bearer(role))).andExpect(status().isOk());
            mvc.perform(get("/api/cases/" + caseId).header("Authorization", bearer(role))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/cases")).andExpect(status().isUnauthorized());
    }

    @Test
    void caseValidationAndDeleteOfACaseDoesNotExist() throws Exception {
        json(post("/api/cases"), Role.ADMIN, "{\"caseNumber\":\"bad number!\",\"title\":\"\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("caseNumber")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("title")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("leadOfficerId")));
        json(put("/api/cases/" + caseId), Role.ADMIN, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("atLeastOneChange")));
        mvc.perform(delete("/api/cases/" + caseId).header("Authorization", bearer(Role.ADMIN))).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/cases/not-a-uuid").header("Authorization", bearer(Role.ADMIN))).andExpect(status().isBadRequest());
    }
}
