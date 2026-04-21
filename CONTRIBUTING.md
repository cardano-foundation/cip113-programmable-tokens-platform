# Contributing to this project

Thanks for considering contributing and helping us build this project!

This repository hosts the **off-chain platform** for CIP-113 programmable tokens: a reference Next.js frontend, a Spring Boot backend, and Aiken substandard implementations. For the on-chain core framework (CIP-113 validators), see the [on-chain repository](https://github.com/cardano-foundation/cip113-programmable-tokens-2).

The best way to contribute right now is to try things out and provide feedback, but we also accept contributions to the documentation and obviously to the code itself.

## Communication channels

Should you have any questions or need some help getting set up, you can use these communication channels to reach the team and get answers in a way others can benefit from as well:

- [CIP-113 Pull Request](https://github.com/cardano-foundation/CIPs/pull/444) — For standard-related discussions and feedback
- GitHub [Issues](../../issues) — For bug reports and implementation-specific issues

## Your first contribution

Contributing to the documentation, its translation, reporting bugs, or proposing features are awesome ways to get started.

Also, take a look at the tests. Making sure we have a high-quality test suite is vital for this project.

### Documentation

Documentation is available in:

- The top-level [README](./README.md)
- Component READMEs:
  - [Frontend](./src/programmable-tokens-frontend/README.md)
  - [Off-chain backend](./src/programmable-tokens-offchain-java/README.md)
  - Substandards: [`dummy`](./src/substandards/dummy/README.md), [`freeze-and-seize`](./src/substandards/freeze-and-seize/README.md)
- On-chain framework documentation in the [on-chain repository](https://github.com/cardano-foundation/cip113-programmable-tokens-2/tree/main/documentation)

### Bug reports

[Submit an issue](../../issues/new) for implementation-specific bugs. For bug reports, it's very important to explain:

* Which component (frontend, backend, substandard, etc.) and version/commit you used
* Steps to reproduce (or steps you took)
* What behavior you saw (ideally supported by logs)
* What behavior you expected

For issues related to the CIP-113 standard itself, please use the [CIP-113 Pull Request](https://github.com/cardano-foundation/CIPs/pull/444).

### Feature ideas

Feature ideas and enhancement proposals are welcome. Feature discussions should consider:

- **Standard-level features** — Discuss in the [CIP-113 Pull Request](https://github.com/cardano-foundation/CIPs/pull/444)
- **Implementation-specific features** — [Submit an issue](../../issues/new) in this repository

We expect a description of:

* Why you (or the user) need/want something (e.g. problem, challenge, pain, benefit)
* What this is roughly about (e.g. description of a new UI flow, API endpoint, or substandard behavior)

We do NOT require a detailed technical description, but are much more interested in *why* a feature is needed.

## Making changes

When contributing code, it helps to have discussed the rationale and (ideally) how something is implemented in a feature idea or bug ticket beforehand.

### Building & Testing

**Frontend (`src/programmable-tokens-frontend/`):**

```bash
cd src/programmable-tokens-frontend
npm install
npm run lint
npm test
npm run build
```

**Off-chain backend (`src/programmable-tokens-offchain-java/`):**

```bash
cd src/programmable-tokens-offchain-java
./gradlew build
./gradlew test
```

**Substandards (`src/substandards/<name>/`):**

```bash
cd src/substandards/<substandard>
aiken fmt --check
aiken check
aiken build
```

Make sure **all** tests pass before submitting a pull request.

### Coding standards

- **TypeScript / Next.js:** Follow the ESLint config in the frontend. Run `npm run lint` before committing.
- **Java:** Follow the [Google style guide for Java](https://google.github.io/styleguide/javaguide.html).
- **Aiken:** Follow [Aiken best practices](https://aiken-lang.org). Run `aiken fmt` before committing.

More generally, keep the coding style consistent with what is already there. Report or fix inconsistencies in a separate issue or pull request — file style-only changes in their own PR.

### Cross-repository changes

Changes that span on-chain and off-chain (e.g. a new substandard requiring new on-chain primitives, or a protocol change affecting transaction building) may require coordinated pull requests in both:

- This repository — off-chain services and substandard implementations
- The [on-chain repository](https://github.com/cardano-foundation/cip113-programmable-tokens-2) — validators and core framework

Please reference related pull requests in both descriptions so reviewers have the full picture.

### Creating a pull request

Thank you for contributing your changes by opening a pull request! To get something merged, we usually require:

+ Description of the changes — if your commit messages are great, this is less important
+ Quality of changes is ensured — through new or updated automated tests
+ Change is related to an issue, feature (idea), or bug report — ideally discussed beforehand
+ Well-scoped — we prefer multiple PRs rather than one big one

**Note:** This project is currently in R&D phase. As the CIP-113 standard evolves, significant changes may be required. We appreciate your understanding and flexibility during this development period.
