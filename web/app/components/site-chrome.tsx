import Image from "next/image";
import Link from "next/link";
import { GithubLogo } from "@phosphor-icons/react/ssr";
import DownloadButton from "./download-button";
import { APP_STORE, DMG, GITHUB } from "../links";

const nav: [href: string, label: string][] = [
  ["/#product", "Product"],
  ["/compare", "Compare"],
  ["/#pricing", "Pricing"],
  ["/#faq", "FAQ"],
];

// The header and footer every page shares.
export function SiteHeader() {
  return (
    <header className="sticky top-0 z-30 bg-black/80 backdrop-blur-md">
      <nav className="mx-auto grid h-16 max-w-6xl grid-cols-[1fr_auto] items-center px-4 sm:px-6 md:grid-cols-[1fr_auto_1fr]">
        <Link href="/" className="flex items-center gap-2.5 font-semibold text-neutral-50">
          <Image src="/icon.png" alt="" width={28} height={28} className="rounded-[7px]" />
          Codync
        </Link>
        <div className="hidden items-center gap-1 md:flex">
          {nav.map(([href, label]) => (
            <Link key={href} href={href} className="rounded-full px-4 py-2 text-sm text-neutral-400 transition hover:text-neutral-100">
              {label}
            </Link>
          ))}
        </div>
        <div className="flex items-center justify-end gap-2">
          <a
            href={GITHUB}
            aria-label="Codync on GitHub"
            title="GitHub"
            className="rounded-full p-2 text-neutral-400 transition hover:text-neutral-100"
          >
            <GithubLogo size={20} />
          </a>
          <DownloadButton size="sm" />
        </div>
      </nav>
    </header>
  );
}

const columns: [title: string, links: [label: string, href: string][]][] = [
  ["Product", [["Download for Mac", DMG], ["iPhone app", APP_STORE], ["Linux and servers", "/#download"], ["Pricing", "/#pricing"]]],
  ["Use it with", [["Claude Code on iPhone", "/claude-code-iphone"], ["Codex on iPhone", "/codex-iphone"], ["Compare Codync", "/compare"]]],
  ["Open source", [["Code on GitHub", GITHUB], ["Releases", `${GITHUB}/releases`], ["Contact", `${GITHUB}/issues`]]],
  ["Legal", [["Terms", "/terms"], ["Privacy", "/privacy"]]],
];

export function SiteFooter() {
  return (
    <footer className="border-t border-neutral-900 px-4 py-14 sm:px-6">
      <div className="mx-auto grid max-w-6xl grid-cols-2 gap-10 md:grid-cols-[1.4fr_repeat(4,1fr)]">
        <div className="col-span-2 md:col-span-1">
          <Link href="/" className="flex items-center gap-2.5 font-semibold text-neutral-50">
            <Image src="/icon.png" alt="" width={24} height={24} className="rounded-[6px]" />
            Codync
          </Link>
          <p className="mt-4 max-w-[16rem] text-sm leading-relaxed text-neutral-500">
            100% free and open source. No hidden fees, no paid tier.
          </p>
        </div>
        {columns.map(([title, links]) => (
          <div key={title}>
            <p className="text-sm font-medium text-neutral-200">{title}</p>
            <ul className="mt-4 space-y-3 text-sm">
              {links.map(([label, href]) => (
                <li key={label}>
                  <Link href={href} className="text-neutral-500 transition hover:text-neutral-200">
                    {label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </footer>
  );
}
