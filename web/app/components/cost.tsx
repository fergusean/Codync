import { Check } from "@phosphor-icons/react/ssr";
import DownloadButton from "./download-button";
import Halftone from "./halftone";
import Reveal from "./reveal";

const included = [
  "Unlimited bots, rooms and threads",
  "Approvals, notifications and Live Activities on iPhone",
  "Memory, routines and webhooks",
  "Connectors, apps through Composio and skills",
  "Voice calls and remote screen",
  "Mac, Linux, iPhone and a terminal UI",
  "The end-to-end encrypted relay",
];

// Pricing, said once: one plan, $0, everything in it.
export default function Cost() {
  return (
    <section id="pricing" className="relative scroll-mt-20 overflow-hidden px-4 py-20 sm:px-6 md:py-28">
      <Halftone corner="bottom-left" className="w-[30rem] max-w-[70vw] text-neutral-50 opacity-[0.1]" />
      <div className="relative mx-auto max-w-5xl">
        <Reveal className="text-center">
          <h2 className="text-3xl font-semibold tracking-tight text-neutral-50 md:text-5xl">Pricing</h2>
          <p className="mx-auto mt-5 max-w-[34rem] text-lg leading-relaxed text-neutral-400">
            One plan, and it&apos;s free. No hidden fees, no paid tier, no locked features.
          </p>
        </Reveal>

        <Reveal className="mt-12">
          <div className="grid grid-cols-1 overflow-hidden rounded-[2rem] bg-neutral-900 md:grid-cols-[1fr_1.2fr]">
            <div className="flex flex-col justify-between gap-10 p-8 md:p-12">
              <div>
                <p className="text-sm font-medium text-neutral-400">Codync</p>
                <p className="mt-4 flex items-baseline gap-2">
                  <span className="text-7xl font-semibold tracking-tighter text-neutral-50 md:text-8xl">$0</span>
                  <span className="text-neutral-500">forever</span>
                </p>
                <p className="mt-4 text-neutral-400">MIT licensed. No account needed.</p>
              </div>
              <DownloadButton />
            </div>
            <div className="bg-neutral-950/60 p-8 md:p-12">
              <p className="text-sm font-medium text-neutral-200">Everything is included:</p>
              <ul className="mt-6 space-y-3.5">
                {included.map((item) => (
                  <li key={item} className="flex gap-3 text-neutral-300">
                    <Check size={18} weight="bold" className="mt-0.5 shrink-0 text-neutral-50" />
                    {item}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </Reveal>

        <Reveal className="mt-4">
          <p className="rounded-2xl bg-neutral-900 px-6 py-4 text-center text-neutral-400">
            <span className="text-neutral-100">Already pay for Claude, ChatGPT or Gemini?</span> Your bots run on those
            plans; you pay your agent&apos;s provider and nothing else.
          </p>
        </Reveal>
      </div>
    </section>
  );
}
