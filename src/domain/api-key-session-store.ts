import { createHash } from 'node:crypto';
import type { MobilePlatform } from './mobile-types.js';

export interface ApiKeySession {
  id: string;
  deviceId: string;
  deviceName: string;
  platform: MobilePlatform;
  customerName: string;
  createdAt: string;
  updatedAt: string;
  lastUsedAt: string;
}

interface UpsertSessionInput {
  deviceId: string;
  deviceName: string;
  platform: MobilePlatform;
  customerName: string;
}

export class ApiKeySessionStore {
  private readonly sessions = new Map<string, ApiKeySession>();

  get(apiKey: string): ApiKeySession | undefined {
    const keyId = normalizeApiKey(apiKey);
    if (!keyId) {
      return undefined;
    }

    return this.sessions.get(keyId);
  }

  upsert(apiKey: string, input: UpsertSessionInput): ApiKeySession {
    const keyId = requireApiKey(apiKey);
    const now = new Date().toISOString();
    const existing = this.sessions.get(keyId);

    const session: ApiKeySession = existing
      ? {
          ...existing,
          deviceId: input.deviceId,
          deviceName: input.deviceName,
          platform: input.platform,
          customerName: input.customerName,
          updatedAt: now,
          lastUsedAt: now,
        }
      : {
          id: keyId,
          deviceId: input.deviceId,
          deviceName: input.deviceName,
          platform: input.platform,
          customerName: input.customerName,
          createdAt: now,
          updatedAt: now,
          lastUsedAt: now,
        };

    this.sessions.set(keyId, session);
    return session;
  }

  touch(apiKey: string): ApiKeySession | undefined {
    const keyId = normalizeApiKey(apiKey);
    if (!keyId) {
      return undefined;
    }

    const session = this.sessions.get(keyId);
    if (!session) {
      return undefined;
    }

    const now = new Date().toISOString();
    session.lastUsedAt = now;
    session.updatedAt = now;
    return session;
  }
}

function requireApiKey(apiKey: string): string {
  const normalized = normalizeApiKey(apiKey);
  if (!normalized) {
    throw new Error('API key is required');
  }
  return normalized;
}

function normalizeApiKey(apiKey: string): string {
  const trimmed = apiKey.trim();
  if (!trimmed) {
    return '';
  }

  return createHash('sha256').update(trimmed).digest('hex');
}
