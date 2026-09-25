package org.cardanofoundation.cip113.service;

import id.veridian.signify.cesr.util.Utils;
import id.veridian.signify.generated.keria.model.Credential;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeriAnchorAttachmentTest {

    @Test
    void preservesAnchorAttachmentFromKeriaJson() {
        Credential credential = Utils.fromJson("{\"ancatc\":[\"-AABsignature\"]}", Credential.class);

        assertEquals("-AABsignature", KeriService.requireAnchorAttachment(credential, "credential-said"));
    }

    @Test
    void refusesGrantWithoutAnchorAttachment() {
        for (List<String> attachments : List.of(
                List.<String>of(), List.of(""), List.of(" "), java.util.Arrays.asList((String) null))) {
            Credential credential = new Credential().ancatc(attachments);
            var error = assertThrows(IllegalStateException.class,
                    () -> KeriService.requireAnchorAttachment(credential, "credential-said"));
            assertTrue(error.getMessage().contains("grant was not submitted"));
        }
    }
}
