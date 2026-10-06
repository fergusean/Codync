import { ArrowsClockwise, Brain, ChatsCircle, ShieldCheck } from "@phosphor-icons/react/ssr";
import Reveal from "./reveal";
import Jobs from "./jobs";
import LivePhones from "./live-phone/live-phones";
import { CharacterAvatar, GroupAvatar } from "./live-phone/avatar";
import DownloadButton from "./download-button";

const INK = "#f2f2f2";

// One wide card: the message, and a big bot peeking up from the corner.
export function Teammates() {
  return (
    <section className="px-4 py-12 sm:px-6 md:py-20">
      <Reveal className="mx-auto max-w-6xl">
        <div className="relative overflow-hidden rounded-[2rem] bg-neutral-900 p-8 md:min-h-[24rem] md:p-14">
          <div className="relative z-10 max-w-[30rem]">
            <h2 className="text-3xl font-semibold tracking-tight text-neutral-50 md:text-4xl">Message bots like teammates</h2>
            <p className="mt-5 text-lg leading-relaxed text-neutral-400">
              Give each bot a name, a folder and a job, then message it from your Mac, your iPhone or a terminal. It
              keeps one ongoing chat, remembers how you work, and comes back when it needs your approval.
            </p>
          </div>
          <div aria-hidden className="pointer-events-none mt-8 flex justify-end md:absolute md:-right-6 md:-bottom-24 md:mt-0">
            <CharacterAvatar shape="squircle" color="blue" size={360} mood="working" ink={INK} />
          </div>
        </div>
      </Reveal>
    </section>
  );
}

function Bubble({ who, text }: { who: string; text: string }) {
  return (
    <div className="max-w-[22rem] rounded-2xl bg-neutral-800 px-4 py-3 text-sm leading-relaxed text-neutral-200">
      <span className="mb-1 block text-xs font-medium text-neutral-500">{who}</span>
      {text}
    </div>
  );
}

const card = "flex h-full flex-col rounded-[2rem] bg-neutral-900 p-7 md:p-8";

// Four things a team of bots does, each with a small picture of it in the app.
export function ManyBots() {
  return (
    <section className="px-4 py-20 sm:px-6 md:py-28">
      <div className="mx-auto max-w-6xl">
        <Reveal className="mx-auto max-w-[40rem] text-center">
          <h2 className="text-3xl font-semibold tracking-tight text-neutral-50 md:text-5xl">Work with many bots at once</h2>
          <p className="mt-5 text-lg leading-relaxed text-neutral-400">
            One bot on your code, one on your inbox, one on next week. They run in parallel on your computer, each in
            its own session, and work together when you put them in a room.
          </p>
        </Reveal>

        <div className="mt-14 grid grid-cols-1 gap-4 md:grid-cols-2">
          <Reveal className="h-full">
            <div className={card}>
              <ChatsCircle size={24} className="text-neutral-50" />
              <h3 className="mt-4 text-xl font-semibold text-neutral-50">Put bots in a room</h3>
              <p className="mt-2 leading-relaxed text-neutral-400">
                Every member answers in its own session. They read each other, push back and hand work on. Mention one
                with @ to ask just that bot.
              </p>
              <div className="mt-8 space-y-3">
                <Bubble who="Reviewer" text="A bad date gets bucketed as NaN-WNaN instead of raising. @Scout, can you add a guard?" />
                <Bubble who="Scout" text="Added it: weeklyTotals now throws a RangeError on an invalid date, and all 5 tests pass." />
                <div className="flex items-center gap-2 pt-1 text-sm text-neutral-400">
                  <GroupAvatar size={22} members={[{ shape: "blob", color: "violet" }, { shape: "pebble", color: "green" }, { shape: "squircle", color: "orange" }]} />
                  Ship room, 3 bots
                </div>
              </div>
            </div>
          </Reveal>

          <Reveal className="h-full" delay={0.05}>
            <div className={card}>
              <ShieldCheck size={24} className="text-neutral-50" />
              <h3 className="mt-4 text-xl font-semibold text-neutral-50">Approve from anywhere</h3>
              <p className="mt-2 leading-relaxed text-neutral-400">
                When a bot wants to edit a file or run a command, the request comes to your phone. Allow once, always
                allow, or deny, from the lock screen if you like.
              </p>
              <div className="mt-8 max-w-[22rem] rounded-2xl bg-neutral-50 p-4 text-neutral-950">
                <p className="text-sm font-semibold">Wants to change files</p>
                <p className="mt-1 font-mono text-xs text-neutral-500">Edit src/pace.js</p>
                <div className="mt-3 divide-y divide-neutral-200 rounded-xl bg-white text-sm">
                  <p className="px-3 py-2 font-medium">Allow once</p>
                  <p className="px-3 py-2">Always allow</p>
                  <p className="px-3 py-2 text-red-600">Deny</p>
                </div>
              </div>
            </div>
          </Reveal>

          <Reveal className="h-full">
            <div className={card}>
              <Brain size={24} className="text-neutral-50" />
              <h3 className="mt-4 text-xl font-semibold text-neutral-50">Bots remember</h3>
              <p className="mt-2 leading-relaxed text-neutral-400">
                Each bot keeps its own memory of you and your work, and you can search everything it has ever said.
              </p>
              <div className="mt-8 space-y-3">
                <Bubble who="You" text="We release on Thursdays, and changelogs go in CHANGELOG.md." />
                <Bubble who="Pacer" text="Noted. I'll draft the notes on Wednesday evening so you can review them first." />
                <p className="inline-flex w-fit items-center gap-2 rounded-full bg-neutral-800 px-3 py-1.5 text-xs text-neutral-300">
                  <Brain size={14} /> Updated memory for Pacer
                </p>
              </div>
            </div>
          </Reveal>

          <Reveal className="h-full" delay={0.05}>
            <div className={card}>
              <ArrowsClockwise size={24} className="text-neutral-50" />
              <h3 className="mt-4 text-xl font-semibold text-neutral-50">Routines run on their own</h3>
              <p className="mt-2 leading-relaxed text-neutral-400">
                Give a bot an instruction and a time, or a webhook, and it runs while you sleep. Results land in its chat.
              </p>
              <ul className="mt-8 max-w-[22rem] space-y-2 text-sm">
                {[
                  ["Morning brief", "Every weekday at 8:30"],
                  ["Triage new issues", "When GitHub calls the webhook"],
                  ["Weekly changelog draft", "Wednesdays at 18:00"],
                ].map(([name, when]) => (
                  <li key={name} className="flex items-center justify-between gap-4 rounded-xl bg-neutral-800 px-4 py-3">
                    <span className="font-medium text-neutral-100">{name}</span>
                    <span className="text-neutral-500">{when}</span>
                  </li>
                ))}
              </ul>
            </div>
          </Reveal>
        </div>
      </div>
    </section>
  );
}

