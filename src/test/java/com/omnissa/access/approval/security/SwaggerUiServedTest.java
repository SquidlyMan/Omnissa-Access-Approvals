package com.omnissa.access.approval.security;

import com.omnissa.access.approval.update.RegistryClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API reference is served without a session from the swagger-ui webjar
 * folder that {@code springdoc.swagger-ui.version} names. springdoc pins that
 * version in its own {@code springdoc.config.properties}, so a {@code pom.xml}
 * override of the webjar without the matching property leaves the page at
 * 404 while {@code /v3/api-docs} keeps working — which is how 1.23.5 shipped.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:swagger-ui-served;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "omnissa.api.username=",
        "omnissa.bootstrap.url="
})
class SwaggerUiServedTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    RegistryClient registryClient;

    @Value("${springdoc.swagger-ui.version}")
    private String configuredVersion;

    @Test
    @DisplayName("the API reference page and its scripts are served without a session")
    void pageAndScriptsAreServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/swagger-initializer.js")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/swagger-ui-bundle.js")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("springdoc.swagger-ui.version names the webjar on the classpath")
    void configuredVersionMatchesTheWebjar() throws Exception {
        Properties pom = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/META-INF/maven/org.webjars/swagger-ui/pom.properties")) {
            assertThat(in).as("swagger-ui webjar pom.properties on the classpath").isNotNull();
            pom.load(in);
        }
        assertThat(configuredVersion)
                .as("springdoc.swagger-ui.version must match the pinned org.webjars:swagger-ui")
                .isEqualTo(pom.getProperty("version"));
    }
}
