import { describe, expect, it } from 'vitest';
import { DeviceStore } from '../src/domain/device-store.js';

describe('DeviceStore', () => {
  it('registers device and returns public view without auth token', () => {
    const store = new DeviceStore();
    const device = store.register({ name: 'Pixel 8', platform: 'android' });

    expect(device.id).toBeTruthy();
    expect(device.authToken).toBeTruthy();

    const publicDevice = store.publicView(device);
    expect('authToken' in publicDevice).toBe(false);
    expect(publicDevice.name).toBe('Pixel 8');
  });

  it('normalizes app ids when updating permissions', () => {
    const store = new DeviceStore();
    const device = store.register({ name: 'Pixel 8', platform: 'android' });

    const updated = store.updatePermissions(device.id, {
      appGrants: [
        {
          appId: ' Amazon ',
          canLaunch: true,
          canNavigateSupport: true,
          canUploadMedia: true,
        },
      ],
    });

    expect(updated.appGrants[0]?.appId).toBe('amazon');
  });

  it('validates token correctly', () => {
    const store = new DeviceStore();
    const device = store.register({ name: 'Pixel 8', platform: 'android' });

    expect(store.validateToken(device.id, device.authToken)).toBe(true);
    expect(store.validateToken(device.id, 'invalid-token')).toBe(false);
  });

  it('upserts permission grants without replacing unrelated app grants', () => {
    const store = new DeviceStore();
    const device = store.register({ name: 'Pixel 8', platform: 'android' });

    store.upsertPermissionGrant(device.id, {
      appId: 'amazon',
      canLaunch: true,
      canNavigateSupport: true,
      canUploadMedia: true,
    });
    store.upsertPermissionGrant(device.id, {
      appId: 'flipkart',
      canLaunch: true,
      canNavigateSupport: true,
      canUploadMedia: false,
    });
    const updated = store.upsertPermissionGrant(device.id, {
      appId: 'amazon',
      canLaunch: true,
      canNavigateSupport: true,
      canUploadMedia: false,
    });

    expect(updated.appGrants).toHaveLength(2);
    expect(updated.appGrants.find((grant) => grant.appId === 'amazon')?.canUploadMedia).toBe(false);
    expect(updated.appGrants.find((grant) => grant.appId === 'flipkart')).toBeTruthy();
  });
});
