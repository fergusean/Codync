import Link from "next/link";

export default function Privacy() {
  return (
    <main className="flex-1 flex flex-col items-center px-6 py-16">
      <article className="max-w-2xl w-full space-y-6">
        <h1 className="text-3xl font-bold text-white">Privacy Policy</h1>
        <p className="text-neutral-400 text-sm">Last updated: October 4, 2026</p>

        <Section title="Overview">
          Codync lets you message the AI agents that run on your own computer. It is built so that your files, conversations and credentials stay on your devices.
        </Section>

        <Section title="What stays on your devices">
          <ul className="list-disc pl-5 space-y-2">
            <li><strong>Bots and conversations</strong> are stored by the Codync host on your computer (in <code>~/.codync</code>). Your phone keeps a cache so the app opens instantly.</li>
            <li><strong>Your phone talks directly to your computer</strong> over your local network or Tailscale. There is no Codync server in between and no Codync account.</li>
            <li><strong>Agents run with your own logins</strong> (Claude Code, Codex, Cursor and others). Codync never sees or stores their credentials.</li>
            <li><strong>Usage limits</strong> are read locally from the agents installed on your computer. Only percentages reach your phone.</li>
          </ul>
        </Section>

        <Section title="Push notifications">
          To notify you when a bot needs you or finishes, your computer sends a short alert (the bot&apos;s name and a one-line preview) through our push relay, a Cloudflare Worker that forwards it to Apple Push Notification service. The relay does not store notifications. Your device token is encrypted into a ticket that only the relay can read; your computer never sees the raw token.
        </Section>

        <Section title="Data we collect">
          None. Codync has no analytics, no tracking and no advertising identifiers.
        </Section>

        <Section title="Third-party services">
          <ul className="list-disc pl-5 space-y-2">
            <li><strong>Apple Push Notification service</strong>: delivers notifications and Live Activity updates.</li>
            <li><strong>Cloudflare Workers</strong>: runs the push relay (nothing stored).</li>
            <li><strong>The AI agents you choose</strong>: they run on your computer under their own terms and privacy policies.</li>
            <li><strong>OpenAI or Google (optional, voice calls)</strong>: only if you add your own OpenAI or Gemini API key in a call&apos;s settings. Your voice then goes from your iPhone or Mac directly to that provider, under your account and its privacy policy, together with the bot&apos;s name and description and the chat messages it asks for during the call. The key is stored encrypted on your computer, which uses it only to start calls and list models. Without a key, voice calls use Apple speech recognition on your device.</li>
          </ul>
        </Section>

        <Section title="Data retention">
          Everything lives on your devices. Delete a bot to remove its conversation; uninstall the host and delete <code>~/.codync</code> to remove all of it. Deleting the iPhone app removes its cache.
        </Section>

        <Section title="Children's privacy">
          Codync is not directed at children under the age of 13.
        </Section>

        <Section title="Changes">
          We may update this policy. Changes will be posted on this page with a new date.
        </Section>

        <Section title="Contact">
          Questions? Open an issue at{" "}
          <a href="https://github.com/leepokai/Codync/issues" className="text-white underline">github.com/leepokai/Codync/issues</a>.
        </Section>

        <div className="pt-4">
          <p className="text-neutral-400">
            <Link href="/terms" className="text-white underline">Terms of Use</Link>
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
