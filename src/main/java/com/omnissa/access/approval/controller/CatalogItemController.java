package com.omnissa.access.approval.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnissa.access.approval.interfaces.CatalogItemInterface;
import com.omnissa.access.approval.model.LicenseInfo;
import com.omnissa.access.approval.model.Mappings;
import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.util.OmnissaRestClient;
import com.omnissa.access.approval.util.Paths;
import com.omnissa.access.approval.util.RestPreconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping(value = Mappings.CATALOG_ITEMS)
public class CatalogItemController {

    private static final Logger logger = LoggerFactory.getLogger(CatalogItemController.class);

    @Autowired
    private CatalogItemInterface catalogItemInterface;

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getCatalogItemInfo(@PathVariable String id) {
        OmnissaServer server = RestPreconditions.omnissaServerConfig();
        OmnissaRestClient client = new OmnissaRestClient(server);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/vnd.vmware.horizon.manager.catalog.search.bulk+json");

        String reqBody = "{\"catalogItemIds\": [\"" + id + "\"]}";
        HttpEntity<String> httpEntity = new HttpEntity<>(reqBody, headers);

        logger.info("Catalog search for id={}", id);

        ResponseEntity<String> response = client.exchange(
                RestPreconditions.omnissaServerBaseUrl() + Paths.CATALOG_SEARCH,
                HttpMethod.POST,
                httpEntity,
                String.class);

        return ResponseEntity.ok(response.getBody());
    }

    @GetMapping("/{id}/license")
    public ResponseEntity<?> getResourceLicenseInfo(@PathVariable String id) throws IOException {
        OmnissaServer server = RestPreconditions.omnissaServerConfig();
        OmnissaRestClient client = new OmnissaRestClient(server);

        ResponseEntity<String> response = client.exchange(
                RestPreconditions.omnissaServerBaseUrl() + Paths.LICENSE,
                HttpMethod.GET,
                null,
                String.class,
                id);

        LicenseInfo info = new ObjectMapper().readValue(response.getBody(), LicenseInfo.class);
        return ResponseEntity.ok(info);
    }

    @GetMapping(value = "/{id}/icon", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getResourceIcon(@PathVariable String id) {
        return ResponseEntity.ok(catalogItemInterface.getResourceIconBytes(id));
    }
}
