-- ============================================================================
-- Only credentials this deployment actually tried to register may be marked known
-- ============================================================================
-- V16 added known_script_registration and an endpoint that writes to it, so a
-- submit rejected with 3145 could tell the platform what it had no other way to
-- learn. That endpoint takes a credential from the caller and trusts it, and
-- nothing in this service authenticates anything -- which makes it the one
-- unauthenticated write here that changes future behaviour on its own.
--
-- The other endpoints are not comparable: they BUILD unsigned transactions and
-- hand back CBOR that still needs the user's wallet signature to have any effect.
-- This one persists a fact that is consulted before every later build.
--
-- Marking an arbitrary credential "registered" makes the pre-registration step
-- SKIP it. The registration that follows then withdraws-0 from a reward account
-- that does not exist and is rejected with WithdrawalsNotInRewardsCERTS -- a
-- durable denial of service against that token, and an unusually opaque one,
-- since nothing on chain or in the logs points at the injected row.
--
-- So the row must exist BEFORE it can be marked: the platform inserts it with
-- registered = FALSE when it builds a certificate for that credential, and the
-- endpoint may only flip an existing row to TRUE. A caller can therefore confirm
-- what this deployment was already attempting -- which is the entire recovery
-- case -- and cannot introduce a credential of its own choosing.
--
-- Existing rows default to TRUE: V16 could only be written through the endpoint's
-- confirm path, so anything already recorded was a confirmation.
ALTER TABLE known_script_registration
    ADD COLUMN registered BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN known_script_registration.registered IS
    'TRUE = known registered, honoured by the registration check. FALSE = this platform built a certificate for it and is awaiting the outcome; such a row is evidence the credential is ours to confirm, and nothing more.';
