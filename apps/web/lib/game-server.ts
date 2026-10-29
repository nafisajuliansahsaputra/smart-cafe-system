import "server-only";

import { guestApi, guestToken, envelopeData } from "@/lib/guest-server";
import { staffApi, staffToken, dataFromEnvelope } from "@/lib/staff-server";

export type GameView = { id: string; name: string; description: string | null; availableCopies: number };
export type GameCopyView = {
  id: string; gameId: string; gameName: string;
  copyCode: string; condition: "AVAILABLE" | "DAMAGED" | "LOST" | "RETIRED";
};
export type GameCopyPage = { items: GameCopyView[]; page: number; hasNext: boolean };
export type GameConditionHistoryView = {
  id: string; copyId: string; gameName: string; copyCode: string;
  beforeCondition: "AVAILABLE" | "DAMAGED" | "LOST";
  afterCondition: "AVAILABLE" | "DAMAGED";
  reason: string; actorLogin: string; recordedAt: string;
};
export type GameConditionHistoryPage = {
  items: GameConditionHistoryView[]; page: number; hasNext: boolean;
};
export type GameLossIncidentView = {
  id: string; loanId: string; gameName: string; copyCode: string;
  tableCode: string; status: "OPEN" | "DISMISSED" | "CONFIRMED_LOST";
  reason: string; reviewReason: string | null; reportedAt: string;
};
export type GameLossIncidentPage = {
  items: GameLossIncidentView[]; page: number; hasNext: boolean; openCount: number;
};
export type GameLoanView = {
  id: string; gameId: string; gameName: string;
  status: "REQUESTED" | "ASSIGNED" | "IN_USE" | "RETURN_PENDING" | "RETURNED" | "CANCELLED" | "LOST";
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
    ["REQUESTED","ASSIGNED","IN_USE","RETURN_PENDING","RETURNED","CANCELLED","LOST"].includes(String(value.status)) &&
    typeof value.tableCode === "string" &&
    (value.copyCode === null || typeof value.copyCode === "string") &&
    typeof value.requestedAt === "string";
}
function result<T>(status: number, data: unknown, check: (value: unknown) => value is T): Result<T> {
  if (status !== 200) return { ok: false, status };
  return check(data) ? { ok: true, data } : { ok: false, status: 502 };
}
function gameCopy(value: unknown): value is GameCopyView {
  return record(value) && typeof value.id === "string" && uuid.test(value.id) &&
    typeof value.gameId === "string" && uuid.test(value.gameId) &&
    typeof value.gameName === "string" && typeof value.copyCode === "string" &&
    ["AVAILABLE","DAMAGED","LOST","RETIRED"].includes(String(value.condition));
}
const copies = (value: unknown): value is GameCopyView[] =>
  Array.isArray(value) && value.every(gameCopy);
function copyPage(value: unknown): value is GameCopyPage {
  return record(value) && Array.isArray(value.items) &&
    value.items.length<=25 && value.items.every(gameCopy) &&
    Number.isSafeInteger(value.page) && (value.page as number)>=0 &&
    (value.page as number)<=2000 && typeof value.hasNext === "boolean";
}
function historyItem(value: unknown): value is GameConditionHistoryView {
  return record(value) && typeof value.id === "string" && uuid.test(value.id) &&
    typeof value.copyId === "string" && uuid.test(value.copyId) &&
    typeof value.gameName === "string" &&
    typeof value.copyCode === "string" && typeof value.reason === "string" &&
    typeof value.actorLogin === "string" &&
    typeof value.recordedAt === "string" && !Number.isNaN(Date.parse(value.recordedAt)) &&
    ["AVAILABLE","DAMAGED","LOST"].includes(String(value.beforeCondition)) &&
    ["AVAILABLE","DAMAGED"].includes(String(value.afterCondition));
}
const history = (value: unknown): value is GameConditionHistoryView[] =>
  Array.isArray(value) && value.every(historyItem);
