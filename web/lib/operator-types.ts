export type OperatorRole =
  | "REFUND_OPERATOR"
  | "RECONCILER"
  | "FINANCE_APPROVER"
  | "OPERATOR_ADMIN";

export type OperatorProfile = {
  id: string;
  username: string;
  roles: OperatorRole[];
};

export type RefundState = "PENDING" | "PROVIDER_PENDING" | "SUCCEEDED" | "FAILED";

export type RefundView = {
  id: string;
  contributionId: string;
  goalId: string;
  sourceAccount: "GOAL_POOL" | "SUSPENSE";
  amountMinorUnits: number;
  platformFeeMinorUnits: number;
  processingFeeMinorUnits: number;
  totalMinorUnits: number;
  currencyCode: "GHS";
  state: RefundState;
  providerReference?: string | null;
  clientReference: string;
  operatorId: string;
  reason: string;
  failureCode?: string | null;
  createdAt: string;
  succeededAt?: string | null;
  failedAt?: string | null;
};

export type ReconciliationItem = {
  contributionId: string;
  goalId: string;
  goalTitle: string;
  amountMinorUnits: number;
  currencyCode: "GHS";
  providerReference: string;
  suspendedAt: string;
  ageSeconds: number;
};

export type OperatorResult<T> =
  | { kind: "ok"; data: T }
  | { kind: "unauthorized" }
  | { kind: "forbidden" };
