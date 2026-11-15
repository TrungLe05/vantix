// src/types/bot-flags.ts
export type BotFlagStatus = "ACTIVE" | "CLEARED";

export interface BotFlag {
  id: string;
  buyerId: string;
  deviceHash: string | null;
  orderId: string | null;
  eventId: string | null;
  score: number;
  modelVersion: string;
  features: Record<string, number | null>;
  status: BotFlagStatus;
  hitCount: number;
  firstFlaggedAt: string;
  lastFlaggedAt: string;
  clearedAt: string | null;
  clearedBy: string | null;
}
