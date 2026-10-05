import Image from "next/image";
import { AppleLogo, DeviceMobile, LinuxLogo } from "@phosphor-icons/react/ssr";
import CopyCommand from "./copy-command";
import Eyebrow from "./eyebrow";
import Halftone from "./halftone";
import Reveal from "./reveal";
import { APP_STORE, DMG } from "../links";

const INSTALL_SH = "curl -fsSL https://raw.githubusercontent.com/leepokai/Codync/main/packaging/install.sh | sh";

export default function Install() {
  return (
    <section id="install" className="relative overflow-hidden px-4 py-20 sm:px-6 md:py-28">
      <Halftone className="w-[36rem] max-w-[80vw] text-neutral-50 opacity-[0.12]" />
      <div className="relative mx-auto max-w-3xl">
        <Reveal>
          <Eyebrow>Install</Eyebrow>
          <h2 className="text-3xl font-semibold tracking-tight text-neutral-50 md:text-4xl">Install once.</h2>
          <p className="mt-4 max-w-[36rem] text-lg leading-relaxed text-neutral-400">
            Put Codync on the computer your agents run on, then pair your phone from it.
          </p>
        </Reveal>

        <div className="mt-12 space-y-10">
          <Reveal>
            <div className="flex items-center gap-3 text-neutral-50">
              <AppleLogo size={22} weight="fill" />
              <h3 className="text-lg font-semibold">Mac</h3>
            </div>
            <p className="mt-2 text-neutral-400">
              Homebrew, or the{" "}
              <a href={DMG} className="text-neutral-200 underline underline-offset-4 hover:text-white">
                signed download
              </a>
              . Open Codync and it starts the host itself.
            </p>
            <div className="mt-4">
              <CopyCommand command="brew install --cask leepokai/codync/codync" />
            </div>
          </Reveal>

          <Reveal>
            <div className="flex items-center gap-3 text-neutral-50">
              <LinuxLogo size={22} weight="fill" />
              <h3 className="text-lg font-semibold">Linux and servers</h3>
            </div>
            <p className="mt-2 text-neutral-400">
              The host, plus the desktop app when there is a display. Then run <code className="font-mono text-neutral-200">codync-host install</code>.
            </p>
            <div className="mt-4">
              <CopyCommand command={INSTALL_SH} />
            </div>
          </Reveal>

          <Reveal>
            <div className="flex items-center gap-3 text-neutral-50">
              <DeviceMobile size={22} weight="fill" />
              <h3 className="text-lg font-semibold">iPhone</h3>
            </div>
            <p className="mt-2 text-neutral-400">
              Get the app, then scan the code from <span className="text-neutral-200">Pair iPhone…</span> in the Mac
              menu bar, the Linux app or <code className="font-mono text-neutral-200">codync-host pair</code>.
            </p>
            <a
              href={APP_STORE}
              target="_blank"
              rel="noopener noreferrer"
              className="mt-4 inline-flex transition hover:opacity-80 active:scale-[0.98]"
            >
              <Image src="/app-store-badge.svg" alt="Download on the App Store" width={120} height={40} className="h-10 w-auto" />
            </a>
          </Reveal>
        </div>
      </div>
    </section>
  );
}
