import { v4 as uuidv4 } from 'uuid';
import type {
  CompanionCommand,
  CompanionCommandEvent,
  CompanionCommandStatus,
  RaiseComplaintCommandPayload,
} from './mobile-types.js';

interface CommandWaiter {
  resolve: (command: CompanionCommand) => void;
  timeoutHandle: NodeJS.Timeout;
}

export class DeviceCommandStore {
  private readonly commands = new Map<string, CompanionCommand>();
  private readonly waiters = new Map<string, CommandWaiter>();

  createRaiseComplaintCommand(input: {
    deviceId: string;
    payload: RaiseComplaintCommandPayload;
  }): CompanionCommand {
    const now = new Date().toISOString();

    const command: CompanionCommand = {
      id: uuidv4(),
      deviceId: input.deviceId,
      type: 'raise_complaint',
      status: 'queued',
      payload: input.payload,
      createdAt: now,
      updatedAt: now,
      events: [
        {
          at: now,
          stage: 'queued',
          message: 'Command queued for companion device',
        },
      ],
    };

    this.commands.set(command.id, command);
    return command;
  }

  get(commandId: string): CompanionCommand | undefined {
    return this.commands.get(commandId);
  }

  listForDevice(deviceId: string): CompanionCommand[] {
    return [...this.commands.values()]
      .filter((command) => command.deviceId === deviceId)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  pullNextQueued(deviceId: string): CompanionCommand | undefined {
    const next = [...this.commands.values()]
      .filter((command) => command.deviceId === deviceId && command.status === 'queued')
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt))[0];

    if (!next) {
      return undefined;
    }

    next.status = 'in_progress';
    next.updatedAt = new Date().toISOString();
    next.events.push({
      at: next.updatedAt,
      stage: 'device_ack',
      message: 'Device accepted command for execution',
    });

    return next;
  }

  appendEvent(
    commandId: string,
    event: Omit<CompanionCommandEvent, 'at'>,
  ): CompanionCommand {
    const command = this.require(commandId);
    command.events.push({ at: new Date().toISOString(), ...event });
    command.updatedAt = new Date().toISOString();
    return command;
  }

  complete(commandId: string, resultSummary: string): CompanionCommand {
    const command = this.require(commandId);
    command.status = 'completed';
    command.resultSummary = resultSummary;
    command.updatedAt = new Date().toISOString();
    command.events.push({
      at: command.updatedAt,
      stage: 'completed',
      message: resultSummary,
    });

    this.finalizeWaiter(command);
    return command;
  }

  fail(commandId: string, errorMessage: string): CompanionCommand {
    const command = this.require(commandId);
    command.status = 'failed';
    command.errorMessage = errorMessage;
    command.updatedAt = new Date().toISOString();
    command.events.push({
      at: command.updatedAt,
      stage: 'failed',
      message: errorMessage,
    });

    this.finalizeWaiter(command);
    return command;
  }

  expire(commandId: string, reason: string): CompanionCommand {
    const command = this.require(commandId);

    if (isTerminalStatus(command.status)) {
      return command;
    }

    command.status = 'expired';
    command.errorMessage = reason;
    command.updatedAt = new Date().toISOString();
    command.events.push({
      at: command.updatedAt,
      stage: 'expired',
      message: reason,
    });

    this.finalizeWaiter(command);
    return command;
  }

  async waitForTerminal(commandId: string, timeoutMs: number): Promise<CompanionCommand> {
    const existing = this.require(commandId);

    if (isTerminalStatus(existing.status)) {
      return existing;
    }

    return new Promise<CompanionCommand>((resolve) => {
      const timeoutHandle = setTimeout(() => {
        this.expire(commandId, 'Timed out waiting for companion device result');
      }, timeoutMs);

      this.waiters.set(commandId, { resolve, timeoutHandle });
    });
  }

  private finalizeWaiter(command: CompanionCommand): void {
    const waiter = this.waiters.get(command.id);
    if (!waiter) {
      return;
    }

    clearTimeout(waiter.timeoutHandle);
    this.waiters.delete(command.id);
    waiter.resolve(command);
  }

  private require(commandId: string): CompanionCommand {
    const command = this.commands.get(commandId);
    if (!command) {
      throw new Error(`Command not found: ${commandId}`);
    }
    return command;
  }
}

function isTerminalStatus(status: CompanionCommandStatus): boolean {
  return status === 'completed' || status === 'failed' || status === 'expired';
}
