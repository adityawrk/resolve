import { v4 as uuidv4 } from 'uuid';
import type { CaseEvent, CaseStatus, CreateCaseInput, SupportCase } from './types.js';
import { inferCategoryAndStrategy } from '../services/intent.js';

interface CaseStoreOptions {
  onCaseChanged?: (supportCase: SupportCase) => void;
}

export class CaseStore {
  private readonly cases = new Map<string, SupportCase>();
  private readonly onCaseChanged?: (supportCase: SupportCase) => void;

  constructor(options: CaseStoreOptions = {}) {
    this.onCaseChanged = options.onCaseChanged;
  }

  create(input: CreateCaseInput): SupportCase {
    const now = new Date().toISOString();
    const { category, strategy } = inferCategoryAndStrategy(input.issue);

    const supportCase: SupportCase = {
      id: uuidv4(),
      customerName: input.customerName,
      issue: input.issue,
      orderId: input.orderId,
      attachmentPaths: input.attachmentPaths ?? [],
      category,
      strategy,
      status: 'queued',
      createdAt: now,
      updatedAt: now,
      events: [
        {
          at: now,
          type: 'case_created',
          message: 'Case created',
        },
        {
          at: now,
          type: 'intent_inferred',
          message: 'Intent inferred from issue text',
          meta: {
            category,
            strategy,
          },
        },
      ],
    };

    this.cases.set(supportCase.id, supportCase);
    this.notifyChanged(supportCase);
    return supportCase;
  }

  get(caseId: string): SupportCase | undefined {
    return this.cases.get(caseId);
  }

  list(): SupportCase[] {
    return [...this.cases.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  updateStatus(caseId: string, status: CaseStatus): SupportCase {
    const existing = this.require(caseId);
    existing.status = status;
    existing.updatedAt = new Date().toISOString();
    this.notifyChanged(existing);
    return existing;
  }

  appendEvent(caseId: string, event: Omit<CaseEvent, 'at'>): SupportCase {
    const existing = this.require(caseId);
    existing.events.push({ at: new Date().toISOString(), ...event });
    existing.updatedAt = new Date().toISOString();
    this.notifyChanged(existing);
    return existing;
  }

  complete(caseId: string, summary: string): SupportCase {
    const existing = this.require(caseId);
    existing.status = 'completed';
    existing.resolutionSummary = summary;
    existing.events.push({
      at: new Date().toISOString(),
      type: 'case_completed',
      message: summary,
    });
    existing.updatedAt = new Date().toISOString();
    this.notifyChanged(existing);
    return existing;
  }

  fail(caseId: string, errorMessage: string): SupportCase {
    const existing = this.require(caseId);
    existing.status = 'failed';
    existing.lastError = errorMessage;
    existing.events.push({
      at: new Date().toISOString(),
      type: 'case_failed',
      message: errorMessage,
    });
    existing.updatedAt = new Date().toISOString();
    this.notifyChanged(existing);
    return existing;
  }

  private require(caseId: string): SupportCase {
    const found = this.cases.get(caseId);
    if (!found) {
      throw new Error(`Case not found: ${caseId}`);
    }
    return found;
  }

  private notifyChanged(supportCase: SupportCase): void {
    if (!this.onCaseChanged) {
      return;
    }

    try {
      this.onCaseChanged(cloneCase(supportCase));
    } catch (error: unknown) {
      // eslint-disable-next-line no-console
      console.error('Failed to archive case:', error);
    }
  }
}

function cloneCase(supportCase: SupportCase): SupportCase {
  return {
    ...supportCase,
    attachmentPaths: [...supportCase.attachmentPaths],
    events: supportCase.events.map((event) => ({
      ...event,
      ...(event.meta ? { meta: { ...event.meta } } : {}),
    })),
  };
}
