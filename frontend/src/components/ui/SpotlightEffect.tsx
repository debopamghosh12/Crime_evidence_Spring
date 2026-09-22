"use client";

import { useEffect, useState } from "react";
import { motion, useSpring } from "framer-motion";

export default function SpotlightEffect() {
  const [coords, setCoords] = useState({ x: 0, y: 0 });

  // Use springs for smooth movement
  const springX = useSpring(0, { stiffness: 150, damping: 30 });
  const springY = useSpring(0, { stiffness: 150, damping: 30 });

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      springX.set(e.clientX - 200); // Center the 400px circle
      springY.set(e.clientY - 200);
    };

    window.addEventListener("mousemove", handleMouseMove);
    return () => window.removeEventListener("mousemove", handleMouseMove);
  }, [springX, springY]);

  return (
    <motion.div
      className="fixed top-0 left-0 w-[400px] h-[400px] bg-primary/10 rounded-full blur-[100px] pointer-events-none z-10 mix-blend-screen"
      style={{
        x: springX,
        y: springY,
      }}
    />
  );
}
