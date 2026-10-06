"use client";

import { motion, MotionConfig } from "framer-motion";
import { AppleLogo, DeviceMobile, GithubLogo, LinuxLogo, OpenAiLogo, Terminal, WindowsLogo } from "@phosphor-icons/react";
import DownloadButton from "./download-button";
import Halftone from "./halftone";
import MacWindow from "./mac-window";
import LivePhones from "./live-phone/live-phones";
import { CharacterAvatar } from "./live-phone/avatar";
import { APP_STORE, GITHUB, PRODUCT_HUNT } from "../links";

const ease = [0.16, 1, 0.3, 1] as const;

// Agents named in the subtitle, each with its mark (Simple Icons; OpenAI's mark from Phosphor).
const agents: [name: string, icon: string | null][] = [
  ["Claude Code", "https://cdn.simpleicons.org/claude/ffffff"],
  ["Codex", null],
  ["Gemini", "https://cdn.simpleicons.org/googlegemini/ffffff"],
  ["Cursor", "https://cdn.simpleicons.org/cursor/ffffff"],
];

export default function Hero() {
  // Same markup on server and client; MotionConfig drops the movement for reduced motion.
  const rise = (delay: number) => ({
    initial: { opacity: 0, y: 20 },
    animate: { opacity: 1, y: 0 },
    transition: { duration: 0.7, delay, ease },
  });

  return (
    <MotionConfig reducedMotion="user">
      <section className="relative overflow-hidden px-4 pt-14 pb-20 sm:px-6 md:pt-20 md:pb-28">
        <Halftone corner="top-right" className="w-[44rem] max-w-[90vw] text-neutral-50 opacity-[0.12]" />
        <Halftone corner="top-left" className="w-[30rem] max-w-[70vw] text-neutral-50 opacity-[0.08]" />
        <motion.div {...rise(0)} className="relative mb-10 flex justify-center md:mb-14">
          <a
            href={PRODUCT_HUNT}
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center gap-3 transition hover:opacity-80"
          >
            {/* eslint-disable-next-line @next/next/no-img-element -- live badge served by Product Hunt */}
            <img
              src="https://api.producthunt.com/widgets/embed-image/v1/featured.svg?post_id=1267264&theme=dark"
              alt="Codync on Product Hunt"
              width={250}
              height={54}
              className="h-10 w-auto"
            />
            <span className="text-sm font-medium text-neutral-300">#16 Product of the Day</span>
          </a>
        </motion.div>
        <div className="relative mx-auto grid max-w-6xl grid-cols-1 items-center gap-14 md:grid-cols-[1.15fr_1fr] md:gap-8">
          <div className="flex flex-col items-start text-left">
            <motion.div {...rise(0)} className="flex flex-col items-start gap-4">
              <span className="inline-flex items-center gap-2 rounded-full bg-neutral-900 py-1.5 pr-4 pl-1.5 text-sm">
                <span className="rounded-full bg-neutral-50 px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap text-neutral-950">100% free</span>
                <span className="text-neutral-300">No hidden fees, no paid tier</span>
              </span>
            </motion.div>

            <motion.h1
              {...rise(0.04)}
              className="mt-8 flex flex-wrap items-center gap-x-4 text-6xl font-semibold leading-none tracking-tighter text-neutral-50 sm:text-7xl md:gap-x-5 lg:text-8xl"
            >
              Meet
              <span className="inline-flex size-[0.95em] items-center justify-center rounded-[0.24em] bg-neutral-50 [&_svg]:size-[78%]">
                <CharacterAvatar shape="squircle" color="blue" size={88} mood="working" />
              </span>
              Codync
            </motion.h1>

            <motion.p {...rise(0.1)} className="mt-8 max-w-[36rem] text-lg leading-relaxed text-neutral-400">
              The open-source Grok Bot alternative, 100% free. Persistent AI teammates on your own computer, for code
              and everyday life. Run{" "}
              {agents.map(([name, icon], i) => (
                <span key={name}>
                  <span className="inline-flex items-baseline gap-1.5 font-medium whitespace-nowrap text-neutral-50">
                    {icon ? (
                      // eslint-disable-next-line @next/next/no-img-element -- brand marks from Simple Icons
                      <img src={icon} alt="" width={18} height={18} className="size-[0.9em] self-center" />
                    ) : (
                      <OpenAiLogo size="0.95em" weight="fill" className="self-center" />
                    )}
                    {name}
                    {i < agents.length - 2 ? "," : ""}
                  </span>
                  {i === agents.length - 2 ? " and " : " "}
                </span>
              ))}
              with the plans you already pay for.
            </motion.p>

            <motion.div {...rise(0.16)} className="mt-10 flex flex-wrap items-center gap-3">
              <DownloadButton />
              <a
                href={APP_STORE}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex h-12 items-center gap-2 rounded-full bg-neutral-900 px-6 font-medium text-neutral-100 transition hover:bg-neutral-800 active:scale-[0.98]"
              >
                <DeviceMobile size={18} weight="fill" />
                iPhone app
              </a>
              <a
                href={GITHUB}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex h-12 items-center gap-2 rounded-full bg-neutral-900 px-6 font-medium text-neutral-100 transition hover:bg-neutral-800 active:scale-[0.98]"
              >
                <GithubLogo size={18} weight="fill" />
                Star on GitHub
              </a>
            </motion.div>

            <motion.p {...rise(0.2)} className="mt-6 flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-neutral-500">
              <span className="inline-flex items-center gap-1.5"><AppleLogo size={15} weight="fill" /> macOS</span>
              <span className="inline-flex items-center gap-1.5"><WindowsLogo size={15} weight="fill" /> Windows</span>
              <span className="inline-flex items-center gap-1.5"><LinuxLogo size={15} weight="fill" /> Linux</span>
              <span className="inline-flex items-center gap-1.5"><DeviceMobile size={15} weight="fill" /> iPhone</span>
              <span className="inline-flex items-center gap-1.5"><Terminal size={15} weight="bold" /> Terminal</span>
              <a href={GITHUB} className="underline underline-offset-4 hover:text-neutral-300">MIT licensed</a>
            </motion.p>
          </div>

          {/* The live iPhones; the front one hangs below the back one, so reserve room for it. */}
          <div className="relative mx-auto mb-16 w-full max-w-[26rem] md:mb-20 md:max-w-none">
            <LivePhones />
          </div>
        </div>

        <motion.div
          id="product"
          initial={{ opacity: 0, y: 40 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.9, delay: 0.25, ease }}
          className="relative mx-auto mt-16 max-w-6xl scroll-mt-24 md:mt-20"
        >
          <div className="mb-5 flex flex-col items-center gap-2 text-center">
            <span className="inline-flex items-center gap-2 rounded-full bg-neutral-900 px-3 py-1 text-xs font-medium tracking-[0.2em] text-neutral-200 uppercase">
              <span className="relative flex size-2">
                <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-75 motion-reduce:hidden" />
                <span className="relative inline-flex size-2 rounded-full bg-emerald-400" />
              </span>
              Live demo
            </span>
            <p className="text-sm text-neutral-400">The real Codync desktop app, running in your browser. Click a bot, open a room, send a message.</p>
          </div>
          <MacWindow />
        </motion.div>
      </section>
    </MotionConfig>
  );
}
