"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { zodResolver } from "@hookform/resolvers/zod";
import { EllipsisVertical, LoaderCircle, MapPin, Plus, Search } from "lucide-react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { createVenue, getVenues } from "@/lib/api/venues";
import { getErrorMessage } from "@/lib/api/client";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";

const venueSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, "Enter a venue name.")
    .max(200, "Name must be 200 characters or fewer."),
  address: z
    .string()
    .trim()
    .min(1, "Enter an address.")
    .max(255, "Address must be 255 characters or fewer."),
  city: z
    .string()
    .trim()
    .min(1, "Enter a city.")
    .max(100, "City must be 100 characters or fewer."),
});

type VenueFormValues = z.infer<typeof venueSchema>;

export default function AdminVenuesPage() {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const venuesQuery = useQuery({
    queryKey: ["admin", "venues"],
    queryFn: getVenues,
  });
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<VenueFormValues>({
    resolver: zodResolver(venueSchema),
    defaultValues: { name: "", address: "", city: "" },
  });
  const createMutation = useMutation({
    mutationFn: createVenue,
    onSuccess: async (venue) => {
      await queryClient.invalidateQueries({ queryKey: ["admin", "venues"] });
      setDialogOpen(false);
      reset();
      setSuccessMessage(`Venue “${venue.name}” created.`);
    },
  });

  const visibleVenues = useMemo(() => {
    const query = search.trim().toLocaleLowerCase("vi");
    return (venuesQuery.data ?? []).filter((venue) =>
      [venue.name, venue.address, venue.city].some((value) =>
        value.toLocaleLowerCase("vi").includes(query),
      ),
    );
  }, [search, venuesQuery.data]);

  function closeDialog(open: boolean) {
    setDialogOpen(open);
    if (!open) {
      reset();
      createMutation.reset();
    }
  }

  return (
    <div className="mx-auto w-full max-w-7xl space-y-6">
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
        <div>
          <h1 className="mt-1 font-heading text-3xl font-bold tracking-tight">
            Venues
          </h1>
          <p className="mt-2 text-sm text-muted-foreground">
            Manage event locations.
          </p>
        </div>
        <Button
          onClick={() => {
            setSuccessMessage(null);
            setDialogOpen(true);
          }}
        >
          <Plus aria-hidden="true" />
          Add venue
        </Button>
      </div>

      <p className="rounded-xl border border-border bg-background px-4 py-3 text-sm leading-6 text-muted-foreground">
        Venue listing and creation use the live API. The backend does not yet
        support editing, deletion, neighborhoods, or event counts, so those
        fields are not mocked.
      </p>

      {successMessage ? (
        <p
          role="status"
          aria-live="polite"
          className="rounded-lg border border-primary/20 bg-primary/5 px-4 py-3 text-sm font-medium text-foreground"
        >
          {successMessage}
        </p>
      ) : null}

      <section
        className="overflow-hidden rounded-2xl border border-border bg-card shadow-sm"
        aria-labelledby="venue-list-title"
      >
        <div className="flex flex-col gap-4 border-b border-border p-4 sm:flex-row sm:items-center sm:justify-between md:p-5">
          <div>
            <h2
              id="venue-list-title"
              className="font-heading text-lg font-semibold"
            >
              Venue list
            </h2>
            <p className="mt-1 text-sm text-muted-foreground">
              {venuesQuery.data
                ? `${venuesQuery.data.length} venues`
                : "Available venues"}
            </p>
          </div>
          <label className="relative block w-full sm:max-w-xs">
            <span className="sr-only">Search venues</span>
            <Search
              aria-hidden="true"
              className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
            />
            <Input
              type="search"
              name="venue-search"
              autoComplete="off"
              placeholder="Search by name or address…"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              className="h-11 pl-9"
            />
          </label>
        </div>

        {venuesQuery.isPending ? (
          <div role="status" aria-live="polite" className="space-y-3 p-5">
            <span className="sr-only">Loading venues…</span>
            {[0, 1, 2].map((row) => (
              <div key={row} className="grid grid-cols-3 gap-4">
                <div className="h-10 animate-pulse rounded-lg bg-muted" />
                <div className="h-10 animate-pulse rounded-lg bg-muted" />
                <div className="h-10 animate-pulse rounded-lg bg-muted" />
              </div>
            ))}
          </div>
        ) : venuesQuery.isError ? (
          <div className="p-8 text-center">
            <p role="alert" className="font-semibold">
              Could not load venues
            </p>
            <p className="mt-2 text-sm text-muted-foreground">
              {getErrorMessage(venuesQuery.error)}
            </p>
            <Button
              variant="outline"
              className="mt-4"
              onClick={() => venuesQuery.refetch()}
            >
              Try again
            </Button>
          </div>
        ) : visibleVenues.length === 0 ? (
          <div className="flex flex-col items-center px-5 py-14 text-center">
            <span className="flex size-12 items-center justify-center rounded-full bg-accent text-primary">
              <MapPin aria-hidden="true" className="size-5" />
            </span>
            <h3 className="mt-4 font-heading text-lg font-semibold">
              {venuesQuery.data?.length ? "No venues found" : "No venues yet"}
            </h3>
            <p className="mt-2 max-w-sm text-sm leading-6 text-muted-foreground">
              {venuesQuery.data?.length
                ? "Try another search term."
                : "Create your first venue to use when setting up an event."}
            </p>
            {!venuesQuery.data?.length ? (
              <Button className="mt-5" onClick={() => setDialogOpen(true)}>
                <Plus aria-hidden="true" />
                Create venue
              </Button>
            ) : null}
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[680px] border-collapse text-left text-sm">
              <thead className="bg-muted/60 text-xs uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th scope="col" className="px-5 py-3 font-semibold">
                    Name
                  </th>
                  <th scope="col" className="px-5 py-3 font-semibold">
                    Address
                  </th>
                  <th scope="col" className="px-5 py-3 font-semibold">
                    City
                  </th>
                  <th scope="col" className="px-5 py-3 font-semibold">
                    Neighborhood / events
                  </th>
                  <th
                    scope="col"
                    className="px-5 py-3 text-right font-semibold"
                  >
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {visibleVenues.map((venue) => (
                  <tr key={venue.id} className="align-middle hover:bg-muted/30">
                    <th
                      scope="row"
                      className="max-w-64 px-5 py-4 font-semibold"
                    >
                      <span className="block truncate" title={venue.name}>
                        {venue.name}
                      </span>
                    </th>
                    <td className="max-w-sm px-5 py-4 text-muted-foreground">
                      <span className="block truncate" title={venue.address}>
                        {venue.address}
                      </span>
                    </td>
                    <td className="px-5 py-4 text-muted-foreground">
                      {venue.city}
                    </td>
                    <td className="px-5 py-4 text-xs text-muted-foreground">
                      Not available in API
                    </td>
                    <td className="px-5 py-4">
                      <div className="flex justify-end">
                        <DropdownMenu>
                          <DropdownMenuTrigger aria-label={`Actions for ${venue.name}`} className="inline-flex size-9 items-center justify-center rounded-lg text-muted-foreground transition hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><EllipsisVertical aria-hidden="true" className="size-5" /></DropdownMenuTrigger>
                          <DropdownMenuContent align="end" className="w-44 rounded-xl p-1.5 shadow-lg">
                            <DropdownMenuItem disabled className="min-h-10 rounded-lg">Edit venue · unavailable</DropdownMenuItem>
                            <DropdownMenuItem disabled className="min-h-10 rounded-lg">Delete venue · unavailable</DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <Dialog open={dialogOpen} onOpenChange={closeDialog}>
        <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Add venue</DialogTitle>
            <DialogDescription>
              Enter the venue details. The address will appear on event detail
              pages.
            </DialogDescription>
          </DialogHeader>
          <form
            onSubmit={handleSubmit((values) => createMutation.mutate(values))}
            className="space-y-4"
          >
            {createMutation.isError ? (
              <p
                role="alert"
                aria-live="polite"
                className="rounded-lg border border-destructive/20 bg-destructive/5 px-3 py-2 text-sm text-destructive"
              >
                {getErrorMessage(createMutation.error)}
              </p>
            ) : null}
            <div className="space-y-2">
              <Label htmlFor="venue-name">Venue name</Label>
              <Input
                id="venue-name"
                autoComplete="off"
                placeholder="For example: City Theater…"
                aria-invalid={Boolean(errors.name)}
                aria-describedby={errors.name ? "venue-name-error" : undefined}
                {...register("name")}
              />
              {errors.name ? (
                <p id="venue-name-error" className="text-sm text-destructive">
                  {errors.name.message}
                </p>
              ) : null}
            </div>
            <div className="space-y-2">
              <Label htmlFor="venue-address">Address</Label>
              <Input
                id="venue-address"
                autoComplete="street-address"
                placeholder="Street address…"
                aria-invalid={Boolean(errors.address)}
                aria-describedby={
                  errors.address ? "venue-address-error" : undefined
                }
                {...register("address")}
              />
              {errors.address ? (
                <p
                  id="venue-address-error"
                  className="text-sm text-destructive"
                >
                  {errors.address.message}
                </p>
              ) : null}
            </div>
            <div className="space-y-2">
              <Label htmlFor="venue-city">City</Label>
              <Input
                id="venue-city"
                autoComplete="address-level2"
                placeholder="For example: Ho Chi Minh City…"
                aria-invalid={Boolean(errors.city)}
                aria-describedby={errors.city ? "venue-city-error" : undefined}
                {...register("city")}
              />
              {errors.city ? (
                <p id="venue-city-error" className="text-sm text-destructive">
                  {errors.city.message}
                </p>
              ) : null}
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => closeDialog(false)}
                disabled={createMutation.isPending}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={createMutation.isPending}>
                {createMutation.isPending ? (
                  <LoaderCircle aria-hidden="true" className="animate-spin" />
                ) : (
                  <Plus aria-hidden="true" />
                )}
                {createMutation.isPending ? "Saving…" : "Save venue"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
