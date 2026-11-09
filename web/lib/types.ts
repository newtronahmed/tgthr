export type PayoutRules = {
  destinationType: string;
  secondApproverName?: string | null;
  secondApprover: string;
  secondApproverThresholdPesewas: number;
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

export type OrganizerProfile = {
  id: string;
  displayName?: string | null;
  legalName?: string | null;
  createdAt: string;
};

export type OrganizerGoal = {
  id: string;
  slug: string;
  title: string;
  template: "TRIP" | "WEDDING" | "FUNERAL" | "RENT" | "COMMUNITY" | "GIFT" | "PARTY";
  targetType: "FIXED" | "OPEN";
  fixedAmountPesewas?: number | null;
  targetAmountPesewas?: number | null;
  minimumAmountPesewas?: number | null;
  status: "OPEN" | "CLOSED" | "FROZEN";
  deadline: string | null;
  destinationType: "BENEFICIARY_MOMO" | "ADMIN_MOMO";
  destinationAccountName: string;
  secondApproverName?: string | null;
  secondApprover: "OFF" | "ABOVE_THRESHOLD" | "ALWAYS";
  secondApproverThresholdPesewas: number;
  holdWindowMinutes: number;
  surplusRule: "PAY_BENEFICIARY" | "WELFARE_RESERVE";
  raisedPesewas: number;
  contributorCount: number;
  createdAt: string;
};

export type CreateGoalInput = {
  title: string;
  template: OrganizerGoal["template"];
  targetType: "FIXED" | "OPEN";
  fixedAmountPesewas?: number;
  targetAmountPesewas?: number;
  minimumAmountPesewas?: number;
  deadline: string;
  destinationType: "BENEFICIARY_MOMO" | "ADMIN_MOMO";
  destinationPhone: string;
  network: Network;
  secondApproverName?: string;
  secondApproverPhone?: string;
  secondApprover: "OFF" | "ABOVE_THRESHOLD" | "ALWAYS";
  secondApproverThresholdPesewas: number;
  holdWindowMinutes: number;
  surplusRule: "PAY_BENEFICIARY" | "WELFARE_RESERVE";
};
