import type { CompanionDevice } from '../domain/mobile-types.js';

interface PermissionCheckResult {
  ok: boolean;
  reason?: string;
}

export function canDeviceHandleSupport(
  device: CompanionDevice,
  targetPlatform: string,
  requiresMediaUpload: boolean,
): PermissionCheckResult {
  const appId = normalize(targetPlatform);
  const grant =
    device.appGrants.find((candidate) => candidate.appId === appId) ??
    device.appGrants.find((candidate) => candidate.appId === '*');

  if (!grant) {
    return {
      ok: false,
      reason: `Device has no granted permission for app '${appId}'.`,
    };
  }

  if (!grant.canLaunch || !grant.canNavigateSupport) {
    return {
      ok: false,
      reason: `Device permission for '${appId}' does not allow launching and support navigation.`,
    };
  }

  if (requiresMediaUpload && !grant.canUploadMedia) {
    return {
      ok: false,
      reason: `Device permission for '${appId}' does not allow media upload.`,
    };
  }

  return { ok: true };
}

function normalize(value: string): string {
  return value.trim().toLowerCase();
}
