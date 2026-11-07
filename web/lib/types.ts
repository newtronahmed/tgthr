export type PayoutRules = {
  destinationType: string;
  secondApprover: string;
  holdWindowMinutes: number;
  surplusRule: string;
};

export type FeedItem = {
  displayName?: string | null;
  anonymous: boolean;
  amountPesewas: number;
  at: string;
};

export type PublicGoal = {
  slug: string;
  title: string;
  template: string;
  targetType: "FIXED" | "OPEN";
  fixedAmountPesewas?: number | null;
  targetAmountPesewas?: number | null;
  minimumAmountPesewas: number;
  raisedPesewas: number;
  contributorCount: number;
  deadline?: string | null;
  status: "OPEN" | "CLOSED" | "FROZEN";
  payoutRules: PayoutRules;
  feed: FeedItem[];
};

export type Receipt = {
  reference: string;
  confirmedAt: string;
};

export type ContributionState =
  | "INITIATED"
  | "PENDING"
  | "CONFIRMED"
  | "FAILED"
  | "EXPIRED"
  | "REFUNDED";

export type ContributionStatus = {
  id: string;
  state: ContributionState;
  amountPesewas: number;
  processingFeePesewas: number;
  platformFeePesewas: number;
  feePesewas: number;
  totalPesewas: number;
  receipt?: Receipt | null;
};

export type FeeQuote = {
  amountPesewas: number;
  processingFeePesewas: number;
  platformFeePesewas: number;
  totalPesewas: number;
};

export type Problem = {
  type?: string;
  title?: string;
  status?: number;
  code?: string;
  detail?: string;
};

export type Network = "MTN" | "TELECEL" | "AT";
