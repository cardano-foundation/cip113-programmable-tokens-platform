package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.core.model.certs.CertificateType;
import lombok.extern.slf4j.Slf4j;
import com.bloxbean.cardano.yaci.store.staking.storage.impl.model.StakeRegistrationEntity;
import org.cardanofoundation.cip113.model.DummyRegisterRequest;
import org.cardanofoundation.cip113.model.FreezeAndSeizeRegisterRequest;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.cardanofoundation.cip113.repository.CustomStakeRegistrationRepository;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.service.AccountService;
import org.cardanofoundation.cip113.service.substandard.DummySubstandardHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Registering a script stake credential must happen exactly when it is missing — never when it
 * already exists, and never skipped when it does not.
 *
 * <p>Both halves are load-bearing and both have failed in production. A withdrawal from an
 * UNREGISTERED reward account is rejected phase-1 with {@code WithdrawalsNotInRewardsCERTS}; a
 * duplicate registration is rejected with {@code StakeKeyAlreadyRegisteredDELEG}. Neither is
 * catchable by script evaluation, because reward-account existence is a ledger rule and not a
 * Plutus one — so both substandards build and evaluate a perfectly good transaction and then
 * fail at submit, after the user has signed.
 *
 * <p>The dummy handler shipped with the predicate inverted: it derived the "to register" list
 * from the ALREADY-REGISTERED list and kept the entries missing from the REQUIRED list. The
 * registered list is by construction a subset of the required one, so that predicate was never
 * true, the list was always empty, and no certificate was ever built — while the wizard cheerfully
 * reported "all required stake addresses are already registered". These tests pin the truth table
 * rather than the implementation, so an inversion cannot come back unnoticed.
 */
@Slf4j
public class StakeRegistrationIdempotenceTest {

    /** A repository that reports exactly {@code registered} as having a live registration. */
    private static CustomStakeRegistrationRepository stakeRepo(Set<String> registered) {
        var repo = Mockito.mock(CustomStakeRegistrationRepository.class);
        Mockito.when(repo.findRegistrationsByStakeAddress(Mockito.anyString()))
                .thenAnswer(inv -> {
                    String addr = inv.getArgument(0);
                    if (!registered.contains(addr)) {
                        return Optional.empty();
                    }
                    var entity = Mockito.mock(StakeRegistrationEntity.class);
                    Mockito.when(entity.getType()).thenReturn(CertificateType.STAKE_REGISTRATION);
                    return Optional.of(entity);
                });
        return repo;
    }

