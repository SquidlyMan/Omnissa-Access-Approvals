package com.omnissa.access.approval.update;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

import java.util.Date;

/**
 * What the last registry check found — one row, keyed {@link #SINGLETON_ID}.
 *
 * <p>Persisted rather than held in memory for two reasons. The console must
 * show the last-known state through a registry outage rather than an error,
 * which needs somewhere to keep it. And "notify on transition only" needs a
 * durable memory of what was already announced: this feature causes container
 * restarts, and an in-memory record would re-announce the same version after
 * every one of them.
 */
@Entity
public class UpdateStatus {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    private Date lastCheckedAt;

    /** Newest release tag the registry advertised on the last successful check. */
    private String newestVersion;

    /** Set when the last check failed; cleared by the next success. */
    @Column(length = 500)
    private String lastError;

    /** The version most recently announced to the notifiers — see UpdateNotifier. */
    private String lastNotifiedVersion;

    /** Every release tag seen, newest first, comma-separated. Feeds the version picker. */
    @Lob
    private String knownVersions;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Date getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Date lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public String getNewestVersion() { return newestVersion; }
    public void setNewestVersion(String newestVersion) { this.newestVersion = newestVersion; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getLastNotifiedVersion() { return lastNotifiedVersion; }
    public void setLastNotifiedVersion(String lastNotifiedVersion) { this.lastNotifiedVersion = lastNotifiedVersion; }
    public String getKnownVersions() { return knownVersions; }
    public void setKnownVersions(String knownVersions) { this.knownVersions = knownVersions; }
}
