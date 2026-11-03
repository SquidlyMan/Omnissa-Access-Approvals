package com.omnissa.access.approval.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

@Entity
public class OmnissaServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotEmpty
    private String url;

    @NotEmpty
    private String clientId;

    /**
     * Accepted on POST and PUT, never written back out: the service client is
     * a Super Admin credential for the tenant, and until 1.23.5 every read of
     * the configuration returned it in clear. Required on POST; a PUT that
     * leaves it blank keeps the stored value (see ConfigController).
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String clientSecret;

    public OmnissaServer() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
}
