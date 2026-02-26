import { describe, expect, it } from 'vitest';
import { evaluatePolicy } from '../src/services/policy.js';
import type { SupportCase } from '../src/domain/types.js';

function makeCase(issue: string): SupportCase {
  return {
    id: 'case-1',
    customerName: 'Test User',
    issue,
    orderId: 'ORD-1',
    attachmentPaths: [],
    category: 'billing',
    strategy: 'refund',
    status: 'queued',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    events: [],
  };
}

describe('evaluatePolicy', () => {
  it('flags high-risk legal language', () => {
    const decision = evaluatePolicy(makeCase('I will file a lawsuit for this chargeback issue'));
    expect(decision.requiresApproval).toBe(true);
    expect(decision.reason).toBeTruthy();
  });

  it('allows normal cases', () => {
    const decision = evaluatePolicy(makeCase('My item arrived damaged and I need a refund'));
    expect(decision).toEqual({ requiresApproval: false });
  });
});
