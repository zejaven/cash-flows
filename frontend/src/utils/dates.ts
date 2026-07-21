/** Local "today" as yyyy-MM-dd — dates always come from the client, never the server. */
export function today(): string {
  const now = new Date();
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

/** "Tue 21 Jul" / "Today · Tue 21 Jul" for day-group headers. */
export function formatDayHeader(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  const date = new Date(y, m - 1, d);
  const label = date.toLocaleDateString('en-NZ', { weekday: 'short', day: 'numeric', month: 'short' });
  return isoDate === today() ? `Today · ${label}` : label;
}

export function formatDate(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-NZ', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}
