import Image from "next/image";
import Eyebrow from "./eyebrow";
import Reveal from "./reveal";

// Real window and menu captures from the Mac app; both PNGs keep their rounded, transparent corners.
export default function Mac() {
  return (
    <section className="px-4 py-20 sm:px-6 md:py-28">
      <div className="mx-auto max-w-6xl">
        <Reveal>
          <Eyebrow>Desktop</Eyebrow>
          <h2 className="max-w-[36rem] text-3xl font-semibold tracking-tight text-neutral-50 md:text-4xl">
            And at your desk, on your Mac.
          </h2>
          <p className="mt-5 max-w-[34rem] text-lg leading-relaxed text-neutral-400">
            The same bots and rooms in a native window. The menu bar shows who needs you and how much of your
            Claude and Codex limits are left.
          </p>
        </Reveal>

        <div className="relative mt-12 md:pr-28">
          <Reveal>
            <Image
              src="/screens/mac-chat.webp"
              alt="The Codync Mac window: Ship room, where Pacer, Reviewer and Scout discuss an isoWeek fix, with the member list on the right"
              width={1600}
              height={1096}
              className="h-auto w-full drop-shadow-[0_40px_60px_rgba(0,0,0,0.8)]"
            />
          </Reveal>
          <Reveal
            delay={0.1}
            className="mx-auto mt-8 w-full max-w-[17.5rem] md:absolute md:-right-2 md:-bottom-12 md:mt-0 md:w-[30%] md:max-w-none"
          >
            <Image
              src="/screens/mac-menu.webp"
              alt="The Codync menu bar menu: three bots, Remote screen, and Claude and Codex usage bars"
              width={700}
              height={876}
              className="h-auto w-full drop-shadow-[0_30px_50px_rgba(0,0,0,0.85)]"
            />
          </Reveal>
        </div>
      </div>
    </section>
  );
}
