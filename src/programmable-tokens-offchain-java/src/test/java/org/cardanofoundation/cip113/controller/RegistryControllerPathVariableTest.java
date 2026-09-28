package org.cardanofoundation.cip113.controller;

import org.cardanofoundation.cip113.entity.RegistryNodeEntity;
import org.cardanofoundation.cip113.service.ProtocolParamsService;
import org.cardanofoundation.cip113.service.RegistryService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two single-token registry lookups, exercised as HTTP requests.
 *
 * <p><b>Why this exists.</b> Both declared a path template named {@code blacklistNodePolicyId}
 * and bound a method parameter named {@code policyId}. Spring resolves an unannotated
 * {@code @PathVariable} by NAME, so the two never met and neither endpoint could serve a request —
 * and nothing noticed, because no client in this repository has ever called {@code /registry/*}.
 * The registry page is the first consumer, which is how it surfaced.
 *
 * <p>These are deliberately standalone MockMvc tests: no Spring Boot context, no database, no
 * network. The defect is in the request-mapping layer, so that is the only layer that needs to be
 * real — and a slice that needed a database would not have been run often enough to catch it.
 *
 * <p>The naming also mattered on its own: the template said {@code blacklistNodePolicyId} while
 * both handlers take a TOKEN policy id. A blacklist node policy is a different value entirely, so
 * the template was misleading even where it was not broken.
 */
class RegistryControllerPathVariableTest {

    private static final String TOKEN_POLICY_ID =
            "5584c807a05704c35546602d1be1bcb0ee45e92637295c39e901cb6e";

    /**
     * The class-level mapping is {@code @RequestMapping("${apiPrefix}/registry")}, and standalone
     * MockMvc has no Environment to resolve that against — without a value supplied here every
     * request fails on the placeholder before it ever reaches a handler, which looks like a
     * routing defect and is not one.
     */
    private MockMvc mvc(RegistryService registry) {
        return MockMvcBuilders
                .standaloneSetup(new RegistryController(registry, Mockito.mock(ProtocolParamsService.class)))
                .addPlaceholderValue("apiPrefix", "/api/v1")
                .build();
    }

    @Test
    void lookingUpAKnownTokenReturnsIt() throws Exception {
        var registry = Mockito.mock(RegistryService.class);
        var entity = RegistryNodeEntity.builder()
                .key(TOKEN_POLICY_ID)
                .next("ff".repeat(30))
                .build();
        Mockito.when(registry.getByKey(TOKEN_POLICY_ID)).thenReturn(Optional.of(entity));

        mvc(registry).perform(get("/api/v1/registry/token/" + TOKEN_POLICY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(TOKEN_POLICY_ID));
    }

    @Test
    void lookingUpAnUnknownTokenIsANotFound() throws Exception {
        var registry = Mockito.mock(RegistryService.class);
        Mockito.when(registry.getByKey(anyString())).thenReturn(Optional.empty());

        mvc(registry).perform(get("/api/v1/registry/token/" + TOKEN_POLICY_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void theRegistrationCheckAnswersForAKnownToken() throws Exception {
        var registry = Mockito.mock(RegistryService.class);
        Mockito.when(registry.isTokenRegistered(TOKEN_POLICY_ID)).thenReturn(true);

        mvc(registry).perform(get("/api/v1/registry/is-registered/" + TOKEN_POLICY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(true));
    }

    @Test
    void theRegistrationCheckAnswersForAnUnknownToken() throws Exception {
        var registry = Mockito.mock(RegistryService.class);
        Mockito.when(registry.isTokenRegistered(anyString())).thenReturn(false);

        mvc(registry).perform(get("/api/v1/registry/is-registered/" + TOKEN_POLICY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(false));
    }
}
