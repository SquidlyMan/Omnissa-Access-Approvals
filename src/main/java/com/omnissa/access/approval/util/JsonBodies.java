package com.omnissa.access.approval.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Request bodies sent to Access, serialised rather than concatenated.
 *
 * <p>A catalog item id reaches these from a path variable or from the callout,
 * and until 1.23.5 it was dropped into a JSON string by hand: an id carrying a
 * quote became extra fields in the search the tool's bearer token then sent.
 */
public final class JsonBodies {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonBodies() {
    }

    /** The bulk catalog search body for one item. */
    public static String catalogSearch(String catalogItemId) {
        try {
            return MAPPER.writeValueAsString(Map.of("catalogItemIds", List.of(catalogItemId)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise the catalog search body", e);
        }
    }
}
