# WP-7: The acceptance sequence runs end to end on yaci-devkit
Tier: R3
Base: 403f48cb051505be68eeb85d1924098b5536dfef
Check: each step confirms on chain, with its tx hash in the Log: genesis → register with initial mint → wallet-attested transfer → SetMintableAmount → rotate the logic to a new cap → mint

## Allowed files
- docs/work/wp-7-e2e.md

## Rules
- The issuer and the sender are different wallets, so the run exercises the real two-party attestation flow.
- One transfer uses the copy-paste path: the issuer generates a bundle on the admin page, and the holder pastes it into their transfer dialog. Its tx hash goes in the log.
- Include a structural registration followed by one GS admin action, which proves the GS reference scripts exist on that path.
- No code changes here. A failure goes back to the WP that owns the code.

## Proof commands
- green: run the sequence against yaci-devkit (manually or scripted) and record the tx hashes

## Log
