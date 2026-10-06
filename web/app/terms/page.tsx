import Link from "next/link";

export default function Terms() {
  return (
    <main className="flex-1 flex flex-col items-center px-6 py-16">
      <article className="max-w-2xl w-full space-y-6">
        <h1 className="text-3xl font-bold text-white">Terms of Use</h1>
        <p className="text-neutral-400 text-sm">Last updated: September 25, 2026</p>

        <Section title="1. Acceptance of Terms">
          By using Codync you agree to these terms. If you don&apos;t agree, don&apos;t use the app.
        </Section>

        <Section title="2. The service">
          Codync is free, open-source software (MIT license) that lets you message AI agents running on your own computer from your iPhone, Mac, Windows PC or Linux desktop.
        </Section>

        <Section title="3. Your responsibility">
          <ul className="list-disc pl-5 space-y-2">
            <li>Agents act on your computer with your permissions. Review approval requests before allowing them; &quot;Approve automatically&quot; lets agents act without asking.</li>
            <li>Keep your pairing code private. Anyone with it can run agents on your computer; reset it with <code>codync-host reset-token</code>.</li>
            <li>You are responsible for complying with the terms of the agents and services you connect.</li>
          </ul>
        </Section>

        <Section title="4. Intellectual property">
          The source code is available at github.com/leepokai/Codync under the MIT license. Names and logos of third-party agents belong to their owners.
        </Section>

        <Section title="5. Disclaimer of warranties">
          Codync is provided &quot;as is&quot;, without warranties of any kind. We don&apos;t guarantee that agents will behave as intended or that the service will be uninterrupted.
        </Section>

        <Section title="6. Limitation of liability">
          To the maximum extent permitted by law, we are not liable for any damages arising from your use of Codync or of the agents it runs, including changes they make to your files.
        </Section>

        <Section title="7. Changes">
          We may update these terms. Continued use after changes means you accept them.
        </Section>

        <Section title="8. Contact">
          Questions? Open an issue at{" "}
          <a href="https://github.com/leepokai/Codync/issues" className="text-white underline">github.com/leepokai/Codync/issues</a>.
        </Section>

        <div className="pt-4">
          <p className="text-neutral-400">
            <Link href="/privacy" className="text-white underline">Privacy Policy</Link>
          </p>
        </div>

        <div className="pt-4">
          <Link href="/" className="text-sm text-neutral-500 hover:text-neutral-300 transition-colors">
            &larr; Back to home
          </Link>
        </div>
      </article>
    </main>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h2 className="text-xl font-semibold text-white mb-2">{title}</h2>
      <div className="text-neutral-400 leading-relaxed">{children}</div>
    </section>
  );
}
