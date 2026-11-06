import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "tgthr — group money, simplified",
    short_name: "tgthr",
    description: "Collect contributions together, without the group-chat chaos.",
    start_url: "/",
    display: "standalone",
    background_color: "#eaf2ed",
    theme_color: "#006847",
    icons: [
      {
        src: "/icon.svg",
        sizes: "any",
        type: "image/svg+xml",
        purpose: "any",
      },
    ],
  };
}