    private static DummySubstandardHandler dummyHandler(OfflineChain chain,
                                                        BootstrapFixture.Bootstrapped boot,
                                                        CustomStakeRegistrationRepository stakeRepo)
            throws Exception {
        var registrySpendHash = HexUtil.encodeHexString(boot.registrySpend().getScriptHash());
        var registryAddress = boot.registryOriginUtxo().getAddress();
        return new DummySubstandardHandler(
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                HandlerFixtures.utxoRepository(chain, registryAddress, registrySpendHash),
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                Mockito.mock(AccountService.class),
                HandlerFixtures.substandardService(),
                HandlerFixtures.protocolScriptBuilderService(),
                chain.quickTxBuilder(),
                chain.protocolParamsSupplier(),
                Mockito.mock(ProgrammableTokenRegistryRepository.class),
                stakeRepo,
                // A REAL service over the mocked repository. The mocked backend makes the ledger
                // lookup fail, so these tests exercise the indexed-certificate fallback — which is
                // the branch whose truth table they exist to pin.
                new org.cardanofoundation.cip113.service.ScriptRegistrationService(
                        Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class),
                        // Deliberately null, not a mock: isStakeAddressRegistered never touches the
                        // builder, and mocking QuickTxBuilder instruments it for every test in the
                        // JVM under the inline mock maker — which silently broke the security-token
                        // burn's real evaluator whenever this class ran as a whole.
                        null,
                        Mockito.mock(AccountService.class),
                        stakeRepo,
                        // Nothing pre-recorded: these tests exercise the ledger and
                        // indexed-certificate sources, not the learned one.
                        Mockito.mock(org.cardanofoundation.cip113.repository.KnownScriptRegistrationRepository.class)));
    }

    private static DummyRegisterRequest dummyRequest() {
        var request = new DummyRegisterRequest();
        request.setSubstandardId("dummy");
        request.setFeePayerAddress(BootstrapFixture.ADMIN.baseAddress());
        request.setAssetName(HexUtil.encodeHexString("IdempotenceToken".getBytes()));
        request.setQuantity("1000");
        request.setRecipientAddress(BootstrapFixture.ALICE.baseAddress());
        return request;
    }

    /** How many stake-registration certificates {@code cborHex} actually carries. */
    private static int certCount(String cborHex) throws Exception {
        var tx = Transaction.deserialize(HexUtil.decodeHexString(cborHex));
        var certs = tx.getBody().getCerts();
        return certs == null ? 0 : certs.size();
    }

    // ------------------------------------------------------------------ dummy

    /**
     * Nothing registered yet — the regression case. This returned a null CBOR before the fix, so
     * the certificates were never built and the registration that followed withdrew from reward
     * accounts that did not exist.
     */
    @Test
    public void dummyRegistersBothCredentialsWhenNeitherExists() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var handler = dummyHandler(chain, boot, stakeRepo(Set.of()));

        var result = handler.buildPreRegistrationTransaction(dummyRequest(), boot.params());

        Assertions.assertTrue(result.isSuccessful(),
                "pre-registration build failed: " + result.error());
        Assertions.assertNotNull(result.unsignedCborTx(),
                "with NEITHER credential registered the handler must build a certificate "
                + "transaction — a null CBOR is read by the wizard as 'already registered', which "
                + "is exactly the bug that let unregistered reward accounts reach the ledger");
        Assertions.assertEquals(2, certCount(result.unsignedCborTx()),
                "both the issue and transfer credentials need registering");
        Assertions.assertEquals(List.of(), result.metadata(),
                "nothing is registered yet, so the already-registered list must be empty");
        log.info("dummy/none-registered: built {} certs, {} bytes",
                certCount(result.unsignedCborTx()),
                HexUtil.decodeHexString(result.unsignedCborTx()).length);
    }

    /** Both already registered — a second certificate would be StakeKeyAlreadyRegisteredDELEG. */
    @Test
    public void dummyBuildsNothingWhenBothCredentialsExist() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);

        // Discover the two addresses the handler needs by letting it tell us: with nothing
        // registered it must ask for both, so the same run that proves the previous test also
        // yields the address pair, without this test hard-coding script hashes that change
        // whenever the substandard blueprint is rebuilt.
        var discovery = dummyHandler(chain, boot, stakeRepo(Set.of()))
                .buildPreRegistrationTransaction(dummyRequest(), boot.params());
        Assertions.assertNotNull(discovery.unsignedCborTx(), "discovery run must build certs");
        var required = certAddresses(discovery.unsignedCborTx());
        Assertions.assertEquals(2, required.size(), "expected two required credentials");

        var handler = dummyHandler(chain, boot, stakeRepo(required));
        var result = handler.buildPreRegistrationTransaction(dummyRequest(), boot.params());

        Assertions.assertTrue(result.isSuccessful(),
                "pre-registration build failed: " + result.error());
        Assertions.assertNull(result.unsignedCborTx(),
                "both credentials already exist, so registering either again would be rejected "
                + "with StakeKeyAlreadyRegisteredDELEG — the handler must build nothing");
        Assertions.assertEquals(2, result.metadata().size(),
                "both addresses must still be reported back as registered");
    }

    /** The asymmetric case: one registered, one not. Only the missing one may be certified. */
    @Test
    public void dummyRegistersOnlyTheMissingCredential() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);

        var discovery = dummyHandler(chain, boot, stakeRepo(Set.of()))
                .buildPreRegistrationTransaction(dummyRequest(), boot.params());
        var required = certAddresses(discovery.unsignedCborTx());
        var alreadyThere = required.iterator().next();

        var handler = dummyHandler(chain, boot, stakeRepo(Set.of(alreadyThere)));
        var result = handler.buildPreRegistrationTransaction(dummyRequest(), boot.params());

        Assertions.assertTrue(result.isSuccessful(),
                "pre-registration build failed: " + result.error());
        Assertions.assertNotNull(result.unsignedCborTx(),
                "one credential is still missing, so a certificate transaction is required");
        Assertions.assertEquals(1, certCount(result.unsignedCborTx()),
                "exactly the missing credential — certifying the existing one too would be "
                + "rejected with StakeKeyAlreadyRegisteredDELEG");
        Assertions.assertEquals(List.of(alreadyThere), result.metadata());
        Assertions.assertFalse(certAddresses(result.unsignedCborTx()).contains(alreadyThere),
                "the already-registered credential must not appear among the certificates");
    }

    /** The bech32 reward addresses named by {@code cborHex}'s registration certificates. */
    private static Set<String> certAddresses(String cborHex) throws Exception {
        var tx = Transaction.deserialize(HexUtil.decodeHexString(cborHex));
        var certs = tx.getBody().getCerts();
        Assertions.assertNotNull(certs, "transaction carries no certificates");
        var out = new java.util.LinkedHashSet<String>();
        for (var cert : certs) {
            var sr = (com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration) cert;
            out.add(new Address(com.bloxbean.cardano.client.address.AddressProvider
                    .getRewardAddress(toCredential(sr.getStakeCredential()),
                            HandlerFixtures.NETWORK.getCardanoNetwork()).getAddress()).getAddress());
        }
        return out;
    }

    private static com.bloxbean.cardano.client.address.Credential toCredential(
            com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential cred) {
        return cred.getType() == com.bloxbean.cardano.client.transaction.spec.cert.StakeCredType.SCRIPTHASH
                ? com.bloxbean.cardano.client.address.Credential.fromScript(cred.getHash())
                : com.bloxbean.cardano.client.address.Credential.fromKey(cred.getHash());
    }

    private static org.cardanofoundation.cip113.service.substandard.FreezeAndSeizeHandler
            freezeAndSeizeHandler(OfflineChain chain, CustomStakeRegistrationRepository stakeRepo) throws Exception {
        var utxoProvider = Mockito.mock(org.cardanofoundation.cip113.service.UtxoProvider.class);
        var substandardService = HandlerFixtures.substandardService();
        return new org.cardanofoundation.cip113.service.substandard.FreezeAndSeizeHandler(
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                Mockito.mock(org.cardanofoundation.cip113.model.onchain.siezeandfreeze.blacklist.BlacklistNodeParser.class),
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                new AccountService(utxoProvider),
                substandardService,
                HandlerFixtures.protocolScriptBuilderService(),
                new org.cardanofoundation.cip113.service.FreezeAndSeizeScriptBuilderService(substandardService),
                new org.cardanofoundation.cip113.service.LinkedListService(utxoProvider),
                chain.quickTxBuilder(),
                chain.protocolParamsSupplier(),
                new org.cardanofoundation.cip113.service.HybridUtxoSupplier(
                        Mockito.mock(com.bloxbean.cardano.client.backend.api.UtxoService.class)),
                Mockito.mock(org.cardanofoundation.cip113.repository.FreezeAndSeizeTokenRegistrationRepository.class),
                Mockito.mock(org.cardanofoundation.cip113.repository.BlacklistInitRepository.class),
                Mockito.mock(ProgrammableTokenRegistryRepository.class),
                stakeRepo,
                new org.cardanofoundation.cip113.service.ScriptRegistrationService(
                        Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class),
                        null,
                        Mockito.mock(AccountService.class),
                        stakeRepo,
                        // Nothing pre-recorded: these tests exercise the ledger and
                        // indexed-certificate sources, not the learned one.
                        Mockito.mock(org.cardanofoundation.cip113.repository.KnownScriptRegistrationRepository.class)),
                utxoProvider,
                Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class));
    }

    // ------------------------------------------ the SDK-facing registration check

    /**
     * {@code /script-registration/check} is what the SDK's freeze-and-seize path consults before
     * deciding whether to emit a registration certificate, so a wrong answer here is what actually
     * reaches the ledger.
     *
     * <p>It used to ask Blockfrost for the account's {@code active} flag. Script stake credentials
     * exist only to be withdrawn-from, never delegate and never earn rewards, so that flag is false
     * (or the account 404s) even when the credential is perfectly well registered — and every
     * failure path returned false, meaning "register it again". The SDK duly did, and the ledger
     * rejected it with StakeKeyAlreadyRegisteredDELEG.
     */
    @Test
    public void registrationCheckReadsIndexedCertificatesNotAccountActivity() {
        var addr = "stake_test17q8czpaqyn8mclj70p7k0txaemr53n4jsr7vfv2vxp0x5tgnq7hem";

        var registered = new org.cardanofoundation.cip113.service.ScriptRegistrationService(
                Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class),
                null,
                Mockito.mock(AccountService.class),
                stakeRepo(Set.of(addr)),
                Mockito.mock(org.cardanofoundation.cip113.repository.KnownScriptRegistrationRepository.class));
        Assertions.assertTrue(registered.isStakeAddressRegistered(addr),
                "a script credential whose latest certificate is a registration IS registered, "
                + "however inactive its account looks");

        var never = new org.cardanofoundation.cip113.service.ScriptRegistrationService(
                Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class),
                null,
                Mockito.mock(AccountService.class),
                stakeRepo(Set.of()),
                Mockito.mock(org.cardanofoundation.cip113.repository.KnownScriptRegistrationRepository.class));
        Assertions.assertFalse(never.isStakeAddressRegistered(addr),
                "no certificate at all means not registered");
    }

    /**
     * The reported failure, reduced: the credential IS registered on chain, and our index has no
     * record of it.
     *
     * <p>That is not a corner case — it is the normal state for these two validators. They are
     * protocol-GLOBAL and unparameterized, so they are registered exactly once per network, and
     * every deployment indexes from its own {@code sync-start-slot} (genesis on a devnet, the
     * contract's ref-input block on mainnet) with the table starting empty again after any devnet
     * reset. Consulting the index alone answered "not registered", the handler emitted a
     * certificate, and the ledger rejected the whole transaction:
     *
     * <pre>
     *   3145 — Trying to re-register some already known credentials.
     *          knownCredential: 17e9e9e3412e7198877557fab1181f394ed36cf5db27b418d3f68990
     * </pre>
     *
     * <p>That hash is the dummy transfer validator's, and this is exactly the shape that produced
     * it: the ledger says yes, the index says nothing.
     */
    @Test
    public void ledgerBeatsAnIndexThatNeverSawTheCertificate() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);

        // An index with NOTHING in it — the pre-sync-start-slot situation.
        var emptyIndex = stakeRepo(Set.of());

        var handler = new DummySubstandardHandler(
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                HandlerFixtures.utxoRepository(chain, boot.registryOriginUtxo().getAddress(),
                        HexUtil.encodeHexString(boot.registrySpend().getScriptHash())),
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                Mockito.mock(AccountService.class),
                HandlerFixtures.substandardService(),
                HandlerFixtures.protocolScriptBuilderService(),
                chain.quickTxBuilder(),
                chain.protocolParamsSupplier(),
                Mockito.mock(ProgrammableTokenRegistryRepository.class),
                emptyIndex,
                new org.cardanofoundation.cip113.service.ScriptRegistrationService(
                        ledgerSaying(true),
                        null,
                        Mockito.mock(AccountService.class),
                        emptyIndex,
                        // Nothing pre-recorded: these tests exercise the ledger and
                        // indexed-certificate sources, not the learned one.
                        Mockito.mock(org.cardanofoundation.cip113.repository.KnownScriptRegistrationRepository.class)));

        var result = handler.buildPreRegistrationTransaction(dummyRequest(), boot.params());

        Assertions.assertTrue(result.isSuccessful(), result.error());
        Assertions.assertNull(result.unsignedCborTx(),
                "the ledger says both credentials exist, so nothing may be registered — emitting a "
                + "certificate here is what the chain rejects with StakeKeyAlreadyRegisteredDELEG, "
                + "and an empty index must not be read as evidence of absence");
    }

    /**
     * The recovery path: BOTH chain sources are blind, and the credential was recorded anyway.
     *
     * <p>This is the state a devkit devnet is actually in — no {@code /accounts} endpoint (it 404s
     * even on {@code /blocks/latest}) and an indexed certificate window that starts well after the
     * protocol-global validators were registered. With nothing else to go on the handler emitted a
     * certificate and the ledger rejected the transaction with 3145, identically, forever. Once the
     * credential named by that error has been recorded, the next attempt must leave it out.
     */
    @Test
    public void aRecordedCredentialIsHonouredWhenBothChainSourcesAreBlind() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var emptyIndex = stakeRepo(Set.of());

        // Discover the pair, then record BOTH as already known.
        var discovery = dummyHandler(chain, boot, stakeRepo(Set.of()))
                .buildPreRegistrationTransaction(dummyRequest(), boot.params());
        var required = certAddresses(discovery.unsignedCborTx());

        var known = Mockito.mock(
                org.cardanofoundation.cip113.repository.KnownScriptRegistrationRepository.class);
        Mockito.when(known.findById(Mockito.anyString())).thenAnswer(inv -> {
            String addr = inv.getArgument(0);
            if (!required.contains(addr)) {
                return Optional.empty();
            }
            // registered = true: a confirmed row. A row with registered = false is only evidence
            // that we built a certificate, and must NOT suppress the registration.
            return Optional.of(org.cardanofoundation.cip113.entity.KnownScriptRegistrationEntity
                    .builder().stakeAddress(addr).source("LEDGER_REJECT").registered(true).build());
        });

        var handler = new DummySubstandardHandler(
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                HandlerFixtures.utxoRepository(chain, boot.registryOriginUtxo().getAddress(),
                        HexUtil.encodeHexString(boot.registrySpend().getScriptHash())),
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                Mockito.mock(AccountService.class),
                HandlerFixtures.substandardService(),
                HandlerFixtures.protocolScriptBuilderService(),
                chain.quickTxBuilder(),
                chain.protocolParamsSupplier(),
                Mockito.mock(ProgrammableTokenRegistryRepository.class),
                emptyIndex,
                new org.cardanofoundation.cip113.service.ScriptRegistrationService(
                        // An account endpoint that answers nothing, like the devkit's.
                        Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class),
                        null,
                        Mockito.mock(AccountService.class),
                        emptyIndex,
                        known));

        var result = handler.buildPreRegistrationTransaction(dummyRequest(), boot.params());

        Assertions.assertTrue(result.isSuccessful(), result.error());
        Assertions.assertNull(result.unsignedCborTx(),
                "both credentials are on record, so nothing may be registered — this is the retry "
                + "that has to succeed after a 3145, and it is the only source able to say so here");
    }

    /**
     * The confirm endpoint may only confirm what this deployment was already attempting.
     *
     * <p>Nothing in this service authenticates anyone, and this is the one write that changes later
     * behaviour by itself — every other endpoint returns unsigned CBOR that still needs a wallet
     * signature. An unconstrained write would let any caller mark an arbitrary credential
     * registered, which makes the pre-registration step SKIP it and the registration that follows
     * fail with WithdrawalsNotInRewardsCERTS: durable, and invisible both on chain and in the logs.
     */
    @Test
    public void confirmingACredentialWeNeverBuiltIsRefused() {
        var addr = "stake_test17q8czpaqyn8mclj70p7k0txaemr53n4jsr7vfv2vxp0x5tgnq7hem";
        var known = Mockito.mock(
                org.cardanofoundation.cip113.repository.KnownScriptRegistrationRepository.class);
        Mockito.when(known.findById(Mockito.anyString())).thenReturn(Optional.empty());

        var service = new org.cardanofoundation.cip113.service.ScriptRegistrationService(
                Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class),
                null,
                Mockito.mock(AccountService.class),
                stakeRepo(Set.of()),
                known);

        Assertions.assertFalse(service.noteRegistered(addr, null, "LEDGER_REJECT"),
                "there is no evidence this deployment ever built a registration for this address, "
                + "so there is nothing to confirm and the write must be refused");
        Mockito.verify(known, Mockito.never()).save(Mockito.any());
    }

    /**
     * The other half of the evidence rule: once a row exists, confirming it must succeed.
     *
     * <p>This is the freeze-and-seize recovery path. There the SDK builds and submits the
     * certificate client-side, so the backend never sees the transaction — its only involvement is
     * answering /script-registration/check with "not registered", which is what creates the row.
     * Without that, a 3145 from the SDK path could never be confirmed and the flow would stay
     * stuck exactly as it was.
     */
    @Test
    public void confirmingACredentialWeAdvisedRegisteringSucceeds() {
        var addr = "stake_test17qlj7k8pcdlfqwd2mkzawx5xwvtlqzl3w8sxptg8pg24mlq7k9g8t";
        var row = org.cardanofoundation.cip113.entity.KnownScriptRegistrationEntity.builder()
                .stakeAddress(addr).source("BUILT").registered(false).build();

        var known = Mockito.mock(
                org.cardanofoundation.cip113.repository.KnownScriptRegistrationRepository.class);
        Mockito.when(known.findById(addr)).thenReturn(Optional.of(row));

        var service = new org.cardanofoundation.cip113.service.ScriptRegistrationService(
                Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class),
                null,
                Mockito.mock(AccountService.class),
                stakeRepo(Set.of()),
                known);

        Assertions.assertTrue(service.noteRegistered(addr, null, "LEDGER_REJECT"),
                "this deployment advised registering the credential, so the 3145 that came back is "
                + "exactly the confirmation the row was waiting for");
        Assertions.assertTrue(row.isRegistered(), "the row must be promoted, not merely touched");
        Mockito.verify(known).save(row);
    }

    /** An attempt row is evidence, not a claim: it must not suppress the registration by itself. */
    @Test
    public void anUnconfirmedAttemptRowDoesNotSuppressRegistration() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);

        var known = Mockito.mock(
                org.cardanofoundation.cip113.repository.KnownScriptRegistrationRepository.class);
        Mockito.when(known.findById(Mockito.anyString())).thenAnswer(inv -> Optional.of(
                org.cardanofoundation.cip113.entity.KnownScriptRegistrationEntity.builder()
                        .stakeAddress(inv.getArgument(0)).source("BUILT").registered(false).build()));

        var service = new org.cardanofoundation.cip113.service.ScriptRegistrationService(
                Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class),
                null,
                Mockito.mock(AccountService.class),
                stakeRepo(Set.of()),
                known);

        Assertions.assertFalse(
                service.isStakeAddressRegistered(
                        "stake_test17q8czpaqyn8mclj70p7k0txaemr53n4jsr7vfv2vxp0x5tgnq7hem"),
                "a BUILT/registered=false row records that we tried, nothing more — treating it as "
                + "proof of registration would skip a credential that may not exist yet");
    }

    /** A backend whose account endpoint reports every account with {@code active = registered}. */
    private static com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService
            ledgerSaying(boolean registered) throws Exception {
        var account = Mockito.mock(com.bloxbean.cardano.client.backend.model.AccountInformation.class);
        Mockito.when(account.getActive()).thenReturn(registered);

        var result = Mockito.mock(com.bloxbean.cardano.client.api.model.Result.class);
        Mockito.when(result.isSuccessful()).thenReturn(true);
        Mockito.when(result.getValue()).thenReturn(account);

        var accountService = Mockito.mock(com.bloxbean.cardano.client.backend.api.AccountService.class);
        Mockito.when(accountService.getAccountInformation(Mockito.anyString())).thenReturn(result);

        var backend = Mockito.mock(
                com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class);
        Mockito.when(backend.getAccountService()).thenReturn(accountService);
        return backend;
    }

    /** A later deregistration must flip the answer back — the account flag could not express this. */
    @Test
    public void registrationCheckHonoursALaterDeregistration() {
        var addr = "stake_test17q8czpaqyn8mclj70p7k0txaemr53n4jsr7vfv2vxp0x5tgnq7hem";

        var repo = Mockito.mock(CustomStakeRegistrationRepository.class);
        var latest = Mockito.mock(StakeRegistrationEntity.class);
        Mockito.when(latest.getType()).thenReturn(CertificateType.STAKE_DEREGISTRATION);
        Mockito.when(repo.findRegistrationsByStakeAddress(addr)).thenReturn(Optional.of(latest));

        var service = new org.cardanofoundation.cip113.service.ScriptRegistrationService(
                Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class),
                null,
                Mockito.mock(AccountService.class),
                repo,
                Mockito.mock(org.cardanofoundation.cip113.repository.KnownScriptRegistrationRepository.class));

        Assertions.assertFalse(service.isStakeAddressRegistered(addr),
                "the newest certificate is a deregistration, so the credential must be re-registered "
                + "before anything withdraws from it again");
    }

    // ------------------------------------------------------ freeze-and-seize

    /**
     * freeze-and-seize used to answer this call with a hard error ("Use DenyList Init instead"),
     * which is only correct while a token's blacklist init and its registration are built
     * back-to-back. {@code issuer_admin} is parameterized by the ASSET NAME, so registering a
     * second token against an existing blacklist needs a reward account no init ever saw.
     */
    @Test
    public void freezeAndSeizePreRegistrationNoLongerHardErrors() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var adminPkh = new Address(BootstrapFixture.ADMIN.baseAddress())
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElseThrow();

        var request = FreezeAndSeizeRegisterRequest.builder()
                .adminPubKeyHash(adminPkh)
                // Any 28-byte policy id: the transfer script is parameterized by it, and this test
                // is about the register/skip decision rather than about a specific blacklist.
                .blacklistNodePolicyId("00".repeat(28))
                .build();
        request.setSubstandardId("freeze-and-seize");
        request.setFeePayerAddress(BootstrapFixture.ADMIN.baseAddress());
        request.setAssetName(HexUtil.encodeHexString("IdempotenceToken".getBytes()));
        request.setQuantity("1000");
        request.setRecipientAddress(BootstrapFixture.ALICE.baseAddress());

        var handler = freezeAndSeizeHandler(chain, stakeRepo(Set.of()));
        var result = handler.buildPreRegistrationTransaction(request, boot.params());

        Assertions.assertTrue(result.isSuccessful(),
                "freeze-and-seize pre-registration must build rather than refuse: " + result.error());
        Assertions.assertNotNull(result.unsignedCborTx(),
                "with nothing registered the handler must build a certificate transaction");
        Assertions.assertEquals(2, certCount(result.unsignedCborTx()),
                "both issuer_admin and the transfer credential need registering");

        // ...and the mirror case: both present means nothing to do.
        var required = certAddresses(result.unsignedCborTx());
        var settled = freezeAndSeizeHandler(chain, stakeRepo(required))
                .buildPreRegistrationTransaction(request, boot.params());
        Assertions.assertTrue(settled.isSuccessful(), settled.error());
        Assertions.assertNull(settled.unsignedCborTx(),
                "both credentials already exist — a duplicate certificate would be rejected");
        Assertions.assertEquals(2, settled.metadata().size());
    }
}
