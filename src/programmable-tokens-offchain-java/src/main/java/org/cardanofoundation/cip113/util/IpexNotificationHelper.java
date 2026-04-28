package org.cardanofoundation.cip113.util;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.signify.app.Notifying;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.cesr.util.Utils;

import java.util.List;

@Slf4j
public class IpexNotificationHelper {

    private static final int MAX_RETRIES = 20;
    private static final long POLL_INTERVAL_MS = 2000;

    public static Notification waitForNotification(SignifyClient client, String route) throws Exception {
        return waitForNotification(client, route, route.startsWith("/exn/") ? route.substring(4) : "/exn" + route);
    }

    /**
     * Wait for any notification whose body's r field matches one of the accepted routes.
     * KERIA prefixes inbound exn routes with "/exn/" when surfacing them as notifications,
     * so we typically pass both the bare route and the /exn/-prefixed variant.
     */
    public static Notification waitForNotification(SignifyClient client, String... acceptedRoutes) throws Exception {
        var accepted = List.of(acceptedRoutes);
        for (int i = 0; i < MAX_RETRIES; i++) {
            Notifying.Notifications.NotificationListResponse response = client.notifications().list();
            List<Notification> notes = Utils.fromJson(response.notes(), new TypeReference<>() {});

            var matching = notes.stream()
                    .filter(n -> n.a != null && accepted.contains(n.a.r) && !Boolean.TRUE.equals(n.r))
                    .findFirst();

            if (matching.isPresent()) {
                log.debug("Received notification for route={}", matching.get().a.r);
                return matching.get();
            }

            log.info("Waiting for notification: {} (attempt {}/{})", accepted, i + 1, MAX_RETRIES);
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new RuntimeException("Timed out waiting for notification: " + accepted);
    }

    public static void markAndDelete(SignifyClient client, Notification note) throws Exception {
        client.notifications().mark(note.i);
        client.notifications().delete(note.i);
    }

    public static class Notification {
        public String i;
        public Boolean r;
        public NotificationBody a;

        public static class NotificationBody {
            public String r;
            public String d;
        }
    }
}
