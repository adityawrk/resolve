import { describe, expect, it } from 'vitest';
import { DeviceCommandStore } from '../src/domain/device-command-store.js';

describe('DeviceCommandStore', () => {
  it('queues and pulls command for a device', () => {
    const store = new DeviceCommandStore();
    const command = store.createRaiseComplaintCommand({
      deviceId: 'device-1',
      payload: {
        caseId: 'case-1',
        customerName: 'Asha',
        issue: 'Need refund',
        orderId: 'ORD-1',
        attachmentPaths: [],
        targetPlatform: 'amazon',
        desiredOutcome: 'refund',
      },
    });

    expect(command.status).toBe('queued');

    const pulled = store.pullNextQueued('device-1');
    expect(pulled?.id).toBe(command.id);
    expect(pulled?.status).toBe('in_progress');
  });

  it('waits for terminal completion', async () => {
    const store = new DeviceCommandStore();
    const command = store.createRaiseComplaintCommand({
      deviceId: 'device-1',
      payload: {
        caseId: 'case-1',
        customerName: 'Asha',
        issue: 'Need refund',
        orderId: 'ORD-1',
        attachmentPaths: [],
        targetPlatform: 'amazon',
        desiredOutcome: 'refund',
      },
    });

    const wait = store.waitForTerminal(command.id, 1000);
    store.complete(command.id, 'Completed on device');

    const terminal = await wait;
    expect(terminal.status).toBe('completed');
    expect(terminal.resultSummary).toContain('Completed');
  });

  it('expires when timeout is reached', async () => {
    const store = new DeviceCommandStore();
    const command = store.createRaiseComplaintCommand({
      deviceId: 'device-1',
      payload: {
        caseId: 'case-1',
        customerName: 'Asha',
        issue: 'Need refund',
        orderId: 'ORD-1',
        attachmentPaths: [],
        targetPlatform: 'amazon',
        desiredOutcome: 'refund',
      },
    });

    const terminal = await store.waitForTerminal(command.id, 20);
    expect(terminal.status).toBe('expired');
    expect(terminal.errorMessage).toContain('Timed out');
  });
});
