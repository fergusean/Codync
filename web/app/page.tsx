import Hero from "./components/hero";
import Film from "./components/film";
import Privacy from "./components/privacy";
import Cost from "./components/cost";
import Questions from "./components/questions";
import Install from "./components/install";
import CompareCards from "./components/compare-cards";
import Reveal from "./components/reveal";
import { FinalCta, Jobs, ManyBots, Pocket, Teammates } from "./components/story";
import { SiteFooter, SiteHeader } from "./components/site-chrome";

export default function Home() {
  return (
    <>
      <SiteHeader />
      <main className="flex-1">
        <Hero />
        <Teammates />
        <ManyBots />
        <Jobs />
        <Pocket />
        <Film />
        <Privacy />
        <Cost />
        <Install />
        <section className="px-4 py-20 sm:px-6 md:py-28">
          <Reveal className="mx-auto max-w-6xl">
            <h2 className="text-3xl font-semibold tracking-tight text-neutral-50 md:text-5xl">Compare Codync</h2>
            <p className="mt-5 max-w-[36rem] text-lg leading-relaxed text-neutral-400">
              Price, where your agents run, which agents you can use and what you can do from your phone.
            </p>
            <div className="mt-10">
              <CompareCards />
            </div>
          </Reveal>
        </section>
        <Questions />
        <FinalCta />
      </main>
      <SiteFooter />
    </>
  );
}
