import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "DiscoSticker.cafe",
  description: "Turn images into sticker-sized links for Discord.",
  icons: { icon: [{ url: "/discosticker-logo.svg", type: "image/svg+xml", sizes: "any" }] },
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en">
      <body>
        <header className="site-header">
          <img className="site-header-logo" src="/discosticker-logo.svg" alt="" />
          <span className="site-header-name">DiscoSticker.cafe</span>
        </header>
        {children}
      </body>
    </html>
  );
}
