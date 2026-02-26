import { describe, expect, it } from 'vitest';
import { canDeviceHandleSupport } from '../src/services/mobile-permissions.js';
import type { CompanionDevice } from '../src/domain/mobile-types.js';

function makeDevice(grants: CompanionDevice['appGrants']): CompanionDevice {
  const now = new Date().toISOString();
  return {
    id: 'd-1',
    name: 'Pixel',
    platform: 'android',
    authToken: 'token',
    appGrants: grants,
    registeredAt: now,
    updatedAt: now,
  };
}

describe('canDeviceHandleSupport', () => {
  it('fails when app permission is missing', () => {
    const result = canDeviceHandleSupport(makeDevice([]), 'amazon', false);
    expect(result.ok).toBe(false);
  });

  it('fails when media upload is required but not granted', () => {
    const result = canDeviceHandleSupport(
      makeDevice([
        {
          appId: 'amazon',
          canLaunch: true,
          canNavigateSupport: true,
          canUploadMedia: false,
        },
      ]),
      'amazon',
      true,
    );

    expect(result.ok).toBe(false);
    expect(result.reason).toContain('media');
  });

  it('passes when grant supports launch/navigation/media', () => {
    const result = canDeviceHandleSupport(
      makeDevice([
        {
          appId: 'amazon',
          canLaunch: true,
          canNavigateSupport: true,
          canUploadMedia: true,
        },
      ]),
      'amazon',
      true,
    );

    expect(result).toEqual({ ok: true });
  });
});
