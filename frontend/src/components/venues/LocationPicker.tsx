"use client";

import "leaflet/dist/leaflet.css";
import { useEffect, useRef, useState } from "react";
import type { CircleMarker, Map as LeafletMap } from "leaflet";
import { LoaderCircle, MapPin, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

// D45: tìm địa chỉ thật bằng Nominatim (OpenStreetMap). Theo chính sách sử dụng của Nominatim: chỉ tìm khi
// người dùng bấm (không autocomplete), tối đa vài kết quả, có ghi công OSM trên bản đồ.
const NOMINATIM_URL = "https://nominatim.openstreetmap.org/search";
const TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
const DEFAULT_CENTER: [number, number] = [10.7769, 106.7009]; // TP.HCM

export interface PickedLocation {
  address: string;
  city: string;
  latitude: number;
  longitude: number;
  label: string;
}

interface NominatimResult {
  place_id: number;
  lat: string;
  lon: string;
  display_name: string;
  name?: string;
  address?: Record<string, string>;
}

function toPicked(result: NominatimResult): PickedLocation {
  const a = result.address ?? {};
  const street = [a.house_number, a.road ?? a.pedestrian ?? a.footway].filter(Boolean).join(" ");
  const area = a.suburb ?? a.quarter ?? a.neighbourhood ?? a.city_district;
  const address = [result.name && result.name !== street ? result.name : null, street || null, area ?? null]
    .filter(Boolean)
    .join(", ");
  return {
    address: (address || result.display_name.split(",").slice(0, 2).join(",")).slice(0, 255),
    city: (a.city ?? a.town ?? a.village ?? a.municipality ?? a.state ?? a.country ?? "").slice(0, 100),
    latitude: Number(result.lat),
    longitude: Number(result.lon),
    label: result.display_name,
  };
}

export function LocationPicker({
  value,
  onChange,
}: {
  value: PickedLocation | null;
  onChange: (location: PickedLocation) => void;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<LeafletMap | null>(null);
  const markerRef = useRef<CircleMarker | null>(null);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<NominatimResult[]>([]);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Leaflet đụng tới window: chỉ nạp ở client, sau khi dialog đã render khung bản đồ
  useEffect(() => {
    let cancelled = false;
    let map: LeafletMap | null = null;
    void import("leaflet").then((L) => {
      if (cancelled || !containerRef.current) return;
      map = L.map(containerRef.current, { zoomControl: true }).setView(DEFAULT_CENTER, 12);
      L.tileLayer(TILE_URL, {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      }).addTo(map);
      mapRef.current = map;
      // Dialog còn đang mở animation: đo lại kích thước để tile không bị lệch
      window.setTimeout(() => map?.invalidateSize(), 200);
    });
    return () => {
      cancelled = true;
      map?.remove();
      mapRef.current = null;
      markerRef.current = null;
    };
  }, []);

  // Đồng bộ chấm đỏ với vị trí đã chọn
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !value) return;
    void import("leaflet").then((L) => {
      const position: [number, number] = [value.latitude, value.longitude];
      if (markerRef.current) {
        markerRef.current.setLatLng(position);
      } else {
        markerRef.current = L.circleMarker(position, {
          radius: 9, color: "#dc2626", fillColor: "#ef4444", fillOpacity: 0.9, weight: 2,
        }).addTo(map);
      }
      map.setView(position, Math.max(map.getZoom(), 16));
    });
  }, [value]);

  async function search() {
    const text = query.trim();
    if (text.length < 3) {
      setError("Type at least 3 characters of the address.");
      return;
    }
    setSearching(true);
    setError(null);
    try {
      const params = new URLSearchParams({ q: text, format: "jsonv2", addressdetails: "1", limit: "5" });
      const response = await fetch(`${NOMINATIM_URL}?${params}`, { headers: { "Accept-Language": "en" } });
      if (!response.ok) throw new Error(String(response.status));
      const found = (await response.json()) as NominatimResult[];
      setResults(found);
      if (found.length === 0) setError("No matching address found. Try a more specific address.");
    } catch {
      setError("Address search is unavailable right now. Try again in a moment.");
    } finally {
      setSearching(false);
    }
  }

  return (
    <div className="space-y-3">
      <div className="flex gap-2">
        <label htmlFor="venue-location-search" className="sr-only">Search address</label>
        <Input
          id="venue-location-search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              event.preventDefault();
              void search();
            }
          }}
          placeholder="Search the real address…"
          autoComplete="off"
        />
        <Button type="button" variant="outline" onClick={() => void search()} disabled={searching}>
          {searching ? <LoaderCircle aria-hidden="true" className="animate-spin" /> : <Search aria-hidden="true" />}
          Search
        </Button>
      </div>
      {error ? <p role="alert" className="text-sm text-destructive">{error}</p> : null}
      {results.length > 0 ? (
        <ul className="max-h-40 divide-y divide-border overflow-y-auto rounded-lg border border-border text-sm" aria-label="Address results">
          {results.map((result) => (
            <li key={result.place_id}>
              <button
                type="button"
                onClick={() => {
                  onChange(toPicked(result));
                  setResults([]);
                }}
                className="flex w-full items-start gap-2 px-3 py-2 text-left hover:bg-muted focus-visible:bg-muted focus-visible:outline-none"
              >
                <MapPin aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-primary" />
                <span>{result.display_name}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      <div ref={containerRef} className="h-56 w-full overflow-hidden rounded-lg border border-border" aria-label="Map" />
      {value ? (
        <p className="text-xs text-muted-foreground">
          Selected: {value.label} ({value.latitude.toFixed(5)}, {value.longitude.toFixed(5)})
        </p>
      ) : (
        <p className="text-xs text-muted-foreground">Search and pick a result to confirm the venue exists.</p>
      )}
    </div>
  );
}
