"use client";

import { useRef } from "react";

export function OtpInput({
  value,
  onChange,
  label = "Verification code",
}: {
  value: string;
  onChange: (value: string) => void;
  label?: string;
}) {
  const refs = useRef<Array<HTMLInputElement | null>>([]);
  const digits = Array.from({ length: 6 }, (_, index) => value[index] ?? "");
  const updateDigit = (index: number, digit: string) => {
    const next = value.split("");
    if (digit) next[index] = digit;
    else next.splice(index, 1);
    onChange(next.join("").slice(0, 6));
  };
  return (
    <fieldset>
      <legend className="mb-3 text-sm font-semibold">{label}</legend>
      <div className="flex justify-between gap-2">
        {digits.map((digit, index) => (
          <input
            key={index}
            ref={(node) => {
              refs.current[index] = node;
            }}
            type="text"
            inputMode="numeric"
            pattern="[0-9]*"
            maxLength={1}
            autoComplete={index === 0 ? "one-time-code" : "off"}
            aria-label={`${label}, digit ${index + 1} of 6`}
            value={digit}
            onChange={(event) => {
              const entered = event.target.value.replace(/\D/g, "").slice(-1);
              updateDigit(index, entered);
              if (entered && index < 5) refs.current[index + 1]?.focus();
            }}
            onKeyDown={(event) => {
              if (event.key === "Backspace" && !digit && index > 0)
                refs.current[index - 1]?.focus();
              if (event.key === "ArrowLeft" && index > 0)
                refs.current[index - 1]?.focus();
              if (event.key === "ArrowRight" && index < 5)
                refs.current[index + 1]?.focus();
            }}
            onPaste={(event) => {
              const pasted = event.clipboardData
                .getData("text")
                .replace(/\D/g, "")
                .slice(0, 6);
              if (!pasted) return;
              event.preventDefault();
              onChange(pasted);
              refs.current[Math.min(pasted.length, 5)]?.focus();
            }}
            className="size-11 rounded-lg border border-input bg-background text-center text-lg font-bold outline-none focus:border-primary focus:ring-2 focus:ring-ring/40 sm:size-12"
          />
        ))}
      </div>
    </fieldset>
  );
}
