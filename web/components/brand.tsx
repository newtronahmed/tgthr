export function BrandMark({ size = 36 }: { size?: number }) {
  return (
    <span
      aria-hidden
      className="inline-flex items-center justify-center rounded-2xl bg-forest-600 font-black text-white shadow-[0_8px_18px_-6px_rgba(0,104,71,0.55)]"
      style={{ width: size, height: size, fontSize: size * 0.48 }}
    >
      t
    </span>
  );
}

export function Wordmark({ light = false }: { light?: boolean }) {
  return (
    <span className={`font-extrabold tracking-tight ${light ? "text-white" : "text-slate-900"}`}>
      tgthr
    </span>
  );
}

export function BrandHeader() {
  return (
    <header className="flex items-center gap-2.5">
      <BrandMark />
      <div className="leading-tight">
        <Wordmark />
        <div className="text-[10px] font-bold uppercase tracking-[0.16em] text-forest-700">
          group money, simplified
        </div>
      </div>
    </header>
  );
}
