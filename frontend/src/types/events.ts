export type EventStatus = "PUBLISHED" | "CANCELLED";
export type TicketCategory = "SEATED" | "GENERAL_ADMISSION";

export interface EventVenue {
  id: string;
  name: string;
  address?: string;
  city: string;
}

export interface EventSummary {
  id: string;
  name: string;
  startAt: string;
  endAt: string;
  status: EventStatus;
  bannerImageUrl: string | null;
  minPrice: number | null;
  venue: EventVenue;
}

export interface TicketType {
  id: string;
  category: TicketCategory;
  name: string;
  price: number;
  totalQuantity: number;
}

export interface EventDetail extends EventSummary {
  description: string;
  salesStartAt: string | null;
  salesEndAt: string | null;
  venue: EventVenue;
  ticketTypes: TicketType[];
  hasSeatMap: boolean;
}

export interface Seat {
  id: string;
  section: string;
  rowLabel: string;
  seatNumber: string;
  displayLabel: string;
  ticketType: { id: string; name: string; price: number } | null;
}

export interface SeatMap {
  seatMapId: string;
  seats: Seat[];
}
