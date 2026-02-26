import { v4 as uuidv4 } from 'uuid';
import type {
  AppPermissionGrant,
  CompanionDevice,
  PublicCompanionDevice,
  RegisterDeviceInput,
  UpdatePermissionsInput,
} from './mobile-types.js';

export class DeviceStore {
  private readonly devices = new Map<string, CompanionDevice>();

  register(input: RegisterDeviceInput): CompanionDevice {
    const now = new Date().toISOString();
    const device: CompanionDevice = {
      id: uuidv4(),
      name: input.name,
      platform: input.platform,
      authToken: uuidv4(),
      appGrants: [],
      registeredAt: now,
      updatedAt: now,
    };

    this.devices.set(device.id, device);
    return device;
  }

  get(deviceId: string): CompanionDevice | undefined {
    return this.devices.get(deviceId);
  }

  list(): CompanionDevice[] {
    return [...this.devices.values()].sort((a, b) => b.registeredAt.localeCompare(a.registeredAt));
  }

  updatePermissions(deviceId: string, input: UpdatePermissionsInput): CompanionDevice {
    const device = this.require(deviceId);
    device.appGrants = input.appGrants.map((grant) => ({ ...grant, appId: normalizeAppId(grant.appId) }));
    device.updatedAt = new Date().toISOString();
    return device;
  }

  upsertPermissionGrant(deviceId: string, grantInput: AppPermissionGrant): CompanionDevice {
    const device = this.require(deviceId);
    const grant: AppPermissionGrant = {
      ...grantInput,
      appId: normalizeAppId(grantInput.appId),
    };

    const index = device.appGrants.findIndex((candidate) => candidate.appId === grant.appId);
    if (index >= 0) {
      device.appGrants[index] = grant;
    } else {
      device.appGrants.push(grant);
    }

    device.updatedAt = new Date().toISOString();
    return device;
  }

  touchHeartbeat(deviceId: string): CompanionDevice {
    const device = this.require(deviceId);
    const now = new Date().toISOString();
    device.lastSeenAt = now;
    device.updatedAt = now;
    return device;
  }

  publicView(device: CompanionDevice): PublicCompanionDevice {
    const { authToken: _authToken, ...rest } = device;
    return rest;
  }

  listPublic(): PublicCompanionDevice[] {
    return this.list().map((device) => this.publicView(device));
  }

  validateToken(deviceId: string, token: string | undefined): boolean {
    const device = this.devices.get(deviceId);
    if (!device || !token) {
      return false;
    }

    return device.authToken === token;
  }

  private require(deviceId: string): CompanionDevice {
    const device = this.devices.get(deviceId);
    if (!device) {
      throw new Error(`Device not found: ${deviceId}`);
    }
    return device;
  }
}

function normalizeAppId(input: string): string {
  return input.trim().toLowerCase();
}
