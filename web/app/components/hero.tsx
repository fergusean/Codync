"use client";

import { motion, useReducedMotion } from "framer-motion";
import { AppleLogo, DeviceMobile, GithubLogo } from "@phosphor-icons/react";
import Halftone from "./halftone";
import Phone from "./phone";
import { APP_STORE, DMG, GITHUB, PRODUCT_HUNT } from "../links";

const ease = [0.16, 1, 0.3, 1] as const;
const button =
  "inline-flex h-12 items-center justify-center gap-2 whitespace-nowrap rounded-full px-6 font-medium transition active:scale-[0.98]";
const secondary = "bg-neutral-900 text-neutral-100 hover:bg-neutral-800";

export default function Hero() {
  const reduce = useReducedMotion();
  const rise = (delay: number) =>
    reduce
      ? {}
      : { initial: { opacity: 0, y: 20 }, animate: { opacity: 1, y: 0 }, transition: { duration: 0.7, delay, ease } };

  return (
    <section className="relative overflow-hidden px-4 pt-12 pb-32 sm:px-6 md:pt-20 md:pb-44">
      <Halftone corner="top-right" className="w-[44rem] max-w-[90vw] text-neutral-50 opacity-[0.16]" />
      <Halftone corner="bottom-left" className="w-[22rem] max-w-[60vw] text-neutral-50 opacity-[0.12]" />
      <motion.a
        {...rise(0)}
        href={PRODUCT_HUNT}
        target="_blank"
        rel="noopener noreferrer"
        className="relative mx-auto mb-10 flex w-fit items-center gap-3 transition hover:opacity-80 md:mb-14"
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
      </motion.a>
      <div className="relative mx-auto grid max-w-6xl grid-cols-1 items-center gap-14 md:grid-cols-[1.1fr_1fr] md:gap-8">
        <div>
          <motion.p {...rise(0)} className="mb-5 text-xs font-medium tracking-[0.25em] text-neutral-400 uppercase">
            <span className="text-neutral-50">100% free</span>
            <span className="mx-2 text-neutral-600">/</span>
            The open-source Grok Bot / Muse alternative
          </motion.p>
          <motion.h1
            {...rise(0.04)}
            className="text-4xl font-semibold leading-[1.05] tracking-tighter text-neutral-50 sm:text-5xl lg:text-6xl"
          >
            Message your coding agents like teammates.
          </motion.h1>
          <motion.p {...rise(0.1)} className="mt-6 max-w-[34rem] text-lg leading-relaxed text-neutral-400">
            Codync runs Claude Code, Codex and 40+ agents as named bots on your computer. Reply and approve from
            your iPhone, Mac, Linux desktop or a terminal over SSH. 100% free and MIT licensed, with no hidden
            fees, and every feature of Grok Bot and Muse, 1:1.
          </motion.p>
          <motion.div {...rise(0.18)} className="mt-9 flex flex-wrap gap-3">
            <a href={DMG} className={`${button} bg-neutral-50 text-neutral-950 hover:bg-white`}>
              <AppleLogo size={18} weight="fill" />
              Download for Mac
            </a>
            <a href={APP_STORE} target="_blank" rel="noopener noreferrer" className={`${button} ${secondary}`}>
              <DeviceMobile size={18} weight="fill" />
              iPhone app
            </a>
            <a href={GITHUB} target="_blank" rel="noopener noreferrer" className={`${button} ${secondary}`}>
              <GithubLogo size={18} weight="fill" />
              Star on GitHub
            </a>
          </motion.div>
        </div>

        {/* The second phone hangs below this column, so reserve room for it. */}
        <div className="relative mx-auto mb-16 w-full max-w-[26rem] md:mb-20 md:max-w-none">
          <motion.div
            {...(reduce
              ? {}
              : { initial: { opacity: 0, y: 40 }, animate: { opacity: 1, y: 0 }, transition: { duration: 0.9, delay: 0.1, ease } })}
            className="relative w-[58%]"
          >
            <Phone src="/screens/roster.webp" alt="The Codync bot list: Pacer needs approval, the other bots have replied" eager />
          </motion.div>
          <motion.div
            {...(reduce
              ? {}
              : { initial: { opacity: 0, y: 60 }, animate: { opacity: 1, y: 0 }, transition: { duration: 0.9, delay: 0.25, ease } })}
            className="absolute top-[10%] right-0 z-10 w-[58%]"
          >
            <Phone src="/screens/approval.webp" alt="An approval card asking to edit src/pace.js with Allow once, Always allow and Deny" eager />
          </motion.div>
        </div>
      </div>
    </section>
  );
}
