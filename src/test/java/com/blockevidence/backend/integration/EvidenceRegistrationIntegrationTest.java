package com.blockevidence.backend.integration;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import com.blockevidence.backend.TestSecrets;
import com.blockevidence.backend.crypto.UserKeyService;
import com.blockevidence.backend.model.User;
import com.blockevidence.backend.repository.UserRepository;
import com.blockevidence.backend.security.Role;
import com.blockevidence.backend.storage.IpfsClient;
import com.blockevidence.backend.support.FakeIpfsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L2 (FEATURE_LIST: "Real Postgres and mocked IPFS/Fabric for the main flows"): a genuine end-to-end run of the
 * B1/B2/B3/C2/E1/E3 flow - login, create a case, register DIGITAL evidence, read it back, verify it - through
 * the REAL HTTP surface (real security filter chain, real Flyway migrations, real JPA/repositories) against a
 * REAL Postgres (Testcontainers), with Fabric mocked by the already-existing {@code memory-ledger} profile and
 * IPFS mocked by the already-existing {@link FakeIpfsClient} - exactly the two substitutions FEATURE_LIST names,
 * no new mocking mechanism. WireMock was considered and rejected: {@code HttpIpfsClientTest} already thoroughly
 * covers {@code HttpIpfsClient}'s own HTTP-level behaviour with a plain JDK {@code HttpServer}, so a second,
 * heavier HTTP-mocking dependency would duplicate that coverage rather than add any (DECISIONS).
 *
 * <p>This is genuinely new coverage no unit test offers: real Flyway migrations actually running against a real
 * database, real unique constraints/foreign keys actually enforced, and the real security filter chain actually
 * authenticating a real issued JWT - none of which a mocked-repository unit test exercises.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("memory-ledger")
@Transactional // rolls back everything each test writes, including through MockMvc requests, so the
                // static (class-shared) Testcontainers Postgres instance stays clean between test methods
class EvidenceRegistrationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void requiredSecrets(DynamicPropertyRegistry registry) {
        // No default exists for either (C-07) - the app refuses to start without them, real or test.
        registry.add("blockevidence.jwt.secret", () -> TestSecrets.JWT_SECRET);
        registry.add("blockevidence.encryption.master-key", () -> TestSecrets.ENCRYPTION_MASTER_KEY);
    }

    /** Replaces the real {@code HttpIpfsClient} bean with the same in-memory double every other test in this
     *  suite already trusts - see the class javadoc for why this is preferred over a new HTTP-mocking library. */
    @TestConfiguration
    static class FakeIpfsConfiguration {
        @Bean
        @Primary
        IpfsClient ipfsClient() {
            return new FakeIpfsClient();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserKeyService userKeys;
    @Autowired JsonMapper json;
    @Autowired Clock clock;

    User collector;
    User admin;

    @BeforeEach
    void seedUsers() {
        String hash = passwordEncoder.encode("integration-test-password");
        collector = users.save(new User("l2-collector@example.org", hash, "L2 Collector", "QA", Role.COLLECTOR, clock.instant()));
        admin = users.save(new User("l2-admin@example.org", hash, "L2 Admin", "QA", Role.ADMIN, clock.instant()));
        // F2/F3: register() mandatorily wraps the content key for the registrant, which needs a provisioned
        // keypair - DevUserSeeder does this in production; a test that inserts users directly must do it too.
        userKeys.provision(collector.getId());
        userKeys.provision(admin.getId());
    }

    String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", "integration-test-password"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("accessToken").asString();
    }

    @Test
    void registerReadBackAndVerifyAgainstRealPostgresWithMockedIpfsAndFabric() throws Exception {
        String collectorToken = login(collector.getEmail());
        String adminToken = login(admin.getEmail());

        // E1: a case must exist first (E3 requires it for registration).
        String caseBody = mvc.perform(post("/api/cases").header("Authorization", "Bearer " + adminToken)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("caseNumber", "L2-CASE-1", "title", "L2 integration check",
                                "leadOfficerId", collector.getId().toString()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String caseId = json.readTree(caseBody).path("id").asString();

        // B1/B2/C1: register DIGITAL evidence with a real multipart upload.
        byte[] content = "the seized phone image, byte for byte, L2 integration check".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile metadataPart = new MockMultipartFile("metadata", "", "application/json",
                "{\"caseId\":\"L2-CASE-1\",\"type\":\"DIGITAL\",\"description\":\"L2 phone\"}".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile filePart = new MockMultipartFile("file", "phone.txt", "text/plain", content);

        String registerBody = mvc.perform(multipart("/api/evidence").file(metadataPart).file(filePart)
                        .header("Authorization", "Bearer " + collectorToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var registered = json.readTree(registerBody);
        String evidenceId = registered.path("evidenceId").asString();
        assertThat(evidenceId).matches("EV-[0-9a-f-]{36}");
        assertThat(registered.path("caseId").asString()).isEqualTo("L2-CASE-1");
        assertThat(registered.path("metadataAvailable").asBoolean()).isTrue();
        assertThat(registered.path("metadata").path("description").asString()).isEqualTo("L2 phone");

        // B3: read it back - a genuinely separate request, proving the write actually persisted.
        String fetchedBody = mvc.perform(get("/api/evidence/" + evidenceId).header("Authorization", "Bearer " + collectorToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var fetched = json.readTree(fetchedBody);
        assertThat(fetched.path("evidenceId").asString()).isEqualTo(evidenceId);
        assertThat(fetched.path("status").asString()).isEqualTo("COLLECTED");

        // C2: verify() re-fetches and re-hashes for real, against the FakeIpfsClient's stored bytes.
        String verifyBody = mvc.perform(get("/api/evidence/" + evidenceId + "/verify").header("Authorization", "Bearer " + collectorToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(verifyBody).path("status").asString()).isEqualTo("VERIFIED");

        // E3, real Postgres: the case now genuinely lists this evidence (a real foreign-keyed row, not a mock).
        String caseEvidenceBody = mvc.perform(get("/api/cases/" + caseId + "/evidence").header("Authorization", "Bearer " + collectorToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(caseEvidenceBody).contains(evidenceId);
    }

    @Test
    void aRequestWithNoTokenIsRejectedByTheRealSecurityFilterChain() throws Exception {
        mvc.perform(get("/api/evidence/EV-" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
}
