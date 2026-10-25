export type AdminEventStatus = "DRAFT" | "PUBLISHED" | "CANCELLED";

export interface AdminEventMock {
  id: string;
  name: string;
  venueName: string;
  venueArea: string;
  startAt: string;
  ticketTypeCount: number;
  status: AdminEventStatus;
  salesWindow: string;
}

// UXMagic includes an admin list for every status, but the backend has no admin listing API yet.
// These fixtures are only for display and filtering; actions never call an API with fake IDs.
export const adminEventMocks: AdminEventMock[] = [
  { id: "mock-event-01", name: "Midnight in the Mission: Live Jazz", venueName: "The Chapel", venueArea: "Mission District", startAt: "2026-11-15T19:00:00-08:00", ticketTypeCount: 2, status: "PUBLISHED", salesWindow: "Nov 1 – Nov 14" },
  { id: "mock-event-02", name: "Design After Dark: Making Better Things", venueName: "Figma HQ Loft", venueArea: "SoMa", startAt: "2026-11-20T18:30:00-08:00", ticketTypeCount: 1, status: "DRAFT", salesWindow: "Nov 10 – Nov 19" },
  { id: "mock-event-03", name: "SF Symphony: Autumn Gala Concert", venueName: "Davies Symphony Hall", venueArea: "Civic Center", startAt: "2026-11-22T20:00:00-08:00", ticketTypeCount: 3, status: "PUBLISHED", salesWindow: "Oct 15 – Nov 21" },
  { id: "mock-event-04", name: "Warriors vs Lakers: West Coast Classic", venueName: "Chase Center", venueArea: "Mission Bay", startAt: "2026-12-02T19:30:00-08:00", ticketTypeCount: 3, status: "PUBLISHED", salesWindow: "Nov 1 – Dec 1" },
  { id: "mock-event-05", name: "Mission District Street Food Fest", venueName: "The Chapel Outdoor", venueArea: "Mission District", startAt: "2026-11-16T12:00:00-08:00", ticketTypeCount: 1, status: "CANCELLED", salesWindow: "Oct 1 – Nov 15" },
  { id: "mock-event-06", name: "Indie Film Night", venueName: "Figma HQ Loft", venueArea: "SoMa", startAt: "2026-12-05T18:00:00-08:00", ticketTypeCount: 2, status: "PUBLISHED", salesWindow: "Nov 12 – Dec 4" },
  { id: "mock-event-07", name: "Founders & Makers Meetup", venueName: "The Chapel", venueArea: "Mission District", startAt: "2026-12-09T18:30:00-08:00", ticketTypeCount: 0, status: "DRAFT", salesWindow: "Not scheduled" },
  { id: "mock-event-08", name: "Winter Chamber Sessions", venueName: "Davies Symphony Hall", venueArea: "Civic Center", startAt: "2026-12-12T19:00:00-08:00", ticketTypeCount: 2, status: "PUBLISHED", salesWindow: "Nov 20 – Dec 11" },
  { id: "mock-event-09", name: "Bay Lights Community Run", venueName: "Chase Center", venueArea: "Mission Bay", startAt: "2026-12-14T08:00:00-08:00", ticketTypeCount: 1, status: "PUBLISHED", salesWindow: "Nov 1 – Dec 13" },
  { id: "mock-event-10", name: "Product Design Workshop", venueName: "Figma HQ Loft", venueArea: "SoMa", startAt: "2026-12-17T09:00:00-08:00", ticketTypeCount: 0, status: "DRAFT", salesWindow: "Not scheduled" },
  { id: "mock-event-11", name: "New Year Jazz Brunch", venueName: "The Chapel", venueArea: "Mission District", startAt: "2026-12-27T11:00:00-08:00", ticketTypeCount: 2, status: "PUBLISHED", salesWindow: "Dec 1 – Dec 26" },
  { id: "mock-event-12", name: "New Year Symphony Gala", venueName: "Davies Symphony Hall", venueArea: "Civic Center", startAt: "2026-12-31T20:00:00-08:00", ticketTypeCount: 3, status: "PUBLISHED", salesWindow: "Dec 1 – Dec 30" },
];
