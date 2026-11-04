export type TableActionState = {
  kind: "idle" | "success" | "error" | "proof";
  message: string;
  joinProof?: string;
  expiresAt?: string;
  visitId?: string;
};

export const initialTableAction: TableActionState = { kind: "idle", message: "" };
