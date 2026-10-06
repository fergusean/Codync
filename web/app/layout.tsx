import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import JsonLd from "./json-ld";
import { APP_STORE } from "./links";
import { DESCRIPTION, SITE } from "./site";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Codync: the free, open-source Grok Bot, Muse and Dots alternative for any coding agent",
  description: DESCRIPTION,
  applicationName: "Codync",
  keywords: [
    "Grok Bot alternative",
    "Muse alternative",
    "Dots alternative",
    "Claude Code on iPhone",
    "Codex on iPhone",
    "coding agent remote",
    "Agent Client Protocol",
    "ACP",
    "open source",
    "free",
  ],
  metadataBase: new URL(SITE),
  openGraph: { type: "website", siteName: "Codync", locale: "en_US" },
  twitter: { card: "summary_large_image" },
  appLinks: { ios: { url: APP_STORE, app_store_id: "6760984418" } },
  itunes: { appId: "6760984418" },
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
        <JsonLd />
        {children}
      </body>
    </html>
  );
}
