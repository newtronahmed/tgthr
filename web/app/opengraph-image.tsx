import { ImageResponse } from "next/og";

export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

export default function OpengraphImage() {
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          padding: "80px",
          background: "#EAF2ED",
          color: "#0E291C",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: "24px" }}>
          <div
            style={{
              width: "96px",
              height: "96px",
              borderRadius: "28px",
              background: "#006847",
              color: "white",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontSize: "56px",
              fontWeight: 800,
            }}
          >
            t
          </div>
          <div style={{ fontSize: "64px", fontWeight: 800, letterSpacing: "-2px" }}>tgthr</div>
        </div>
        <div style={{ marginTop: "32px", fontSize: "42px", fontWeight: 700 }}>
          Group money, without the chaos.
        </div>
        <div style={{ marginTop: "16px", fontSize: "28px", color: "#2E5B43" }}>
          Contribute by mobile money from a shared link.
        </div>
      </div>
    ),
    size,
  );
}
