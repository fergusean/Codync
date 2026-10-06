"use client";

import { motion, MotionConfig } from "framer-motion";
import { AppleLogo, DeviceMobile, LinuxLogo, OpenAiLogo, Terminal } from "@phosphor-icons/react";
import DownloadButton from "./download-button";
import Halftone from "./halftone";
import MacWindow from "./mac-window";
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
        <div className="relative mx-auto flex max-w-5xl flex-col items-center text-center">
          <motion.a
            {...rise(0)}
            href={PRODUCT_HUNT}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-2 rounded-full bg-neutral-900 py-1.5 pr-4 pl-1.5 text-sm transition hover:bg-neutral-800"
          >
            <span className="rounded-full bg-neutral-50 px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap text-neutral-950">100% free</span>
            <span className="text-neutral-300">#16 Product of the Day<span className="hidden sm:inline"> on Product Hunt</span></span>
          </motion.a>

          <motion.h1
            {...rise(0.04)}
            className="mt-8 flex flex-wrap items-center justify-center gap-x-4 text-6xl font-semibold leading-none tracking-tighter text-neutral-50 sm:text-7xl md:gap-x-6 md:text-8xl"
          >
            Meet
            <span className="inline-flex size-[0.95em] items-center justify-center rounded-[0.24em] bg-neutral-50 [&_svg]:size-[78%]">
              <CharacterAvatar shape="squircle" color="blue" size={88} mood="working" />
            </span>
            Codync
          </motion.h1>

          <motion.p {...rise(0.1)} className="mt-8 max-w-[44rem] text-lg leading-relaxed text-neutral-400 md:text-xl">
            The 100% free, open-source Grok Bot alternative. Persistent AI teammates on your own computer, for code
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

          <motion.div {...rise(0.16)} className="mt-10 flex flex-wrap items-center justify-center gap-3">
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
          </motion.div>

          <motion.p {...rise(0.2)} className="mt-6 flex flex-wrap items-center justify-center gap-x-4 gap-y-2 text-sm text-neutral-500">
            <span className="inline-flex items-center gap-1.5"><AppleLogo size={15} weight="fill" /> macOS</span>
            <span className="inline-flex items-center gap-1.5"><LinuxLogo size={15} weight="fill" /> Linux</span>
            <span className="inline-flex items-center gap-1.5"><DeviceMobile size={15} weight="fill" /> iPhone</span>
            <span className="inline-flex items-center gap-1.5"><Terminal size={15} weight="bold" /> Terminal</span>
            <a href={GITHUB} className="underline underline-offset-4 hover:text-neutral-300">MIT licensed</a>
          </motion.p>
        </div>

        <motion.div
          id="product"
          initial={{ opacity: 0, y: 40 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.9, delay: 0.25, ease }}
          className="relative mx-auto mt-16 max-w-6xl scroll-mt-24 md:mt-20"
        >
          <MacWindow />
          <p className="mt-4 text-center text-sm text-neutral-500">The real Codync app, live. Click a bot, open a room, send a message.</p>
        </motion.div>
      </section>
    </MotionConfig>
  );
}
