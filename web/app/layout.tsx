import type { Metadata, Viewport } from "next";
import { JetBrains_Mono, Plus_Jakarta_Sans } from "next/font/google";
import { ServiceWorkerRegistration } from "@/components/service-worker";
import "./globals.css";

const jakarta = Plus_Jakarta_Sans({
  variable: "--font-jakarta",
  subsets: ["latin"],
  weight: ["400", "500", "600", "700", "800"],
});

const jetbrains = JetBrains_Mono({
  variable: "--font-jetbrains",
  subsets: ["latin"],
  weight: ["400", "500", "600"],
});

export const metadata: Metadata = {
  metadataBase: new URL(process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000"),
  title: {
    default: "tgthr — group money, simplified",
    template: "%s · tgthr",
  },
  description: "Collect contributions together, without the group-chat chaos.",
  applicationName: "tgthr",
  openGraph: {
    type: "website",
    siteName: "tgthr",
    title: "tgthr — group money, simplified",
    description: "Collect contributions together, without the group-chat chaos.",
  },
};

export const viewport: Viewport = {
  themeColor: "#006847",
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${jakarta.variable} ${jetbrains.variable}`}>
      <body className="min-h-dvh antialiased">
        <ServiceWorkerRegistration />
        {children}
      </body>
    </html>
  );
}
