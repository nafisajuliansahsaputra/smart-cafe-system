import { dailyCashCsv } from "@/lib/cash-report-csv";
import { canUseOcc, dailyCashReport, staffIdentity } from "@/lib/staff-server";

export const dynamic = "force-dynamic";

const headers = {
  "Cache-Control": "private, no-store, max-age=0",
  "Pragma": "no-cache",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
};

function failure(status: number): Response {
  return new Response("Laporan tidak tersedia.", {
    status,
    headers: { ...headers, "Content-Type": "text/plain; charset=utf-8" },
  });
}

export async function GET(request: Request): Promise<Response> {
  const identity = await staffIdentity();
  if (identity.kind === "signed-out") return failure(401);
  if (identity.kind === "unavailable") return failure(503);
  if (!canUseOcc(identity.staff.roles)) return failure(404);

  const dates = new URL(request.url).searchParams.getAll("date");
  if (dates.length !== 1 || !/^\d{4}-\d{2}-\d{2}$/.test(dates[0])) {
    return failure(400);
  }
  const report = await dailyCashReport(dates[0]);
  if (!report.ok) {
    if (report.status === 400 || report.status === 401) return failure(report.status);
    if (report.status === 403 || report.status === 404) return failure(404);
    return failure(503);
  }

  return new Response("\uFEFF" + dailyCashCsv(report.data), {
    status: 200,
    headers: {
      ...headers,
      "Content-Type": "text/csv; charset=utf-8",
      "Content-Disposition": `attachment; filename="cash-daily-${report.data.date}.csv"`,
    },
  });
}
