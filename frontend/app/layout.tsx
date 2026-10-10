import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "DiscoSticker.cafe",
  description: "Turn images into sticker-sized links for Discord.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
