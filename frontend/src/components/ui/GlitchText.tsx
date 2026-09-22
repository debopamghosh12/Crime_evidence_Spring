"use client";

import { cn } from "@/lib/utils";
import { useEffect, useState } from "react";

export default function GlitchText({ text, className }: { text: string; className?: string }) {
  const [glitching, setGlitching] = useState(false);

  useEffect(() => {
    const interval = setInterval(() => {
      if (Math.random() > 0.8) {
        setGlitching(true);
        setTimeout(() => setGlitching(false), 200);
      }
    }, 2000);
    return () => clearInterval(interval);
  }, []);

  return (
    <div className={cn("relative inline-block", className)}>
      <span className="relative z-10">{text}</span>
      {glitching && (
        <>
          <span className="absolute top-0 left-0 -ml-0.5 translate-x-[2px] text-red-500 opacity-70 animate-pulse">{text}</span>
          <span className="absolute top-0 left-0 -ml-0.5 -translate-x-[2px] text-blue-500 opacity-70 animate-pulse">{text}</span>
        </>
      )}
    </div>
  );
}
