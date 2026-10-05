import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Codync: the open-source Grok Bot / Muse alternative for any coding agent",
  description:
    "A free, open-source 1:1 alternative to Grok Bot and Muse. Message Claude Code, Codex, Cursor and 40+ coding agents as persistent bots from your iPhone, Mac or Linux desktop. Group chats, threads, approvals, memory, remote screen and voice, on your own computer. Native on iPhone, Mac and Linux, with a terminal UI over SSH.",
  metadataBase: new URL("https://www.codync.dev"),
  icons: {
    icon: [
      { url: "/icon.svg", type: "image/svg+xml" },
      { url: "/icon.png", sizes: "512x512", type: "image/png" },
    ],
    apple: "/apple-touch-icon.png",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased dark`}
    >
      <body className="min-h-full flex flex-col bg-black text-neutral-200">
        {children}
      </body>
    </html>
  );
}
