import Image from "next/image";
import Link from "next/link";
import { GithubLogo } from "@phosphor-icons/react/ssr";
import Hero from "./components/hero";
import Film from "./components/film";
import { Answers, Features } from "./components/features";
import Compare from "./components/compare";
import Install from "./components/install";
import Privacy from "./components/privacy";
import Mac from "./components/mac";
import Surfaces from "./components/surfaces";
import { DMG, GITHUB } from "./links";

export default function Home() {
  return (
    <>
      <header className="sticky top-0 z-20 bg-black/80 backdrop-blur-md">
        <nav className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4 sm:px-6">
          <Link href="/" className="flex items-center gap-2.5 font-semibold text-neutral-50">
            <Image src="/icon.png" alt="" width={28} height={28} className="rounded-[7px]" />
            Codync
          </Link>
          <div className="flex items-center gap-2">
            <a href="#install" className="hidden rounded-full px-4 py-2 text-sm text-neutral-400 transition hover:text-neutral-100 sm:block">
              Install
            </a>
            <a
              href={GITHUB}
              aria-label="Codync on GitHub"
              title="GitHub"
              className="rounded-full p-2 text-neutral-400 transition hover:text-neutral-100"
            >
              <GithubLogo size={20} />
            </a>
            <a
              href={DMG}
              className="rounded-full bg-neutral-50 px-4 py-2 text-sm font-medium text-neutral-950 transition hover:bg-white active:scale-[0.98]"
            >
              Download for Mac
            </a>
          </div>
        </nav>
      </header>

      <main className="flex-1">
        <Hero />
        <Film />
        <Compare />
        <Answers />
        <Features />
        <Mac />
        <Surfaces />
        <Privacy />
        <Install />
      </main>

      <footer className="px-4 py-12 sm:px-6">
        <div className="mx-auto flex max-w-6xl flex-col gap-6 text-sm text-neutral-500 sm:flex-row sm:items-center sm:justify-between">
          <p>
            100% free and open source. No hidden fees.{" "}
            <a href={GITHUB} className="text-neutral-300 underline underline-offset-4 hover:text-white">
              Code on GitHub
            </a>
          </p>
          <div className="flex gap-6">
            <Link href="/terms" className="transition hover:text-neutral-300">
              Terms
            </Link>
            <Link href="/privacy" className="transition hover:text-neutral-300">
              Privacy
            </Link>
            <a href={`${GITHUB}/issues`} className="transition hover:text-neutral-300">
              Contact
            </a>
          </div>
        </div>
      </footer>
    </>
  );
}
