"use client";

import { DotLottieReact } from '@lottiefiles/dotlottie-react';

interface LottieLoaderProps {
  size?: number;
  className?: string;
}

export default function LottieLoader({ size = 120, className = "" }: LottieLoaderProps) {
  return (
    <div
      className={`flex items-center justify-center ${className}`}
      style={{ width: size, height: size }}
    >
      <DotLottieReact
        src="/loading.lottie"
        loop
        autoplay
        style={{ width: '100%', height: '100%' }}
      />
    </div>
  );
}
