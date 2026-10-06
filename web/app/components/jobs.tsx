"use client";

import { useState } from "react";
import { AnimatePresence, motion, MotionConfig } from "framer-motion";
import { CharacterAvatar } from "./live-phone/avatar";

type Job = { name: string; shape: string; color: string; summary: string; detail: string; message: string[] };

// Example bots. Everything here is something a bot does with its agent and the apps you connect.
const jobs: Job[] = [
  {
    name: "Code Reviewer",
    shape: "pebble",
    color: "violet",
    summary: "Reviews every change before you merge.",
    detail: "Reads the diff, runs the tests and flags what would break, then asks the bot that wrote it to fix it.",
    message: ["Two issues in src/pace.js before this ships:", "1. A bad date gets bucketed as NaN-WNaN instead of raising.", "2. Dec 30, 2024 still lands in week 1 of 2024.", "@Scout, can you take the first one?"],
  },
  {
    name: "Inbox Triage",
    shape: "cloud",
    color: "cyan",
    summary: "Clears your inbox every morning.",
    detail: "Through your connected email it sorts what came in overnight, drafts replies in your voice and leaves them for your ok.",
    message: ["Inbox at zero. 4 drafts are waiting for you:", "Reply to Mia about Friday's demo (accepts 3 pm).", "Decline the vendor webinar, politely.", "Two receipts filed under Expenses."],
  },
  {
    name: "Weekly Planner",
    shape: "squircle",
    color: "orange",
    summary: "Plans your week before Monday starts.",
    detail: "Reads your calendar and open tasks, blocks focus time, and tells you what will not fit.",
    message: ["Your week is planned:", "Focus blocks Tue and Thu mornings for the release.", "Moved the 1:1 with Sam to Wednesday.", "The docs rewrite doesn't fit; want me to move it to next week?"],
  },
  {
    name: "Release Notes",
    shape: "tablet",
    color: "green",
    summary: "Writes the changelog from what actually merged.",
    detail: "Reads the week's merged work and drafts notes for users, not for engineers, ready for your review.",
    message: ["Draft for 2.8.0 is in CHANGELOG.md:", "Team widget: pick the bots in each spot.", "Live Activity shows sending and waiting for your computer.", "Want me to post it to the release when you approve?"],
  },
  {
    name: "Bug Reproducer",
    shape: "hex",
    color: "red",
    summary: "Turns a bug report into a failing test.",
    detail: "Reproduces the report in your repo, writes the smallest failing test, and proposes a fix you can approve.",
    message: ["Reproduced issue #212:", "isoWeek('2026-12-31') returns 2026-W53 on UTC+8.", "Added a failing test in test/pace.test.js.", "Fix is ready; approve the edit to src/pace.js?"],
  },
  {
    name: "Researcher",
    shape: "teardrop",
    color: "magenta",
    summary: "Comes back with a short brief and its sources.",
    detail: "Searches the web, reads the pages, and writes what you need to know in a few lines, with every link.",
    message: ["Brief on running apps that export ISO weeks:", "Three of five use ISO 8601 weeks; two use US weeks.", "Garmin and Strava both start weeks on Monday.", "Sources are in the thread, 6 links."],
  },
];

export default function Jobs() {
  const [active, setActive] = useState(0);
  const job = jobs[active];
  return (
    <MotionConfig reducedMotion="user">
      <section className="px-4 py-20 sm:px-6 md:py-28">
        <div className="mx-auto grid max-w-6xl grid-cols-1 gap-12 md:grid-cols-[1fr_1.1fr] md:gap-16">
          <div>
            <h2 className="text-3xl font-semibold tracking-tight text-neutral-50 md:text-5xl">Give each bot a job</h2>
            <div role="tablist" aria-label="Example bots" className="mt-8 flex flex-wrap gap-2">
              {jobs.map((j, i) => (
                <button
                  key={j.name}
                  role="tab"
                  aria-selected={i === active}
                  onClick={() => setActive(i)}
                  className={`inline-flex items-center gap-2 rounded-full px-3.5 py-2 text-sm transition active:scale-[0.98] ${i === active ? "bg-neutral-50 text-neutral-950" : "bg-neutral-900 text-neutral-400 hover:text-neutral-100"}`}
                >
                  {j.name}
                </button>
              ))}
            </div>
            <p className="mt-8 max-w-[28rem] text-lg leading-relaxed">
              <span className="text-neutral-50">{job.summary}</span>{" "}
              <span className="text-neutral-400">{job.detail}</span>
            </p>
          </div>

          <div className="relative rounded-[2rem] bg-neutral-900 p-6 md:p-8" role="tabpanel" aria-label={job.name}>
            <AnimatePresence mode="wait">
              <motion.div
                key={job.name}
                initial={{ opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -8 }}
                transition={{ duration: 0.3, ease: [0.16, 1, 0.3, 1] }}
              >
                <div className="flex items-center gap-3">
                  <span className="flex size-10 items-center justify-center rounded-xl bg-neutral-50">
                    <CharacterAvatar shape={job.shape} color={job.color} size={30} mood="working" />
                  </span>
                  <div>
                    <p className="font-medium text-neutral-50">{job.name}</p>
                    <p className="text-xs text-neutral-500">Finished just now</p>
                  </div>
                </div>
                <div className="mt-6 space-y-1.5 rounded-2xl bg-neutral-800 p-5 text-[15px] leading-relaxed text-neutral-200">
                  {job.message.map((line) => (
                    <p key={line}>{line}</p>
                  ))}
                </div>
              </motion.div>
            </AnimatePresence>
          </div>
        </div>
      </section>
    </MotionConfig>
  );
}
