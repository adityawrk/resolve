import { describe, it, expect } from 'vitest';
import { evaluateActionPolicy } from '../src/services/policy.js';
import type { AgentAction } from '../src/services/claude-client.js';

describe('evaluateActionPolicy', () => {
  it('allows normal messages', () => {
    const action: AgentAction = { type: 'type_message', text: 'Hi, I need help with my order' };
    const result = evaluateActionPolicy(action);
    expect(result.blocked).toBe(false);
    expect(result.requiresApproval).toBe(false);
  });

  it('blocks messages containing sensitive terms', () => {
    const action: AgentAction = { type: 'type_message', text: 'My social security number is 123-45-6789' };
    const result = evaluateActionPolicy(action);
    expect(result.blocked).toBe(true);
  });

  it('requires approval for commitment language', () => {
    const action: AgentAction = { type: 'type_message', text: 'I agree to the terms' };
    const result = evaluateActionPolicy(action);
    expect(result.blocked).toBe(false);
    expect(result.requiresApproval).toBe(true);
  });

  it('requires approval for risky button clicks', () => {
    const action: AgentAction = { type: 'click_button', buttonLabel: 'Confirm Purchase' };
    const result = evaluateActionPolicy(action);
    expect(result.requiresApproval).toBe(true);
  });

  it('allows safe button clicks', () => {
    const action: AgentAction = { type: 'click_button', buttonLabel: 'Request Refund' };
    const result = evaluateActionPolicy(action);
    expect(result.blocked).toBe(false);
    expect(result.requiresApproval).toBe(false);
  });

  it('allows wait actions', () => {
    const action: AgentAction = { type: 'wait', durationMs: 5000, reason: 'waiting' };
    const result = evaluateActionPolicy(action);
    expect(result.blocked).toBe(false);
    expect(result.requiresApproval).toBe(false);
  });

  it('allows mark_resolved', () => {
    const action: AgentAction = { type: 'mark_resolved', summary: 'Refund granted' };
    const result = evaluateActionPolicy(action);
    expect(result.blocked).toBe(false);
    expect(result.requiresApproval).toBe(false);
  });
});
