import { describe, expect, it } from 'vitest';
import { CaseStore } from '../src/domain/case-store.js';

describe('CaseStore archive callback', () => {
  it('invokes callback whenever case changes', () => {
    const seen: string[] = [];
    const store = new CaseStore({
      onCaseChanged: (supportCase) => {
        seen.push(`${supportCase.status}:${supportCase.events.length}`);
      },
    });

    const created = store.create({
      customerName: 'Asha',
      issue: 'Package damaged and need refund',
      orderId: 'ORD-9',
      attachmentPaths: ['fixtures/damaged-package.txt'],
    });

    store.updateStatus(created.id, 'running');
    store.appendEvent(created.id, {
      type: 'automation_step',
      message: 'Sent message to support',
    });
    store.complete(created.id, 'Case resolved');

    expect(seen.length).toBe(4);
    expect(seen[0]).toContain('queued:2');
    expect(seen[3]).toContain('completed:4');
  });
});
