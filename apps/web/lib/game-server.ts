import "server-only";

import { guestApi, guestToken, envelopeData } from "@/lib/guest-server";
import { staffApi, staffToken, dataFromEnvelope } from "@/lib/staff-server";

export type GameView = { id: string; name: string; description: string | null; availableCopies: number };
export type GameLoanView = {
  id: string; gameId: string; gameName: string;
  status: "REQUESTED" | "ASSIGNED" | "IN_USE" | "RETURN_PENDING" | "RETURNED" | "CANCELLED";
  tableCode: string; copyCode: string | null; requestedAt: string;
};
type Result<T> = { ok: true; data: T } | { ok: false; status: number };
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function game(value: unknown): value is GameView {
  return record(value) && typeof value.id === "string" && uuid.test(value.id) &&
    typeof value.name === "string" &&
    (value.description === null || typeof value.description === "string") &&
    typeof value.availableCopies === "number" &&
    Number.isSafeInteger(value.availableCopies) && value.availableCopies >= 0;
}
function loan(value: unknown): value is GameLoanView {
  return record(value) && typeof value.id === "string" && uuid.test(value.id) &&
    typeof value.gameId === "string" && uuid.test(value.gameId) &&
    typeof value.gameName === "string" &&
    ["REQUESTED","ASSIGNED","IN_USE","RETURNED","CANCELLED"].includes(String(value.status)) &&
    typeof value.tableCode === "string" &&
    (value.copyCode === null || typeof value.copyCode === "string") &&
    typeof value.requestedAt === "string";
}
function result<T>(status: number, data: unknown, check: (value: unknown) => value is T): Result<T> {
  if (status !== 200) return { ok: false, status };
  return check(data) ? { ok: true, data } : { ok: false, status: 502 };
}
const games = (value: unknown): value is GameView[] =>
  Array.isArray(value) && value.every(game);
const loans = (value: unknown): value is GameLoanView[] =>
  Array.isArray(value) && value.every(loan);

export async function guestGames(): Promise<Result<GameView[]>> {
  const token = await guestToken();
  if (!token) return { ok: false, status: 401 };
  const r = await guestApi("/api/v1/me/games", "GET", token);
  return result(r.status, envelopeData(r.payload), games);
}
export async function myGameLoans(): Promise<Result<GameLoanView[]>> {
  const token = await guestToken();
  if (!token) return { ok: false, status: 401 };
  const r = await guestApi("/api/v1/me/game-loans", "GET", token);
  return result(r.status, envelopeData(r.payload), loans);
}
export async function staffGames(): Promise<Result<GameView[]>> {
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const r = await staffApi("/api/v1/waiter/games", "GET", token);
  return result(r.status, dataFromEnvelope(r.payload), games);
}
export async function staffGameLoans(): Promise<Result<GameLoanView[]>> {
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const r = await staffApi("/api/v1/waiter/game-loans", "GET", token);
  return result(r.status, dataFromEnvelope(r.payload), loans);
}
