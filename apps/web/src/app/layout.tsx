import type { Metadata } from "next";
import { Inter, Newsreader } from "next/font/google";
import "./globals.css";

// Headings: the width of "Welcome back.", "Start your review." and "Profile" in the mockups matches Newsreader 400
// to within 2 px at their sizes (26, 26 and 32 px); the other serifs tried were 10 to 20 % wider.
const display = Newsreader({
  variable: "--font-display",
  subsets: ["latin"],
  axes: ["opsz"],
});

const body = Inter({
  variable: "--font-body",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: { default: "Helm", template: "%s · Helm" },
  description: "Leadership intelligence",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${display.variable} ${body.variable} antialiased`}>
      <body className="min-h-dvh">{children}</body>
    </html>
  );
}
