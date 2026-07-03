package com.omnissa.access.approval.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;

import java.util.Date;

/**
 * A single audit-trail entry recording who did what to which approval request.
 * Persisted for the admin UI and mirrored to the "AUDIT" logger so entries
 * also flow into the file log, log bundle, and syslog export.
 */
@Entity
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // "timestamp" is a datatype keyword in H2 — use a safe column name.
    @Column(name = "event_timestamp")
    @Temporal(TemporalType.TIMESTAMP)
    private Date timestamp;

    private String adminUsername;

    private String action;

    private String requestId;

    private String resourceName;

    @Column(length = 1000)
    private String message;

    public AuditEvent() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Date getTimestamp() { return timestamp; }
    public void setTimestamp(Date timestamp) { this.timestamp = timestamp; }

    public String getAdminUsername() { return adminUsername; }
    public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
