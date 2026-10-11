import { CircleCheck, Clock, type LucideIcon, Send } from "lucide-react";
import { REIMBURSEMENT_LABELS, type ReimbursementStatus } from "../../api/entries";

/** Each status in words and with its own icon, so none is told apart by color alone. */
const LOOK: Record<ReimbursementStatus, { icon?: LucideIcon; className: string }> = {
  none: { className: "text-muted" },
  to_submit: { icon: Send, className: "font-semibold text-warn-ink" },
  submitted: { icon: Clock, className: "font-semibold text-estimate-ink" },
  reimbursed: { icon: CircleCheck, className: "font-semibold text-accent-ink" },
};

/** Where an expense stands on being paid back, as a list row says it. */
export function ReimbursementBadge({ status }: { status: ReimbursementStatus }) {
  const { icon: Icon, className } = LOOK[status];
  return (
    <span className={`inline-flex items-center gap-1 whitespace-nowrap ${className}`}>
      {Icon && <Icon aria-hidden className="size-4 shrink-0" />}
      {REIMBURSEMENT_LABELS[status]}
    </span>
  );
}
