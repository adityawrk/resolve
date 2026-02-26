import type { CaseStore } from '../domain/case-store.js';
import type { DeviceStore } from '../domain/device-store.js';
import type { DeviceCommandStore } from '../domain/device-command-store.js';
import { evaluatePolicy } from './policy.js';
import { runMockSupportSession } from '../automation/mock-support-runner.js';
import { canDeviceHandleSupport } from './mobile-permissions.js';

type ExecutionChannel =
  | { kind: 'mock_browser' }
  | { kind: 'mobile_companion'; deviceId: string; targetPlatform: string }
  | { kind: 'browser_extension'; widgetProvider: string };

interface AgentDeps {
  store: CaseStore;
  baseUrl: string;
  deviceStore?: DeviceStore;
  commandStore?: DeviceCommandStore;
  mobileTimeoutMs?: number;
}

export class SupportAgentService {
  private readonly running = new Set<string>();
  private readonly pendingChannels = new Map<string, ExecutionChannel>();
  private readonly store: CaseStore;
  private readonly baseUrl: string;
  private readonly deviceStore?: DeviceStore;
  private readonly commandStore?: DeviceCommandStore;
  private readonly mobileTimeoutMs: number;

  constructor(deps: AgentDeps) {
    this.store = deps.store;
    this.baseUrl = deps.baseUrl;
    this.deviceStore = deps.deviceStore;
    this.commandStore = deps.commandStore;
    this.mobileTimeoutMs = deps.mobileTimeoutMs ?? 120_000;
  }

  runCase(caseId: string): void {
    this.runCaseWithOptions(caseId, {
      bypassPolicy: false,
      channel: { kind: 'mock_browser' },
    });
  }

  runCaseOnExtension(caseId: string, input: { widgetProvider: string }): void {
    const supportCase = this.store.get(caseId);
    if (supportCase) {
      supportCase.executionMode = 'browser_extension';
    }
    this.store.updateStatus(caseId, 'running');
    this.store.appendEvent(caseId, {
      type: 'automation_step',
      message: `Browser extension session started (widget: ${input.widgetProvider})`,
      meta: { widgetProvider: input.widgetProvider },
    });
    // The actual agent loop is driven by the WebSocket connection in index.ts
    // The extension sends widget state, the agent loop processes it
  }

  runCaseOnMobile(caseId: string, input: { deviceId: string; targetPlatform: string }): void {
    this.runCaseWithOptions(caseId, {
      bypassPolicy: false,
      channel: {
        kind: 'mobile_companion',
        deviceId: input.deviceId,
        targetPlatform: input.targetPlatform.trim().toLowerCase(),
      },
    });
  }

  approveAndRun(caseId: string): void {
    this.store.appendEvent(caseId, {
      type: 'policy_gate',
      message: 'Case approved by human operator; automation resumed.',
    });

    const pendingChannel = this.pendingChannels.get(caseId) ?? { kind: 'mock_browser' as const };
    this.runCaseWithOptions(caseId, {
      bypassPolicy: true,
      channel: pendingChannel,
    });
  }

  private runCaseWithOptions(
    caseId: string,
    options: {
      bypassPolicy: boolean;
      channel: ExecutionChannel;
    },
  ): void {
    if (this.running.has(caseId)) {
      return;
    }

    this.pendingChannels.set(caseId, options.channel);
    this.running.add(caseId);

    void this.runCaseInternal(caseId, options)
      .catch((error: unknown) => {
        const message = error instanceof Error ? error.message : String(error);
        this.store.fail(caseId, message);
      })
      .finally(() => {
        this.running.delete(caseId);

        const latestCase = this.store.get(caseId);
        if (!latestCase || latestCase.status !== 'paused_for_approval') {
          this.pendingChannels.delete(caseId);
        }
      });
  }

  private async runCaseInternal(
    caseId: string,
    options: {
      bypassPolicy: boolean;
      channel: ExecutionChannel;
    },
  ): Promise<void> {
    const supportCase = this.store.get(caseId);
    if (!supportCase) {
      throw new Error(`Case not found: ${caseId}`);
    }

    this.store.updateStatus(caseId, 'running');

    const policyDecision = evaluatePolicy(supportCase);
    if (!options.bypassPolicy && policyDecision.requiresApproval) {
      this.store.updateStatus(caseId, 'paused_for_approval');
      this.store.appendEvent(caseId, {
        type: 'policy_gate',
        message: policyDecision.reason ?? 'Policy check failed.',
      });
      return;
    }

    if (options.channel.kind === 'mobile_companion') {
      await this.runMobileCompanionSession(caseId, options.channel.deviceId, options.channel.targetPlatform);
      return;
    }

    const result = await runMockSupportSession({
      baseUrl: this.baseUrl,
      supportCase,
      allowRefund: true,
      onStep: (message, meta) => {
        this.store.appendEvent(caseId, {
          type: 'automation_step',
          message,
          meta,
        });
      },
    });

    this.store.appendEvent(caseId, {
      type: 'portal_response',
      message: result.portalStatus,
      meta: { selectedOption: result.selectedOption },
    });
    this.store.complete(caseId, result.resolution);
  }

  private async runMobileCompanionSession(
    caseId: string,
    deviceId: string,
    targetPlatform: string,
  ): Promise<void> {
    if (!this.deviceStore || !this.commandStore) {
      throw new Error('Mobile companion dependencies are not configured');
    }

    const supportCase = this.store.get(caseId);
    if (!supportCase) {
      throw new Error(`Case not found: ${caseId}`);
    }

    const device = this.deviceStore.get(deviceId);
    if (!device) {
      throw new Error(`Device not found: ${deviceId}`);
    }

    const permissionCheck = canDeviceHandleSupport(
      device,
      targetPlatform,
      supportCase.attachmentPaths.length > 0,
    );

    if (!permissionCheck.ok) {
      throw new Error(permissionCheck.reason ?? 'Mobile permission check failed');
    }

    this.store.appendEvent(caseId, {
      type: 'automation_step',
      message: 'Dispatching support task to mobile companion',
      meta: {
        deviceId,
        targetPlatform,
      },
    });

    const command = this.commandStore.createRaiseComplaintCommand({
      deviceId,
      payload: {
        caseId,
        customerName: supportCase.customerName,
        issue: supportCase.issue,
        orderId: supportCase.orderId,
        attachmentPaths: supportCase.attachmentPaths,
        targetPlatform,
        desiredOutcome: supportCase.strategy,
      },
    });

    this.store.appendEvent(caseId, {
      type: 'automation_step',
      message: 'Mobile companion command queued',
      meta: {
        deviceId,
        commandId: command.id,
      },
    });

    const terminal = await this.commandStore.waitForTerminal(command.id, this.mobileTimeoutMs);

    if (terminal.status !== 'completed') {
      throw new Error(
        terminal.errorMessage ??
          `Mobile companion command ended with status '${terminal.status}' for command ${terminal.id}`,
      );
    }

    const summary = terminal.resultSummary ?? 'Complaint completed by mobile companion.';

    this.store.appendEvent(caseId, {
      type: 'portal_response',
      message: summary,
      meta: {
        deviceId,
        commandId: terminal.id,
      },
    });

    this.store.complete(caseId, `Mobile companion resolved complaint on ${targetPlatform}. ${summary}`);
  }
}
