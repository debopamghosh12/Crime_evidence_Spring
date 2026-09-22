package com.blockevidence.backend.ledger;

import java.util.List;

import com.blockevidence.backend.config.FabricProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A2 (design section 3.5): the "wallet" component of /actuator/health. UNKNOWN when no wallet is configured at
 * all (the same "not built yet" honesty LedgerHealthIndicator uses for an unconfigured ledger); UP with a count
 * of zero when configured and nothing is expiring; DOWN (not just a warning) when any enrolled identity is
 * within 30 days of its certificate expiring, since an expired identity means that user's writes start failing
 * with LEDGER_IDENTITY_MISSING-shaped 403s with no code change - worth surfacing loudly, not silently.
 *
 * <p>Same profile as {@link FileWalletIdentityStore} (the only {@link IdentityStore} bean): the in-memory
 * reference ledger has no Fabric identity concept at all, so this component does not exist under it.
 */
@Component
@Profile("!memory-ledger")
public class WalletHealthIndicator implements HealthIndicator {

    private static final int WARN_WITHIN_DAYS = 30;

    private final IdentityStore identityStore;
    private final FabricProperties properties;

    public WalletHealthIndicator(IdentityStore identityStore, FabricProperties properties) {
        this.identityStore = identityStore;
        this.properties = properties;
    }

    @Override
    public Health health() {
        if (!properties.isWalletConfigured()) {
            return Health.unknown().withDetail("detail", "No wallet configured (FABRIC_WALLET_DIR); every write needs "
                    + "an enrolled identity and will answer 403 LEDGER_IDENTITY_MISSING").build();
        }
        List<String> expiring = identityStore.expiringWithin(WARN_WITHIN_DAYS);
        if (expiring.isEmpty()) {
            return Health.up().withDetail("detail", "No wallet identity expires within " + WARN_WITHIN_DAYS + " days").build();
        }
        return Health.down()
                .withDetail("detail", expiring.size() + " wallet identity(ies) expire within " + WARN_WITHIN_DAYS + " days; re-run the enrollment script for them")
                .withDetail("userIds", expiring)
                .build();
    }
}