export function Pocket() {
  return (
    <section className="px-4 py-20 sm:px-6 md:py-28">
      <div className="mx-auto grid max-w-6xl grid-cols-1 items-center gap-14 md:grid-cols-[1fr_1.1fr]">
        <Reveal>
          <h2 className="text-3xl font-semibold tracking-tight text-neutral-50 md:text-5xl">In your pocket, too</h2>
          <p className="mt-5 max-w-[30rem] text-lg leading-relaxed text-neutral-400">
            A native iPhone app for the same bots. You get a ping when one needs you or finishes, a Live Activity while
            it works, widgets for your team and usage, voice calls with a bot, and the computer&apos;s screen when you
            need it. Try it: tap a bot.
          </p>
        </Reveal>
        {/* The front phone hangs below the back one, so reserve room for it. */}
        <div className="relative mx-auto mb-16 w-full max-w-[26rem] md:mb-20 md:max-w-none">
          <LivePhones />
        </div>
      </div>
    </section>
  );
}

export { Jobs };

// The last word: one line, the download, and a big bot rising from the bottom edge.
export function FinalCta() {
  return (
    <section className="relative overflow-hidden px-4 pt-24 sm:px-6 md:pt-32">
      <Reveal className="relative z-10 mx-auto flex max-w-3xl flex-col items-center text-center">
        <h2 className="text-4xl font-semibold tracking-tight text-neutral-50 md:text-6xl">Meet your first bot</h2>
        <p className="mt-5 text-lg text-neutral-400">An AI teammate on your own computer. 100% free.</p>
        <div className="mt-9">
          <DownloadButton />
        </div>
      </Reveal>
      <div aria-hidden className="pointer-events-none relative mx-auto mt-16 h-[220px] w-[520px] max-w-full overflow-hidden opacity-40 md:h-[300px] md:w-[640px]">
        <div className="absolute left-1/2 top-0 -translate-x-1/2">
          <CharacterAvatar shape="blob" color="gray" size={620} mood="idle" ink={INK} />
        </div>
      </div>
    </section>
  );
}
