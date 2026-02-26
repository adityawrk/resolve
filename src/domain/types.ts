export type CaseStatus = 'queued' | 'running' | 'paused_for_approval' | 'completed' | 'failed';

export type IssueCategory = 'billing' | 'shipping' | 'damaged' | 'account' | 'unknown';

export type Strategy = 'refund' | 'human' | 'close';

export type ExecutionMode = 'mock_browser' | 'mobile_companion' | 'browser_extension';

export interface CaseEvent {
  at: string;
  type:
    | 'case_created'
    | 'intent_inferred'
    | 'policy_gate'
    | 'automation_step'
    | 'portal_response'
    | 'case_completed'
    | 'case_failed';
  message: string;
  meta?: Record<string, string | number | boolean>;
}

export interface SupportCase {
  id: string;
  customerName: string;
  issue: string;
  orderId?: string;
  attachmentPaths: string[];
  category: IssueCategory;
  strategy: Strategy;
  status: CaseStatus;
  executionMode?: ExecutionMode;
  desiredOutcome?: string;
  createdAt: string;
  updatedAt: string;
  events: CaseEvent[];
  resolutionSummary?: string;
  lastError?: string;
}

export interface CreateCaseInput {
  customerName: string;
  issue: string;
  orderId?: string;
  attachmentPaths?: string[];
  desiredOutcome?: string;
}

export interface PolicyDecision {
  requiresApproval: boolean;
  reason?: string;
}
