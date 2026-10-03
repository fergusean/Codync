"use client";

import { motion, useReducedMotion } from "framer-motion";
import { AppleLogo, GithubLogo } from "@phosphor-icons/react";
import Image from "next/image";
import Phone from "./phone";
import { APP_STORE, DMG, GITHUB, PRODUCT_HUNT } from "../links";

const ease = [0.16, 1, 0.3, 1] as const;

export default function Hero() {
  const reduce = useReducedMotion();
  const rise = (delay: number) =>
    reduce
      ? {}
      : { initial: { opacity: 0, y: 20 }, animate: { opacity: 1, y: 0 }, transition: { duration: 0.7, delay, ease } };

  return (
    <section className="px-4 pt-12 pb-20 sm:px-6 md:pt-20 md:pb-28">
      <div className="mx-auto grid max-w-6xl grid-cols-1 items-center gap-14 md:grid-cols-[1.1fr_1fr] md:gap-8">
        <div>
          <motion.p {...rise(0)} className="mb-5 inline-flex items-center rounded-full bg-neutral-900 px-4 py-1.5 text-sm font-medium text-orange-400">
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
            your iPhone, Mac, Linux desktop or a terminal over SSH. Free, MIT licensed, and every feature of Grok
            Bot and Muse, 1:1.
          </motion.p>
          <motion.div {...rise(0.18)} className="mt-9 flex flex-col gap-3 sm:flex-row">
            <a
              href={DMG}
              className="inline-flex items-center justify-center gap-2 rounded-full bg-neutral-50 px-6 py-3 font-medium text-neutral-950 transition hover:bg-white active:scale-[0.98]"
            >
              <AppleLogo size={18} weight="fill" />
              Download for Mac
            </a>
            <div className="flex gap-3">
              <a
                href={APP_STORE}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex justify-center transition hover:opacity-80 active:scale-[0.98]"
              >
                <Image src="/app-store-badge.svg" alt="Download on the App Store" width={144} height={48} className="h-12 w-auto" />
              </a>
              <a
                href={GITHUB}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex flex-1 items-center justify-center gap-2 whitespace-nowrap rounded-full bg-neutral-900 px-5 py-3 font-medium text-neutral-100 sm:flex-none sm:px-6 transition hover:bg-neutral-800 active:scale-[0.98]"
              >
                <GithubLogo size={18} weight="fill" />
                Star on GitHub
              </a>
            </div>
          </motion.div>
          <motion.a
            {...rise(0.24)}
            href={PRODUCT_HUNT}
            target="_blank"
            rel="noopener noreferrer"
            className="mt-6 inline-flex transition hover:opacity-80"
          >
            {/* eslint-disable-next-line @next/next/no-img-element -- live badge served by Product Hunt */}
            <img
              src="https://api.producthunt.com/widgets/embed-image/v1/featured.svg?post_id=1267264&theme=dark"
              alt="Codync on Product Hunt"
              width={250}
              height={54}
              className="h-[54px] w-[250px]"
            />
          </motion.a>
        </div>

        <div className="relative mx-auto w-full max-w-[26rem] md:max-w-none">
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