function historyPage(value: unknown): value is GameConditionHistoryPage {
  return record(value) && Array.isArray(value.items) &&
    value.items.length<=25 && value.items.every(historyItem) &&
    Number.isSafeInteger(value.page) && (value.page as number)>=0 &&
    (value.page as number)<=2000 && typeof value.hasNext==="boolean";
}
function gameLossIncident(value: unknown): value is GameLossIncidentView {
  return record(value) && typeof value.id==="string" && uuid.test(value.id) &&
    typeof value.loanId==="string" && uuid.test(value.loanId) &&
    typeof value.gameName==="string" && typeof value.copyCode==="string" &&
    typeof value.tableCode==="string" &&
    ["OPEN","DISMISSED","CONFIRMED_LOST"].includes(String(value.status)) &&
    typeof value.reason==="string" &&
    (value.reviewReason===null || typeof value.reviewReason==="string") &&
    typeof value.reportedAt==="string";
}
const gameLossIncidents=(value:unknown): value is GameLossIncidentView[] =>
  Array.isArray(value) && value.length<=100 && value.every(gameLossIncident);
function incidentPage(value:unknown):value is GameLossIncidentPage {
  return record(value) && Array.isArray(value.items) &&
    value.items.length<=25 && value.items.every(gameLossIncident) &&
    Number.isSafeInteger(value.page) && (value.page as number)>=0 &&
    (value.page as number)<=2000 && typeof value.hasNext==="boolean" &&
    Number.isSafeInteger(value.openCount) && (value.openCount as number)>=0;
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

/** Manager/admin only; never fetch copy history as a guest or waiter. */
export async function adminGameCopies(): Promise<Result<GameCopyView[]>> {
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const r = await staffApi("/api/v1/admin/games/copies", "GET", token);
  return result(r.status, dataFromEnvelope(r.payload), copies);
}

/** Read-only manager/admin inventory condition audit, latest 100 events. */
export async function adminGameConditionHistory(): Promise<Result<GameConditionHistoryView[]>> {
  const token = await staffToken();
  if (!token) return { ok: false, status: 401 };
  const r = await staffApi("/api/v1/admin/games/condition-history", "GET", token);
  return result(r.status, dataFromEnvelope(r.payload), history);
}

/** Paged, filtered manager inventory: backend does all searching. */
export async function searchAdminGameCopies(
  q: string, condition: string, page: number,
): Promise<Result<GameCopyPage>> {
  const token=await staffToken();
  if (!token) return {ok:false,status:401};
  const params=new URLSearchParams({q,condition,page:String(page)});
  const r=await staffApi(`/api/v1/admin/games/copies/search?${params}`,"GET",token);
  const parsed=result(r.status,dataFromEnvelope(r.payload),copyPage);
  return parsed.ok && parsed.data.page!==page ? {ok:false,status:502} : parsed;
}

/** Search across the complete manager-only audit table, not latest-100. */
export async function searchAdminGameConditionHistory(
  q: string, change: string, page: number,
): Promise<Result<GameConditionHistoryPage>> {
  const token=await staffToken();
  if (!token) return {ok:false,status:401};
  const params=new URLSearchParams({q,change,page:String(page)});
  const r=await staffApi(`/api/v1/admin/games/condition-history/search?${params}`,"GET",token);
  const parsed=result(r.status,dataFromEnvelope(r.payload),historyPage);
  return parsed.ok && parsed.data.page!==page ? {ok:false,status:502} : parsed;
}

/** Loss incident evidence is restricted to manager/admin server sessions. */
export async function adminGameLossIncidents(): Promise<Result<GameLossIncidentView[]>> {
  const token=await staffToken();
  if (!token) return {ok:false,status:401};
  const r=await staffApi("/api/v1/admin/games/loss-incidents","GET",token);
  return result(r.status,dataFromEnvelope(r.payload),gameLossIncidents);
}

/** Manager-only full-database incident register, with independently scoped
 * OPEN backlog count; never fetch notes into guest or waiter pages.
 */
export async function searchAdminGameLossIncidents(
  q:string,status:string,page:number,
):Promise<Result<GameLossIncidentPage>> {
  const token=await staffToken();
  if (!token) return {ok:false,status:401};
  const params=new URLSearchParams({q,status,page:String(page)});
  const r=await staffApi(`/api/v1/admin/games/loss-incidents/search?${params}`,"GET",token);
  const parsed=result(r.status,dataFromEnvelope(r.payload),incidentPage);
  return parsed.ok && parsed.data.page!==page ? {ok:false,status:502} : parsed;
}
