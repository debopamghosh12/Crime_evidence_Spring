package com.blockevidence.backend.ledger;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * G4: the "ledger" component of /actuator/health (the bean name minus "HealthIndicator" becomes the
 * component name). Goes through LedgerService.health(), never around it (C-01).
 */
@Component
public class LedgerHealthIndicator implements HealthIndicator {

    private final LedgerService ledgerService;

    public LedgerHealthIndicator(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Override
    public Health health() {
        LedgerHealth report = ledgerService.health();
        Health.Builder builder = switch (report.state()) {
            case UP -> Health.up();
            case DOWN -> Health.down();
            // Spring's default aggregation ignores UNKNOWN while other components are UP, so an
            // unbuilt ledger does not turn the whole application red.
            case UNKNOWN -> Health.unknown();
        };
        return builder.withDetail("detail", report.detail()).build();
    }
}
