package com.omnissa.access.approval.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LicenseInfo {

    private String licenseSubscriptionType;
    private String licenseType;
    private Integer costPerLicense;
    private Integer licenseLimit;

    public LicenseInfo() {}

    public String getLicenseSubscriptionType() { return licenseSubscriptionType; }
    public void setLicenseSubscriptionType(String licenseSubscriptionType) { this.licenseSubscriptionType = licenseSubscriptionType; }

    public String getLicenseType() { return licenseType; }
    public void setLicenseType(String licenseType) { this.licenseType = licenseType; }

    public Integer getCostPerLicense() { return costPerLicense; }
    public void setCostPerLicense(Integer costPerLicense) { this.costPerLicense = costPerLicense; }

    public Integer getLicenseLimit() { return licenseLimit; }
    public void setLicenseLimit(Integer licenseLimit) { this.licenseLimit = licenseLimit; }
}
