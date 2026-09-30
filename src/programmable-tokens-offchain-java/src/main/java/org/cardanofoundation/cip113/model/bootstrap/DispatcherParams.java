package org.cardanofoundation.cip113.model.bootstrap;

/**
 * {@code programmable_logic_global} — the dispatcher, and the one deployed script that records a
 * second fact about itself.
 *
 * <p>⛔ WHY THIS IS NOT {@link ScriptParams}. Every other script in a deployment is fully described
 * by its hash. The dispatcher also records WHAT IT WAS COMPILED AGAINST: either the real
 * {@code unfracking} script hash, or the 28-byte disabled sentinel
 * ({@code 0000…0000}). Nothing else in the record determines which — a deployment may legitimately
 * deploy, publish and record the real unfracking script while compiling the dispatcher against the
 * sentinel, so {@code unfracking.scriptHash} is NOT a substitute for this value.
 *
 * <p>⚠ THIS TYPE EXISTS BECAUSE THE FIELD WAS BEING LOST — and it was lost in TWO different ways,
 * which is the part worth remembering. {@code programmableLogicGlobal} was typed as
 * {@code ScriptParams(String scriptHash)}, which has nowhere to put {@code unfrackingParameter}, and
 * what happened next depended entirely on WHICH MAPPER read the file:
 *
 * <ul>
 *   <li><strong>Spring's injected ObjectMapper</strong> — Boot disables
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES}, so the field was <strong>dropped in silence</strong>. The
 *       parse succeeded, startup succeeded, and the service served a dispatcher description with the
 *       value simply missing. The SDK then refused to build against it, correctly: it cannot tell
 *       "disabled" from "lost", and defaulting to the sentinel would be right often enough that
 *       nobody would ever check. Reported from a real FES deployment, 2026-09-30.</li>
 *   <li><strong>A plain {@code new ObjectMapper()}</strong> — strict by default, so it
 *       <strong>threw</strong> {@code UnrecognizedPropertyException}. That is why
 *       {@code PreviewDeploymentRecordDerivationTest} could not even initialise at {@code main}: the
 *       same defect, shouting instead of whispering.</li>
 * </ul>
 *
 * <p>⚑ {@code @JsonIgnoreProperties(ignoreUnknown = true)} is on {@link ProtocolBootstrapParams}, the
 * OUTER record — it does not reach nested types. So leniency at the top level and strictness one
 * level down coexisted, and the same record was both loadable and unloadable depending on the
 * caller.
 *
 * <p>Giving the dispatcher its own type rather than widening {@code ScriptParams} keeps the
 * asymmetry visible: six scripts carry a hash, one carries a hash and a claim.
 */
public record DispatcherParams(String scriptHash, String unfrackingParameter) {

    /** The 28-byte sentinel {@code programmable_logic_global} is compiled against when unfracking is off. */
    public static final String UNFRACKING_DISABLED = "0".repeat(56);

    /** True when this deployment compiled the dispatcher against the sentinel rather than a script. */
    public boolean unfrackingDisabled() {
        return UNFRACKING_DISABLED.equals(unfrackingParameter);
    }
}
