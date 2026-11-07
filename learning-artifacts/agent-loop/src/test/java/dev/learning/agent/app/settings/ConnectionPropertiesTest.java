package dev.learning.agent.app.settings;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConnectionPropertiesTest {
    private final ApplicationContextRunner runner =
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application.yml",
                "DEEPSEEK_API_KEY=test-deepseek-sentinel", "DEEPSEEK_BASE_URL=https://api.deepseek.com", "EXA_AI_KEY=",
                "EXA_API_KEY=")
            .withUserConfiguration(BindingConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({DeepSeekProperties.class, ExaProperties.class})
    static class BindingConfiguration {}

    @Test
    void yamlBindsConnectionSettingsAndOptionalSearchCredentials() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var deepSeek = context.getBean(DeepSeekProperties.class);
            assertEquals("test-deepseek-sentinel", deepSeek.apiKey());
            assertEquals("https://api.deepseek.com", deepSeek.baseUrl());
            assertEquals("", context.getBean(ExaProperties.class).apiKey());
        });
    }

    @Test
    void springOverridesTakePrecedenceOverEnvironmentPlaceholders() {
        runner
            .withPropertyValues("agent.deepseek.api-key=external-sentinel",
                "agent.deepseek.base-url=https://gateway.example.com/v1", "agent.exa.api-key=search-sentinel")
            .run(context -> {
                assertNull(context.getStartupFailure());
                var deepSeek = context.getBean(DeepSeekProperties.class);
                assertEquals("external-sentinel", deepSeek.apiKey());
                assertEquals("https://gateway.example.com/v1", deepSeek.baseUrl());
                assertEquals("search-sentinel", context.getBean(ExaProperties.class).apiKey());
            });
    }

    @Test
    void blankRequiredKeyPreventsStartup() {
        runner.withPropertyValues("agent.deepseek.api-key= ").run(context -> {
            assertNotNull(context.getStartupFailure());
        });
    }

    @Test
    void blankBaseUrlPreventsStartup() {
        runner.withPropertyValues("agent.deepseek.base-url= ").run(context -> {
            assertNotNull(context.getStartupFailure());
        });
    }

    @Test
    void primaryExaKeyTakesPrecedence() {
        runner.withPropertyValues("EXA_AI_KEY=primary-sentinel", "EXA_API_KEY=fallback-sentinel").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("primary-sentinel", context.getBean(ExaProperties.class).apiKey());
        });
    }

    @Test
    void blankPrimaryExaKeyUsesLegacyFallback() {
        runner.withPropertyValues("EXA_AI_KEY= ", "EXA_API_KEY=fallback-sentinel").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("fallback-sentinel", context.getBean(ExaProperties.class).apiKey());
        });
    }

    @Test
    void propertyDescriptionsNeverIncludeCredentials() {
        assertFalse(new DeepSeekProperties("secret-sentinel", "https://api.deepseek.com").toString()
            .contains("secret-sentinel"));
        assertFalse(new ExaProperties("primary-sentinel", "fallback-sentinel").toString().contains("primary-sentinel"));
        assertFalse(
            new ExaProperties("primary-sentinel", "fallback-sentinel").toString().contains("fallback-sentinel"));
    }
}
