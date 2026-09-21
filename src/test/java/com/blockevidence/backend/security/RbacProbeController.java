package com.blockevidence.backend.security;

import com.blockevidence.backend.ledger.LedgerNotImplementedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEST-ONLY. Lives under src/test so no throwaway endpoint ships in the application. Gives the
 * Phase 1 RBAC scaffolding (A3) and error format (K1) something to be exercised against, since
 * Phase 1 has no real protected business endpoints yet.
 */
@RestController
@RequestMapping("/test-probe")
public class RbacProbeController {

    @GetMapping("/authenticated")
    public String anyAuthenticated() {
        return "ok";
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminOnly() {
        return "ok";
    }

    @GetMapping("/judge-or-auditor")
    @PreAuthorize("hasAnyRole('JUDGE', 'AUDITOR')")
    public String judgeOrAuditor() {
        return "ok";
    }

    @GetMapping("/boom")
    public String boom() {
        throw new IllegalStateException("secret internal detail: jdbc:postgresql://db/prod");
    }

    @GetMapping("/not-implemented")
    public String stub() {
        throw new LedgerNotImplementedException("createEvidence");
    }
}
