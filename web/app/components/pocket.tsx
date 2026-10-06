import Reveal from "./reveal";
import LivePhones from "./live-phone/live-phones";

// The live iPhones (a recreation of the app you can tap), with what the phone adds.
export default function Pocket() {
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
