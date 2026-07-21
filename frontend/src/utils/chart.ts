/** Compact axis labels: 1.2M / 45k / 320. */
export function compact(value: number): string {
  if (Math.abs(value) >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (Math.abs(value) >= 1_000) return `${Math.round(value / 1_000)}k`;
  return String(Math.round(value));
}

/** Nice tick step: 1/2/5 × power of ten covering the raw step. */
export function niceStep(raw: number): number {
  const power = Math.pow(10, Math.floor(Math.log10(Math.max(raw, 1))));
  const candidates = [1, 2, 5, 10].map((m) => m * power);
  return candidates.find((c) => c >= raw) ?? candidates[3];
}
