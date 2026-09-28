# Testing code that parses serialised bytes

This codebase slices transaction CBOR by byte offset in several places: merging a wallet's
witnesses while keeping the body byte-identical, locating an output's coin to mine against,
reading vkey witnesses to count a multisig quorum. Those are the places where a mistake does not
throw — it produces a transaction that is still valid CBOR, still submittable, and wrong.

Two rules, both learned the expensive way on 2026-09-16. Read them before writing a test over
serialised bytes; the evidence for each is at the bottom.

## 1. Build the fixture with the encoder your code will meet, not from the specification

A parser verified against the CDDL answers "does this match the document?". The question that
matters is "does this match what our encoder emits?" — and those differ, silently, in ways no
amount of care with the specification will surface.

Concretely, in this repository, Evolution:

- writes an **ada-only transaction output as a Shelley array** — `82 <address> <coin>` — not the
  Babbage map form (`a2 00 <address> 01 <value>`) that the Conway CDDL leads you to implement;
- writes the **transaction input list and the vkey witness list as tag-258 sets** — `d9 0102`
  before the array — where the CDDL shows a bare array;
- **canonicalises on decode**, so a hand-built body that round-trips through `fromCBORBytes` /
  `toCBORBytes` comes back with those tags added.

So build fixtures like this:

```js
// Hand-assemble canonical CBOR, then let the encoder have the last word.
const bytes = TransactionBody.toCBORBytes(TransactionBody.fromCBORBytes(handBuilt));
```

What comes back is the encoding your code will actually meet, by construction rather than by
hope. The same applies to `Transaction` and `TransactionWitnessSet`.

**Where both encodings are legitimate, test both.** A witness set arriving from a CIP-30 wallet's
`signTx` is commonly a bare array; one that has been through Evolution carries the tag. Both reach
the quorum code. `test-upgrade-witness.js` runs every witness assertion twice for that reason, and
asserts the two fixtures are *actually different bytes* — so that if Evolution ever stops tagging,
the suite says so rather than quietly halving its own coverage.

## 2. A fixture too small to express the bug makes the assertion over it vacuous

This one hides underneath the first and is harder to see. A test can use the right encoder and
still assert nothing, if the fixture has no room for the failure to occur in.

`test-upgrade-witness.js` assembled multisig witnesses against a transaction whose **body was the
two characters `a0`** — a CBOR map with no entries. Two assertions over it could not fail:

```js
// Extracting the witness set by character arithmetic. Correct only while the body is 2 chars.
const back = keyHashesInWitnessSet(assembled.slice(4, assembled.length - 4));

// "the body was preserved" — true no matter how the slicing behaved, because the body is empty.
assert.strictEqual(assembled.slice(2, 4), "a0");
```

The second is the assertion protecting every signature on a protocol upgrade, including the first
signer's. It had never been capable of going red. Giving the fixture a real body — inputs, outputs,
a fee — turned both into checks, and the first into a failing one.

Ask of any fixture: *if the defect I am guarding against were present, would this fixture be big
enough to show it?* An empty map, a zero-length list and a single-element array are the usual
offenders.

## The evidence

Three instances of rule 1, in one file, on one afternoon:

| Where | What |
|---|---|
| `lib/mining/locate.ts` | Written first against the Babbage map form. Every test from the spec would have passed; every transaction this application builds would have failed. Caught by testing against Evolution's output. |
| `test-upgrade-witness.js` | Fixtures hand-built untagged, so the quorum path was green against an encoding half its callers never produce. The functions handled both; the coverage did not. |
| The fix for the above | A baseline compared against hand-built hex rather than the encoder's output, reporting a body change that never happened — written *while actively hunting this exact failure mode*. |

The third is the one worth remembering. Knowing about the family does not protect you from it.
Only building the fixture through the encoder does.

## A note on reading failures

When a test fails in a way that matches your expectation unusually well, read the source of the
function before writing up the defect. During this work a hypothesis was handed over ("the tag-258
case is probably unhandled"), and a type confusion in the test — a hex string passed where a
`Uint8Array` was expected, making `"8" >> 5` evaluate to `0` — produced the error
`vkey witness value is not a CBOR array (major type 0)`. That is exactly what the predicted defect
would have looked like. The code was already correct: `hasTag258` sits nine lines above the throw.

A wrong answer shaped like the hypothesis you were given is the hardest kind to doubt, and the
more specific your expectation, the more convincing the coincidence. Reading the function ends it
in a minute.
