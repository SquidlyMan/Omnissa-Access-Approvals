package com.omnissa.access.approval.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonBodiesTest {

    @Test
    @DisplayName("a catalog item id carrying JSON syntax is one string in the body, not extra fields")
    void idIsSerialisedNotConcatenated() throws Exception {
        String hostile = "abc\"],\"catalogItemIds\":[\"other";
        JsonNode body = new ObjectMapper().readTree(JsonBodies.catalogSearch(hostile));
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get("catalogItemIds")).hasSize(1);
        assertThat(body.get("catalogItemIds").get(0).asText()).isEqualTo(hostile);
    }
}
