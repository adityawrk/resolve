import { describe, expect, it } from 'vitest';
import { ApiKeySessionStore } from '../src/domain/api-key-session-store.js';

describe('ApiKeySessionStore', () => {
  it('stores sessions by normalized API key hash', () => {
    const store = new ApiKeySessionStore();
    const session = store.upsert('  sk_test_123  ', {
      deviceId: 'device-1',
      deviceName: 'Pixel',
      platform: 'android',
      customerName: 'Test User',
    });

    expect(session.id).toHaveLength(64);

    const lookup = store.get('sk_test_123');
    expect(lookup?.deviceId).toBe('device-1');
    expect(lookup?.customerName).toBe('Test User');
  });

  it('updates existing session and refreshes timestamps', async () => {
    const store = new ApiKeySessionStore();
    const original = store.upsert('sk_live_1', {
      deviceId: 'device-1',
      deviceName: 'Pixel 1',
      platform: 'android',
      customerName: 'User One',
    });

    // Ensure timestamp changes on update.
    await new Promise((resolve) => setTimeout(resolve, 2));

    const updated = store.upsert('sk_live_1', {
      deviceId: 'device-2',
      deviceName: 'Pixel 2',
      platform: 'android',
      customerName: 'User Two',
    });

    expect(updated.id).toBe(original.id);
    expect(updated.deviceId).toBe('device-2');
    expect(updated.customerName).toBe('User Two');
    expect(updated.updatedAt > original.updatedAt).toBe(true);
    expect(updated.lastUsedAt > original.lastUsedAt).toBe(true);
  });
});
