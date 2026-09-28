package org.cardanofoundation.cip113.util;

import com.fasterxml.jackson.core.type.TypeReference;
import id.veridian.signify.app.Exchanging;
import id.veridian.signify.app.Notifying;
import id.veridian.signify.app.clienting.SignifyClient;
import id.veridian.signify.cesr.util.Utils;
import id.veridian.signify.generated.keria.model.ExchangeResource;
import id.veridian.signify.generated.keria.model.Exn;
import id.veridian.signify.generated.keria.model.Notification;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class IpexNotificationHelperTest {

    private static final String GRANT = "grant-said";
    private static final String WALLET = "wallet-aid";
    private static final String ISSUER = "issuer-aid";

    @Test
    void remoteSignRefMustBelongToItsOwnRequest() {
        var ref = new Exn().d("note").r("/remotesign/ixn/ref")
                .p("mint-request").i(WALLET).rp(ISSUER);
        assertTrue(IpexNotificationHelper.matchesRemoteSignRef(ref, "note", "mint-request", WALLET, ISSUER));
        assertFalse(IpexNotificationHelper.matchesRemoteSignRef(ref, "note", "registration-request", WALLET, ISSUER));
        assertFalse(IpexNotificationHelper.matchesRemoteSignRef(ref, "note", "mint-request", "other", ISSUER));
    }

    @Test
    void acceptsOnlyTheAdmitForThisGrantAndLeavesOtherNotificationsUntouched() throws Exception {
        SignifyClient client = mock(SignifyClient.class);
        Notifying.Notifications notifications = mock(Notifying.Notifications.class);
        Exchanging.Exchanges exchanges = mock(Exchanging.Exchanges.class);
        when(client.notifications()).thenReturn(notifications);
        when(client.exchanges()).thenReturn(exchanges);
        when(notifications.list(0, 24)).thenReturn(page(0, 1, 2,
                note("unrelated", "/exn/ipex/admit") + "," + note("matching", "/ipex/admit")));
        when(exchanges.get("unrelated")).thenReturn(Optional.of(exchange("unrelated", "old-grant", WALLET, ISSUER)));
        when(exchanges.get("matching")).thenReturn(Optional.of(exchange("matching", GRANT, WALLET, ISSUER)));

        var match = IpexNotificationHelper.waitForAdmit(client, GRANT, WALLET, ISSUER, 1, 0);

        assertEquals("matching", match.a.d);
        verify(notifications, never()).mark("unrelated");
        verify(notifications, never()).delete("unrelated");
    }

    @Test
    void rejectsWrongSenderRecipientRouteAndDigest() throws Exception {
        checkSingle(exchange("note", GRANT, "other", ISSUER));
        checkSingle(exchange("note", GRANT, WALLET, "other"));
        checkSingle(exchange("other", GRANT, WALLET, ISSUER));
        checkSingle(new ExchangeResource()
                .exn(new Exn().d("note").r("/ipex/offer").p(GRANT).i(WALLET).rp(ISSUER)));
        checkSingle(new ExchangeResource()
                .exn(new Exn().d("note").r("/ipex/admit").i(WALLET).rp(ISSUER)));
    }

    @Test
    void findsMatchingAdmitBeyondTheFirstTwentyFiveNotifications() throws Exception {
        SignifyClient client = mock(SignifyClient.class);
        Notifying.Notifications notifications = mock(Notifying.Notifications.class);
        Exchanging.Exchanges exchanges = mock(Exchanging.Exchanges.class);
        when(client.notifications()).thenReturn(notifications);
        when(client.exchanges()).thenReturn(exchanges);
        String firstPage = IntStream.range(0, 25)
                .mapToObj(i -> note("old-" + i, "/exn/ipex/offer"))
                .reduce((a, b) -> a + "," + b).orElseThrow();
        when(notifications.list(0, 24)).thenReturn(page(0, 24, 26, firstPage));
        when(notifications.list(25, 49)).thenReturn(page(25, 25, 26,
                note("matching", "/exn/ipex/admit")));
        when(exchanges.get("matching")).thenReturn(Optional.of(exchange("matching", GRANT, WALLET, ISSUER)));

        assertEquals("matching", IpexNotificationHelper.waitForAdmit(
                client, GRANT, WALLET, ISSUER, 1, 0).a.d);
        verify(notifications).list(25, 49);
    }

    @Test
    void retriesAnExchangeThatIsNotYetAvailable() throws Exception {
        SignifyClient client = mock(SignifyClient.class);
        Notifying.Notifications notifications = mock(Notifying.Notifications.class);
        Exchanging.Exchanges exchanges = mock(Exchanging.Exchanges.class);
        when(client.notifications()).thenReturn(notifications);
        when(client.exchanges()).thenReturn(exchanges);
        when(notifications.list(anyInt(), anyInt())).thenReturn(page(0, 0, 1,
                note("matching", "/exn/ipex/admit")));
        when(exchanges.get("matching")).thenReturn(Optional.empty(),
                Optional.of(exchange("matching", GRANT, WALLET, ISSUER)));

        assertEquals("matching", IpexNotificationHelper.waitForAdmit(
                client, GRANT, WALLET, ISSUER, 2, 0).a.d);
        verify(exchanges, times(2)).get("matching");
    }

    private void checkSingle(ExchangeResource resource) throws Exception {
        SignifyClient client = mock(SignifyClient.class);
        Notifying.Notifications notifications = mock(Notifying.Notifications.class);
        Exchanging.Exchanges exchanges = mock(Exchanging.Exchanges.class);
        when(client.notifications()).thenReturn(notifications);
        when(client.exchanges()).thenReturn(exchanges);
        when(notifications.list(0, 24)).thenReturn(page(0, 0, 1,
                note("note", "/exn/ipex/admit")));
        when(exchanges.get("note")).thenReturn(Optional.of(resource));
        assertThrows(RuntimeException.class, () -> IpexNotificationHelper.waitForAdmit(
                client, GRANT, WALLET, ISSUER, 1, 0));
        verify(notifications, never()).mark("note");
    }

    private static ExchangeResource exchange(String said, String grant, String sender, String recipient) {
        return new ExchangeResource().exn(new Exn().d(said).r("/ipex/admit")
                .p(grant).i(sender).rp(recipient));
    }

    private static Notifying.Notifications.NotificationListResponse page(
            int start, int end, int total, String notes) {
        // Exercise the SDK's new typed response with KERIA's actual JSON shape.
        var parsed = Utils.fromJson("[" + notes + "]", new TypeReference<java.util.List<Notification>>() {});
        return new Notifying.Notifications.NotificationListResponse(start, end, total, parsed);
    }

    private static String note(String said, String route) {
        return "{\"i\":\"" + said + "\",\"r\":false,\"a\":{\"r\":\""
                + route + "\",\"d\":\"" + said + "\"}}";
    }
}
