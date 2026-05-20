package com.omnissa.access.approval.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.util.CustomContentTypes;
import com.omnissa.access.approval.util.OmnissaRestClient;
import com.omnissa.access.approval.util.Paths;
import com.omnissa.access.approval.util.RestPreconditions;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class CatalogItemInterfaceImpl implements CatalogItemInterface {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public byte[] getResourceIconBytes(String catalogItemId) {
        OmnissaServer server = RestPreconditions.omnissaServerConfig();
        OmnissaRestClient restClient = new OmnissaRestClient(server);

        String reqBody = "{\"catalogItemIds\": [\"" + catalogItemId + "\"]}";
        HttpEntity<String> httpEntity = new HttpEntity<>(reqBody, CustomContentTypes.add(CustomContentTypes.CATALOG_SEARCH_BULK));

        ResponseEntity<String> response = restClient.exchange(
                RestPreconditions.omnissaServerBaseUrl() + Paths.CATALOG_SEARCH,
                HttpMethod.POST,
                httpEntity,
                String.class);

        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            String hwIconHref = root.at("/items/0/_links/hw-icon/href").asText();

            if (hwIconHref.startsWith("https://catalog.")) {
                return new RestTemplate().getForObject(hwIconHref, byte[].class);
            } else {
                return restClient.getForObject(RestPreconditions.omnissaServerBaseUrl() + hwIconHref, byte[].class);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
