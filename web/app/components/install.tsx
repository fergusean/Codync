import { AppleLogo, ArrowUpRight, DeviceMobile, DownloadSimple, LinuxLogo } from "@phosphor-icons/react/ssr";
import CopyCommand from "./copy-command";
import Reveal from "./reveal";
import { APP_STORE, DMG } from "../links";

const INSTALL_SH = "curl -fsSL https://raw.githubusercontent.com/leepokai/Codync/main/packaging/install.sh | sh";

const row = "flex items-center gap-4 rounded-2xl bg-neutral-900 px-5 py-4 transition hover:bg-neutral-800";

// Download, one row per platform; the computer first, then the phone.
export default function Install() {
  return (
    <section id="download" className="scroll-mt-20 px-4 py-20 sm:px-6 md:py-28">
      <div className="mx-auto grid max-w-5xl grid-cols-1 gap-10 md:grid-cols-[1fr_1.2fr] md:gap-16">
        <Reveal>
          <h2 className="text-3xl font-semibold tracking-tight text-neutral-50 md:text-5xl">Download Codync</h2>
          <p className="mt-5 max-w-[24rem] text-lg leading-relaxed text-neutral-400">
            Install it on the computer your agents run on, then pair your phone from it. One team, on your desk and in
            your pocket.
          </p>
        </Reveal>
        <Reveal delay={0.05} className="space-y-3">
          <a href={DMG} className={row}>
            <AppleLogo size={22} weight="fill" className="text-neutral-50" />
            <span className="flex-1">
              <span className="block font-medium text-neutral-50">macOS</span>
              <span className="block text-sm text-neutral-500">Apple silicon and Intel. Or brew install --cask leepokai/codync/codync</span>
            </span>
            <DownloadSimple size={18} className="text-neutral-400" />
          </a>
          <a href={APP_STORE} target="_blank" rel="noopener noreferrer" className={row}>
            <DeviceMobile size={22} weight="fill" className="text-neutral-50" />
            <span className="flex-1">
              <span className="block font-medium text-neutral-50">iPhone</span>
              <span className="block text-sm text-neutral-500">App Store. Scan the pairing code from your computer.</span>
            </span>
            <ArrowUpRight size={18} className="text-neutral-400" />
          </a>
          <div className="rounded-2xl bg-neutral-900 px-5 py-4">
            <div className="flex items-center gap-4">
              <LinuxLogo size={22} weight="fill" className="text-neutral-50" />
              <span>
                <span className="block font-medium text-neutral-50">Linux and servers</span>
                <span className="block text-sm text-neutral-500">
                  The host, plus the desktop app when there is a display. Then run codync-host install.
                </span>
              </span>
            </div>
            <div className="mt-4">
              <CopyCommand command={INSTALL_SH} />
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}
