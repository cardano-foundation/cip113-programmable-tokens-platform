import type { Metadata } from "next";

/**
 * Terms & Conditions and Privacy & Data Processing.
 *
 * ⛔ THE COPY IS VERBATIM AND MUST STAY THAT WAY. It was supplied by the Cardano Foundation
 * and reviewed as a whole; this file is a rendering of it, not an edit of it. Tightening a
 * sentence, dropping a "solely", or moving emphasis changes what the site asserts. If the
 * wording is wrong, it is corrected at the source and copied back — not improved here.
 *
 * ⛔ AND THE TWO CARDANO FOUNDATION POLICIES ARE LINKED, NEVER COPIED. They have their own
 * lifecycle. A local copy would keep being served after theirs changed, which is worse than
 * having none, because it looks authoritative while being stale.
 *
 * The `#terms` and `#privacy` anchors are load-bearing: the footer and the cookie banner both
 * deep-link to them, so the ids must not be renamed without changing those call sites.
 */

export const metadata: Metadata = {
  title: "Terms & Privacy — CIP-113 Programmable Tokens",
  description:
    "Terms & Conditions and Privacy & Data Processing for the CIP-113 Programmable Tokens reference implementation.",
};

const REPO_URL = "https://github.com/cardano-foundation/cip113-programmable-tokens-platform";
const CF_TERMS_URL = "https://cardanofoundation.org/policy/terms-and-conditions";
const CF_PRIVACY_URL = "https://cardanofoundation.org/policy/privacy";

function H2({ id, children }: { id: string; children: React.ReactNode }) {
  return (
    // scroll-mt keeps the heading clear of the fixed header when linked to directly —
    // without it the anchor lands with the title hidden behind the nav.
    <h2 id={id} className="scroll-mt-24 text-2xl font-bold text-white">
      {children}
    </h2>
  );
}

function H3({ children }: { children: React.ReactNode }) {
  return <h3 className="mt-8 font-semibold text-white">{children}</h3>;
}

function P({ children }: { children: React.ReactNode }) {
  return <p className="mt-3 text-sm leading-relaxed text-dark-300">{children}</p>;
}

/** A paragraph the source sets in bold. The emphasis is part of the approved copy. */
function Strong({ children }: { children: React.ReactNode }) {
  return <p className="mt-3 text-sm font-semibold leading-relaxed text-dark-100">{children}</p>;
}

function Ext({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="text-primary-400 underline hover:text-primary-300"
    >
      {children}
    </a>
  );
}

