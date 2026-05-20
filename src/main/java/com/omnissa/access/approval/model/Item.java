package com.omnissa.access.approval.model;

import com.fasterxml.jackson.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Item {

    private String catalogItemType;
    private String name;
    private String productVersion;
    private String uuid;
    private String packageVersion;
    private String description;
    private List<Labels> labels = new ArrayList<>();

    @JsonProperty(value = "_links")
    private Links links;

    public Item() {}

    public String getCatalogItemType() { return catalogItemType; }
    public void setCatalogItemType(String catalogItemType) { this.catalogItemType = catalogItemType; }

    public Links getLinks() { return links; }
    public void setLinks(Links links) { this.links = links; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProductVersion() { return productVersion; }
    public void setProductVersion(String productVersion) { this.productVersion = productVersion; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getPackageVersion() { return packageVersion; }
    public void setPackageVersion(String packageVersion) { this.packageVersion = packageVersion; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<Labels> getLabels() { return labels; }
    public void setLabels(List<Labels> labels) { this.labels = labels; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Labels {
        private String name;
        public Labels() {}
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Links {
        public Links() {}

        @JsonIgnore
        private Map<String, Object> additionalProperties = new HashMap<>();

        @JsonAnyGetter
        public Map<String, Object> getAdditionalProperties() { return additionalProperties; }

        @JsonAnySetter
        public void setAdditionalProperty(String name, Object value) { additionalProperties.put(name, value); }

        @JsonProperty(value = "hw-icon")
        private HwIcon hwIcon;

        public HwIcon getHwIcon() { return hwIcon; }
        public void setHwIcon(HwIcon hwIcon) { this.hwIcon = hwIcon; }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class HwIcon {
            private String href;
            public HwIcon() {}
            public String getHref() { return href; }
            public void setHref(String href) { this.href = href; }
        }
    }
}
