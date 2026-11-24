import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  poweredByHeader: false,
  experimental: {
    // An alert from the video page carries its photos (the whole frame, the bike and a thumbnail),
    // which can pass the 1 MB default on a large frame. The evidence bucket takes 5 MB a photo.
    serverActions: { bodySizeLimit: "4mb" },
  },
  async headers() {
    return [
      {
        source: "/(.*)",
        headers: [
          { key: "X-Frame-Options", value: "DENY" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "same-origin" },
        ],
      },
      // Cross-origin isolation lets the in-browser detector use several CPU threads.
      // Only on the video page and its runtime files: elsewhere it would block images from Supabase.
      ...["/detect", "/ort/:path*"].map((source) => ({
        source,
        headers: [
          { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
          { key: "Cross-Origin-Embedder-Policy", value: "require-corp" },
          { key: "Cross-Origin-Resource-Policy", value: "same-origin" },
        ],
      })),
    ];
  },
};

export default nextConfig;
