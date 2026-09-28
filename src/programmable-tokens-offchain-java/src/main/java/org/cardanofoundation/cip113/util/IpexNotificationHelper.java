package org.cardanofoundation.cip113.util;

import lombok.extern.slf4j.Slf4j;
import id.veridian.signify.app.Notifying;
import id.veridian.signify.app.clienting.SignifyClient;
import id.veridian.signify.generated.keria.model.Exn;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Slf4j
public class IpexNotificationHelper {

    private static final int MAX_RETRIES = 20;
    private static final int ADMIT_MAX_RETRIES = 60;
    private static final long POLL_INTERVAL_MS = 2000;
    private static final int PAGE_SIZE = 25;

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
            List<Notification> notes = toNotifications(response.notes());

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

    /**
     * Wait for the wallet's admit of one specific grant. KERIA notifications only
     * contain a route and exchange SAID, so the referenced exchange must be checked
     * before it can acknowledge this issuance. Unrelated notifications are retained.
     */
    public static Notification waitForAdmit(SignifyClient client, String grantSaid,
                                            String walletAid, String issuerAid) throws Exception {
        return waitForAdmit(client, grantSaid, walletAid, issuerAid, ADMIT_MAX_RETRIES, POLL_INTERVAL_MS);
    }

    /** Preserve unrelated remote-sign replies when registration and mint approvals overlap. */
    public static Notification waitForRemoteSignRef(SignifyClient client, String requestSaid,
                                                    String walletAid, String issuerAid) throws Exception {
        for (int attempt = 0; attempt < ADMIT_MAX_RETRIES; attempt++) {
            int start = 0;
            int total = Integer.MAX_VALUE;
            while (start < total) {
                var response = client.notifications().list(start, start + PAGE_SIZE - 1);
                total = Math.min(total, response.total());
                List<Notification> notes = toNotifications(response.notes());
                if (notes.isEmpty()) break;
                for (Notification note : notes) {
                    if (note.i == null || Boolean.TRUE.equals(note.r) || note.a == null
                            || note.a.d == null || !isRemoteSignRoute(note.a.r)) continue;
                    try {
                        var exchange = client.exchanges().get(note.a.d);
                        if (exchange.isPresent() && matchesRemoteSignRef(exchange.get().getExn(), note.a.d,
                                requestSaid, walletAid, issuerAid)) return note;
                    } catch (RuntimeException badNotification) {
                        log.warn("Could not inspect remote-sign exchange said={}; retaining notification",
                                note.a.d, badNotification);
                    }
                }
                if (response.end() < start) break;
                start = response.end() + 1;
            }
            if (attempt + 1 < ADMIT_MAX_RETRIES) Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new RuntimeException("Timed out waiting for remote-sign reply to request " + requestSaid);
    }

    static boolean matchesRemoteSignRef(Exn exn, String noteSaid, String requestSaid,
                                        String walletAid, String issuerAid) {
        return exn != null && "/remotesign/ixn/ref".equals(exn.getR())
                && Objects.equals(noteSaid, exn.getD()) && Objects.equals(requestSaid, exn.getP())
                && Objects.equals(walletAid, exn.getI()) && Objects.equals(issuerAid, exn.getRp());
    }

    private static boolean isRemoteSignRoute(String route) {
        return "/remotesign/ixn/ref".equals(route) || "/exn/remotesign/ixn/ref".equals(route);
    }

    static Notification waitForAdmit(SignifyClient client, String grantSaid,
                                     String walletAid, String issuerAid,
                                     int maxRetries, long pollIntervalMs) throws Exception {
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            int start = 0;
            int snapshotTotal = Integer.MAX_VALUE;
            Set<String> seen = new HashSet<>();
            while (start < snapshotTotal) {
                int end = start + PAGE_SIZE - 1;
                Notifying.Notifications.NotificationListResponse response =
                        client.notifications().list(start, end);
                snapshotTotal = Math.min(snapshotTotal, response.total());
                List<Notification> notes = toNotifications(response.notes());
                if (notes == null || notes.isEmpty()) {
                    break;
                }
                for (Notification note : notes) {
                    if (note == null || note.i == null || !seen.add(note.i)
                            || Boolean.TRUE.equals(note.r) || note.a == null
                            || note.a.d == null || !isAdmitRoute(note.a.r)) {
                        continue;
                    }
                    try {
                        var exchange = client.exchanges().get(note.a.d);
                        if (exchange.isPresent() && matchesAdmit(exchange.get().getExn(), note.a.d,
                                grantSaid, walletAid, issuerAid)) {
                            log.info("Received matching IPEX admit grantSaid={} walletAid={}", grantSaid, walletAid);
                            return note;
                        }
                    } catch (RuntimeException e) {
                        // A malformed exchange may be repaired or become available
                        // on a later poll; never consume its notification here.
                        log.warn("Could not inspect IPEX admit exchangeSaid={}", note.a.d, e);
                    }
                }
                // Use explicit absolute ranges; list(start) always ends at entry 24.
                if (response.end() < start || response.end() + 1 <= start) {
                    break;
                }
                start = response.end() + 1;
            }
            log.info("Waiting for matching IPEX admit grantSaid={} walletAid={} (attempt {}/{})",
                    grantSaid, walletAid, attempt + 1, maxRetries);
            if (attempt + 1 < maxRetries) {
                Thread.sleep(pollIntervalMs);
            }
        }
        throw new RuntimeException("Timed out waiting for admit of grant " + grantSaid
                + " from wallet " + walletAid);
    }

    static boolean matchesAdmit(Exn exn, String notificationSaid, String grantSaid,
                                String walletAid, String issuerAid) {
        return exn != null
                && Objects.equals("/ipex/admit", exn.getR())
                && Objects.equals(notificationSaid, exn.getD())
                && Objects.equals(grantSaid, exn.getP())
                && Objects.equals(walletAid, exn.getI())
                && Objects.equals(issuerAid, exn.getRp());
    }

    private static boolean isAdmitRoute(String route) {
        return "/exn/ipex/admit".equals(route) || "/ipex/admit".equals(route);
    }

    private static List<Notification> toNotifications(
            List<id.veridian.signify.generated.keria.model.Notification> notes) {
        if (notes == null) {
            return List.of();
        }
        return notes.stream().filter(Objects::nonNull).map(note -> {
            Notification result = new Notification();
            result.i = note.getI();
            result.r = note.getR();
            if (note.getA() != null) {
                result.a = new Notification.NotificationBody();
                result.a.r = note.getA().getR();
                result.a.d = note.getA().getD();
            }
            return result;
        }).toList();
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
