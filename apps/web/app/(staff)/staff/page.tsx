import { notFound } from "next/navigation";

// Do not expose an operational dashboard until real staff authentication exists.
export default function StaffPage() {
  notFound();
}
