// src/types/bot-flags.ts
// WATCH (D46): HIGH đơn lẻ không kèm luật tay — chưa chặn, chờ review hoặc lần HIGH thứ hai
export type BotFlagStatus = "WATCH" | "ACTIVE" | "CLEARED";

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
