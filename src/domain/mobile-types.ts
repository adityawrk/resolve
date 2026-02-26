import type { Strategy } from './types.js';

export type MobilePlatform = 'android' | 'ios';

export interface AppPermissionGrant {
  appId: string;
  canLaunch: boolean;
  canNavigateSupport: boolean;
  canUploadMedia: boolean;
}

export interface CompanionDevice {
  id: string;
  name: string;
  platform: MobilePlatform;
  authToken: string;
  appGrants: AppPermissionGrant[];
  registeredAt: string;
  updatedAt: string;
  lastSeenAt?: string;
}

export type PublicCompanionDevice = Omit<CompanionDevice, 'authToken'>;

export interface RegisterDeviceInput {
  name: string;
  platform: MobilePlatform;
}

export interface UpdatePermissionsInput {
  appGrants: AppPermissionGrant[];
}

export type CompanionCommandStatus = 'queued' | 'in_progress' | 'completed' | 'failed' | 'expired';

export type CompanionCommandType = 'raise_complaint';

export type CompanionEventStage =
  | 'queued'
  | 'device_ack'
  | 'step'
  | 'completed'
  | 'failed'
  | 'expired';

export interface CompanionCommandEvent {
  at: string;
  stage: CompanionEventStage;
  message: string;
  meta?: Record<string, string | number | boolean>;
}

export interface RaiseComplaintCommandPayload {
  caseId: string;
  customerName: string;
  issue: string;
  orderId?: string;
  attachmentPaths: string[];
  targetPlatform: string;
  desiredOutcome: Strategy;
}

export interface CompanionCommand {
  id: string;
  deviceId: string;
  type: CompanionCommandType;
  status: CompanionCommandStatus;
  payload: RaiseComplaintCommandPayload;
  createdAt: string;
  updatedAt: string;
  resultSummary?: string;
  errorMessage?: string;
  events: CompanionCommandEvent[];
}
