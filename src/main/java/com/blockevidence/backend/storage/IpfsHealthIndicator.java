package com.blockevidence.backend.storage;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** G4: the "ipfs" component of /actuator/health. Goes through IpfsClient, never around it. */
@Component
public class IpfsHealthIndicator implements HealthIndicator {

    private final IpfsClient ipfsClient;

    public IpfsHealthIndicator(IpfsClient ipfsClient) {
        this.ipfsClient = ipfsClient;
    }

    @Override
    public Health health() {
        return ipfsClient.isReachable() ? Health.up().build() : Health.down().withDetail("detail", "IPFS node not reachable").build();
    }
}
