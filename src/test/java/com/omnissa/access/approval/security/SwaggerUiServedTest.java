package com.omnissa.access.approval.security;

import com.omnissa.access.approval.update.RegistryClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API reference is served, to a signed-in user only, from the swagger-ui
 * webjar folder that {@code springdoc.swagger-ui.version} names. Without a
 * session it answers with a redirect to the login page: the OpenAPI
 * description maps every endpoint, which is the first thing an automated
 * attacker fetches. springdoc pins that
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
    @WithMockUser(roles = "VIEWER")
    @DisplayName("the API reference page, its scripts and the OpenAPI description are served to a signed-in user")
    void pageAndScriptsAreServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/swagger-initializer.js")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/swagger-ui-bundle.js")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/swagger-ui.html", "/swagger-ui/index.html", "/swagger-ui/swagger-initializer.js",
            "/v3/api-docs", "/v3/api-docs/swagger-config"
    })
    @DisplayName("without a session the API reference redirects to the login page")
    void anonymousIsSentToLogin(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
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
