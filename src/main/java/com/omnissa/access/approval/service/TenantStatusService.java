package com.omnissa.access.approval.service;

import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.repository.OmnissaServerRepository;
import com.omnissa.access.approval.util.OmnissaRestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Whether Omnissa Access is reachable, memoized.
 *
 * <p>Extracted from {@code ConfigController} so the dashboard tile and the
 * health endpoints share one probe and one cache. Left in place, each caller
 * kept its own — a monitor polling health every minute would have doubled the
 * token requests against the tenant while still showing the dashboard a
 * separately-aged answer.
 *
 * <p>The cache is what makes an external monitor safe here: the probe costs a
 * client_credentials token fetch, so the poll interval must not decide how often
 * the tenant is called.
 */
@Service
public class TenantStatusService {

    private static final long CACHE_MILLIS = 60_000L;

    @Autowired
    private OmnissaServerRepository repository;

    private volatile TenantStatus cached;
    private volatile long checkedAt;

    /**
     * @param configured false when no tenant has been set up yet — distinct from
     *                   configured-but-unreachable, which is a fault
     */
    public record TenantStatus(boolean configured, boolean reachable, String tenantUrl,
                               String error, String checkedAt) {
    }

    public TenantStatus current() {
        long now = System.currentTimeMillis();
        TenantStatus snapshot = cached;
        if (snapshot != null && now - checkedAt < CACHE_MILLIS) {
            return snapshot;
        }
        TenantStatus fresh = probe();
        cached = fresh;
        checkedAt = now;
        return fresh;
    }

    private TenantStatus probe() {
        String stamp = Instant.now().toString();
        try {
            if (repository.count() == 0) {
                return new TenantStatus(false, false, "", "Not configured", stamp);
            }
            OmnissaServer server = repository.findAll().get(0);
            String url = server.getUrl() != null ? server.getUrl() : "";
            new OmnissaRestClient(server).checkToken();
            return new TenantStatus(true, true, url, null, stamp);
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            String url = "";
            try {
                if (repository.count() > 0) {
                    OmnissaServer server = repository.findAll().get(0);
                    url = server.getUrl() != null ? server.getUrl() : "";
                }
            } catch (Exception ignored) {
                // Reporting the failure matters more than naming the tenant.
            }
            return new TenantStatus(true, false, url, message, stamp);
        }
    }
}
