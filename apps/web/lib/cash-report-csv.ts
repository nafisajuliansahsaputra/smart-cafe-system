import type { DailyCashReport } from "@/lib/staff-server";

// Protect labels from spreadsheet formula interpretation, including leading spaces.
function csvCell(value: string | number): string {
  let content = String(value).replace(/[\x00-\x1f\x7f]/g, " ");
  if (/^\s*[=+@-]/u.test(content)) content = "'" + content;
  return '"' + content.replace(/"/g, '""') + '"';
}

// Report totals come from finalized CASH receipts only, not browser calculations.
export function dailyCashCsv(report: DailyCashReport): string {
  const rows: (string | number)[][] = [
    ["record_type", "metric", "item_name", "quantity", "amount_idr", "date_wib"],
    ["summary", "paid_orders", "", report.paidOrders, "", report.date],
    ["summary", "gross_cash_collected", "", "", report.grossCollectedIdr, report.date],
    ...report.topItems.map((item) => [
      "top_item", "item_gross_sales", item.name, item.quantity, item.grossIdr, report.date,
    ]),
  ];
  return rows.map((row) => row.map(csvCell).join(",")).join("\r\n") + "\r\n";
}
