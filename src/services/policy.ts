import type { PolicyDecision, SupportCase } from '../domain/types.js';
import type { AgentAction } from './claude-client.js';

const highRiskTerms = ['fraud', 'chargeback', 'legal', 'lawsuit', 'police'];

export function evaluatePolicy(supportCase: SupportCase): PolicyDecision {
  const normalizedIssue = supportCase.issue.toLowerCase();
  const risky = highRiskTerms.some((term) => normalizedIssue.includes(term));

  if (risky) {
    return {
      requiresApproval: true,
      reason: 'High-risk language detected; handoff to a human operator required.',
    };
  }

  return { requiresApproval: false };
}

// ─── Action-level policy gates ──────────────────────────────────────────────

export interface ActionPolicyResult {
  blocked: boolean;
  requiresApproval: boolean;
  reason?: string;
}

/** Sensitive terms that should never appear in outgoing messages. */
const blockedOutputTerms = [
  'social security',
  'credit card number',
  'full card',
  'bank account',
  'routing number',
  'password',
  'pin number',
];

/** Terms that require user approval before sending. */
const approvalRequiredTerms = [
  'cancel my account',
  'delete my account',
  'accept the offer',
  'agree to',
  'authorize',
  'sign up',
  'subscribe',
  'payment',
  'pay now',
];

export function evaluateActionPolicy(action: AgentAction): ActionPolicyResult {
  if (action.type === 'type_message') {
    const text = action.text.toLowerCase();

    // Block messages containing sensitive data patterns
    for (const term of blockedOutputTerms) {
      if (text.includes(term)) {
        return {
          blocked: true,
          requiresApproval: false,
          reason: `Blocked: message contains sensitive term "${term}". The agent should never share this information.`,
        };
      }
    }

    // Require approval for messages with significant commitments
    for (const term of approvalRequiredTerms) {
      if (text.includes(term)) {
        return {
          blocked: false,
          requiresApproval: true,
          reason: `The agent wants to say: "${action.text}". This may involve a commitment. Please approve.`,
        };
      }
    }
  }

  if (action.type === 'click_button') {
    const label = action.buttonLabel.toLowerCase();
    const riskyButtons = ['pay', 'purchase', 'buy', 'subscribe', 'delete', 'confirm order'];
    for (const term of riskyButtons) {
      if (label.includes(term)) {
        return {
          blocked: false,
          requiresApproval: true,
          reason: `The agent wants to click "${action.buttonLabel}". This may involve a financial action. Please approve.`,
        };
      }
    }
  }

  return { blocked: false, requiresApproval: false };
}
