package org.cardanofoundation.cip113.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code CIP113_ALLOW_NO_DEPLOYMENT} is the env var that turns the flag on.
 *
 * <p><strong>Why this is a test and not a comment.</strong> The deployment chart has to set this
 * flag, and the only spelling it could use before this existed was one nobody had written down:
 * the flag lived solely as a {@code @Value} default in {@link ProtocolBootstrapService}, with no
 * {@code cip113:} block in application.yaml. Relaxed binding does reach a {@code @Value}
 * placeholder from the environment, but which spelling it accepts is a property of Spring's
 * {@link SystemEnvironmentPropertySource}, not of this repo — so two people reasoning about it
 * from memory reached two different answers ({@code CIP113_ALLOW_NO_DEPLOYMENT} vs
 * {@code CIP113_ALLOWNODEPLOYMENT}), and a wrong guess in a values file is SILENT: the service
 * refuses to start and the flag that was supposed to prevent that looks set.
 *
 * <p>This resolves the question by exercising the real path — the shipped application.yaml plus
 * the real environment property source — rather than by asserting a belief about either.
 *
 * <p>⛔ It reads {@code application.yaml} FROM THE CLASSPATH ON PURPOSE. A fixture copy would
 * keep passing after somebody deleted the block it is meant to protect.
 */
class AllowNoDeploymentEnvVarTest {

    private static final String PROPERTY = "cip113.allow-no-deployment";
    private static final String ENV_VAR = "CIP113_ALLOW_NO_DEPLOYMENT";

    /**
     * The shipped application.yaml's default document (the one above the first profile), with a
     * real environment property source layered over it exactly as Spring Boot layers them.
     */
    private static StandardEnvironment environmentWith(Map<String, Object> systemEnvironment)
            throws IOException {
        var documents = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));
        assertTrue(!documents.isEmpty(), "application.yaml loaded no documents");

        var environment = new StandardEnvironment();
        var sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        // The environment wins over the file, which is what makes the file's ${...} a default.
        sources.addFirst(documents.get(0));
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, systemEnvironment));
        return environment;
    }

    @Test
    @DisplayName("CIP113_ALLOW_NO_DEPLOYMENT=true turns the flag on")
    void envVarTurnsItOn() throws IOException {
        var environment = environmentWith(Map.of(ENV_VAR, "true"));

        assertEquals("true", environment.getProperty(PROPERTY),
                ENV_VAR + " did not reach " + PROPERTY + ". A chart setting this env var would be "
                + "silently inert and the service would refuse to start with the flag apparently on.");
        assertEquals(Boolean.TRUE, environment.getProperty(PROPERTY, Boolean.class),
                "the value must coerce to the boolean the @Value field declares");
    }

    @Test
    @DisplayName("with the env var unset the flag stays off — refusing to start is still the default")
    void defaultsToOff() throws IOException {
        var environment = environmentWith(Map.of());

        assertEquals(Boolean.FALSE, environment.getProperty(PROPERTY, Boolean.class),
                "the default must remain false: a service that indexes a protocol instance should "
                + "refuse to start when no deployment is recorded unless told otherwise");
    }

    @Test
    @DisplayName("application.yaml states the env var name rather than leaving it to relaxed binding")
    void theFileNamesTheEnvVar() throws IOException {
        var yaml = new String(new ClassPathResource("application.yaml").getInputStream().readAllBytes());

        // The point of the block: the chart reads the name off this file instead of inferring it.
        assertTrue(yaml.contains("${" + ENV_VAR + ":false}"),
                "application.yaml must declare " + PROPERTY + " as ${" + ENV_VAR + ":false}, the way "
                + "every other toggle in that file is declared. Without it the name is implied by "
                + "Spring's relaxed binding and appears nowhere a deployer can read it.");
    }

    /**
     * What relaxed binding accepts, with NO {@code cip113:} block anywhere — the state of the
     * repo at f01c5c9.
     *
     * <p>⚑ THIS IS THE ANSWER TO THE DISAGREEMENT that prompted the block above, and it is
     * recorded because both plausible answers were argued from memory. Spring's
     * {@link SystemEnvironmentPropertySource} maps a dotted, dashed property name onto an env var
     * by replacing BOTH dots and dashes with underscores — so {@code CIP113_ALLOW_NO_DEPLOYMENT}
     * already worked, and the dash-removed {@code CIP113_ALLOWNODEPLOYMENT} is NOT the name.
     *
     * <p>So the yaml block is a READABILITY change, not a fix: it did not make a broken env var
     * work, it wrote down a working one. Anything relying on it having been broken — a chart held
     * back waiting for an image, a SPRING_APPLICATION_JSON workaround kept "until it lands" — was
     * relying on something untrue.
     */
    @Test
    @DisplayName("relaxed binding already accepted this name before the block existed")
    void relaxedBindingAlreadyAcceptedIt() {
        var environment = new StandardEnvironment();
        var sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        // No yaml at all: only the environment, as it was before the cip113 block was added.
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of(ENV_VAR, "true")));

        assertEquals(Boolean.TRUE, environment.getProperty(PROPERTY, Boolean.class),
                "dots AND dashes both become underscores, so " + ENV_VAR + " resolves "
                + PROPERTY + " with no yaml block present");

        // And the spelling that was feared to be the required one is not a property name at all.
        var dashRemoved = new StandardEnvironment();
        dashRemoved.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        dashRemoved.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("CIP113_ALLOWNODEPLOYMENT", "true")));
        assertEquals(null, dashRemoved.getProperty(PROPERTY),
                "CIP113_ALLOWNODEPLOYMENT must NOT resolve " + PROPERTY + "; if this ever starts "
                + "passing, Spring changed its env-var mapping and the block above became load-bearing");
    }
}
