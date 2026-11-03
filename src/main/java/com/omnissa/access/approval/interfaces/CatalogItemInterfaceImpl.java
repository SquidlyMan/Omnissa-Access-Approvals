package com.omnissa.access.approval.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnissa.access.approval.util.JsonBodies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(CatalogItemInterfaceImpl.class);

    /** Connect in 5 s, answer within 10 s, or give the icon up. */
    private static final RestTemplate ICON_CLIENT = iconClient();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static RestTemplate iconClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return new RestTemplate(factory);
    }

    @Override
    public byte[] getResourceIconBytes(String catalogItemId) {
        OmnissaServer server = RestPreconditions.omnissaServerConfig();
        OmnissaRestClient restClient = new OmnissaRestClient(server);

        String reqBody = JsonBodies.catalogSearch(catalogItemId);
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
                // The catalog CDN needs no token; the client is bounded so a
                // stalled CDN cannot pin a request thread for good.
                return ICON_CLIENT.getForObject(hwIconHref, byte[].class);
            } else {
                return restClient.getForObject(RestPreconditions.omnissaServerBaseUrl() + hwIconHref, byte[].class);
            }
        } catch (Exception e) {
            logger.warn("Icon for catalog item {} could not be fetched: {}", catalogItemId, e.toString());
            return null;
        }
    }
}