export default function LegalPage() {
  return (
    <main className="mx-auto max-w-3xl px-4 py-12">
      <h1 className="text-3xl font-bold text-white">Terms &amp; Privacy</h1>
      <nav className="mt-4 flex gap-4 text-sm">
        <a href="#terms" className="text-primary-400 underline hover:text-primary-300">
          Terms &amp; Conditions
        </a>
        <a href="#privacy" className="text-primary-400 underline hover:text-primary-300">
          Privacy &amp; Data Processing
        </a>
      </nav>

      <section className="mt-12">
        <H2 id="terms">Terms &amp; Conditions</H2>

        <H3>Acceptance of Terms</H3>
        <P>
          By accessing and using this interface to interact with the CIP-113 Programmable Tokens
          reference implementation (the &ldquo;Tool&rdquo;),{" "}
          <strong className="font-semibold text-dark-100">
            you agree to be bound by the general <Ext href={CF_TERMS_URL}>Terms of Use</Ext> and
            these following Terms &amp; Conditions (together, the &ldquo;Terms&rdquo;)
          </strong>
          . In the event of any conflict between the general Terms of Use and these Terms &amp;
          Conditions, the provisions of the Terms &amp; Conditions shall prevail, as applicable.
          Please read these Terms carefully. If you do not agree with any part of these Terms, you
          must not use this Tool.
        </P>

        <H3>Description of Tool</H3>
        <P>
          The Tool is a client-side web application that allows users to explore and test
          functionality within the CIP-113 Programmable Token environment, including the
          registration of programmable token policies with on-chain validation logic, the
          administration of token lifecycles and related functionality such as minting,
          denylisting and forced transfers, and the monitoring of protocol state, token balances
          and transaction history. The Tool serves as a reference implementation of CIP-113 and is
          intended to demonstrate how programmable token functionality and associated controls can
          be implemented on the Cardano blockchain.
        </P>
        <Strong>
          The Tool is provided solely for general testing, demonstration and evaluation purposes
          and is not intended or suitable for use as a production environment.
        </Strong>
        <P>Access to the Tool is provided by the Cardano Foundation.</P>

        <H3>Permitted Use</H3>
        <P>
          You may use the Tool for legitimate testing, demonstration and evaluation of CIP-113
          programmable token functionality. You agree not to use the Tool for any unlawful,
          fraudulent, deceptive or otherwise improper purpose, or in connection with any
          unauthorized issuance, transfer or administration of tokens or assets.
        </P>
        <Strong>
          You are solely responsible for your use of the Tool, including any transactions
          initiated, data submitted, configurations applied or tokens created through it, and for
          ensuring that such use complies with all applicable legal and regulatory requirements.
        </Strong>

        <H3>Intellectual Property</H3>
        <P>
          Any token configurations, policies, metadata or other user-generated content created
          using the Tool remain yours, subject to any applicable third-party rights. Your use of
          such content and any associated tokens or assets remains subject to applicable laws and
          any relevant third-party terms.
        </P>
        <P>
          The Tool itself, including its source code, documentation and associated materials, is
          made available under an open-source license via{" "}
          <Ext href={REPO_URL}>GitHub</Ext>. Any use, reproduction, modification or distribution
          of the Tool is governed by the terms of the applicable license. Nothing in these Terms
          limits or modifies the rights granted under that open-source license.
        </P>

        <H3>No Warranty</H3>
        <P>
          This Tool is provided &ldquo;as is&rdquo; and &ldquo;as available&rdquo; without any
          warranties or representations of any kind, whether express or implied. No warranty is
          made that the tool will be uninterrupted, error-free, or that any generated output will
          be accurate, complete, compliant with applicable laws or regulatory requirements, or
          suitable for any particular purpose.
        </P>
        <Strong>
          You remain solely responsible for reviewing, validating, adapting and approving any
          generated content before relying on or using it, including for any regulatory filing or
          submission.
        </Strong>
        <Strong>
          No maintenance, technical support or other assistance is provided or guaranteed in
          connection with the Tool. We reserve the right to modify, restrict, suspend or
          discontinue the Tool, in whole or in part, at any time and without prior notice.
        </Strong>

        <H3>Limitation of Liability</H3>
        <P>
          To the fullest extent permitted by applicable law, any liability arising out of or in
          connection with the use of, or inability to use, the Tool or any generated output is
          excluded. This includes, without limitation, liability for any direct, indirect,
          incidental, consequential, special, exemplary or punitive damages, loss of profits,
          revenue, business opportunities, data or goodwill, business interruption, or regulatory
          fines, penalties or other sanctions.
        </P>

        <H3>Indemnification</H3>
        <P>
          You agree to indemnify and hold harmless the Cardano Foundation, its affiliates,
          directors, officers, employees and representatives from and against any claims,
          liabilities, damages, losses and expenses (including reasonable legal fees) arising out
          of or relating to your use or misuse of the Tool, any tokens, transactions,
          configurations, policies or other activities created, initiated or performed through the
          Tool, your violation of these Terms, or your violation of any applicable law, regulatory
          requirement or third-party right.
        </P>

        <H3>Changes to Terms</H3>
        <P>
          These Terms may be updated from time to time. Continued use of the Tool after changes
          have been posted constitutes acceptance of the revised Terms. It is your responsibility
          to review these Terms periodically.
        </P>
      </section>

      <section className="mt-16">
        <H2 id="privacy">Privacy &amp; Data Processing</H2>

        <H3>Privacy</H3>
        <P>
          The Tool operates as a client-side application, and information entered into the Tool is
          generally processed locally within your web browser. Certain information may nevertheless
          be transmitted where necessary to provide functionality requested by you, including to
          Cardano Foundation infrastructure, the Cardano network or third-party services. This may
          include technical information ordinarily transmitted when accessing or interacting with
          online services, such as IP addresses, device and browser information and network request
          data.
        </P>
        <P>
          <strong className="font-semibold text-dark-100">
            No analytics, advertising or tracking technologies are used.
          </strong>{" "}
          The Tool uses localStorage and sessionStorage for functional purposes, including user
          preferences, settings, protocol and registration state, and temporary session
          information. When you use certain KYC/RWA functionality, the Tool may also set a
          functional cookie containing a signed identity attestation and related technical
          information required to make that attestation available to the relevant functionality.
          Such storage is used solely to provide or maintain functionality and is not used for
          analytics, advertising or tracking.
        </P>
        <P>
          Information submitted as part of a blockchain transaction may be transmitted to the
          Cardano network and become publicly accessible and permanently recorded on the
          blockchain. You are solely responsible for any information or data submitted to or
          recorded on the blockchain through your use of the Tool, and the Cardano Foundation
          accepts no responsibility or liability for the submission, publication, availability,
          immutability or subsequent use of such information or data.
        </P>
        <P>
          The privacy provisions herein apply solely to the Tool. The processing of personal data
          in connection with the remainder of the Cardano Foundation website(s) is governed by the
          relevant <Ext href={CF_PRIVACY_URL}>Privacy Policy</Ext>.
        </P>

        <H3>Data Storage &amp; Data Processing</H3>
        <P>
          Data used locally by the Tool is generally stored and processed within your browser.
          Session data may be stored in sessionStorage and deleted when the relevant session is
          closed, while user preferences, settings and other persistent information may be stored
          in localStorage until cleared by you. Certain functionality, including KYC/RWA features,
          may also use functional cookies to store a signed identity attestation and related
          technical information.
        </P>
        <P>
          Where necessary to provide functionality requested by you, information may be transmitted
          to Cardano Foundation infrastructure, the Cardano network, a connected wallet or
          third-party services. This may include technical information associated with such
          interactions, such as IP addresses and network request data. Data submitted as part of a
          blockchain transaction may be publicly and permanently recorded on the Cardano
          blockchain.
        </P>
        <P>
          You are responsible for managing and securing locally stored data and may clear such data
          through your browser settings, subject to any data that has already been transmitted to
          third parties or recorded on the blockchain.
        </P>

        <H3>Third-Party Services</H3>
        <P>
          The Tool may interact with third-party services and infrastructure, including wallet
          providers, blockchain network and API infrastructure, and externally hosted resources,
          where necessary to provide certain functionality. Your interaction with such services may
          result in information, including technical and network information, being transmitted to
          the relevant provider and is subject to that provider&rsquo;s applicable terms and
          privacy practices. The Cardano Foundation does not control and is not responsible for the
          availability, operation, security or data processing practices of third-party services.
        </P>
      </section>
    </main>
  );
}
