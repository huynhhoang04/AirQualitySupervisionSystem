import { useEffect, useState } from "react";

/** Keep freshness labels moving even when a request fails and cached data stays visible. */
export function useNow() {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 15_000);
    return () => window.clearInterval(timer);
  }, []);
  return now;
}
