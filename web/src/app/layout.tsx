import type { Metadata } from "next";
import { EmailLinkForwarder } from "@/components/EmailLinkForwarder";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "HelmetGuard", template: "%s · HelmetGuard" },
  description: "Review AI helmet alerts captured by the HelmetGuard Android app.",
  robots: { index: false, follow: false },
};

// Runs before first paint so a saved dark theme never flashes light.
const themeScript = `try{var t=localStorage.getItem('theme');if(t==='dark'||(!t&&matchMedia('(prefers-color-scheme: dark)').matches))document.documentElement.classList.add('dark')}catch(e){}`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
      </head>
      <body className="min-h-dvh">
        <EmailLinkForwarder />
        {children}
      </body>
    </html>
  );
}
