import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';
import type { SupportCase } from '../domain/types.js';

export interface RunResult {
  resolution: string;
  selectedOption: 'refund' | 'human' | 'close';
  portalStatus: string;
}

interface RunnerParams {
  baseUrl: string;
  supportCase: SupportCase;
  allowRefund: boolean;
  onStep: (message: string, meta?: Record<string, string | number | boolean>) => void;
}

export async function runMockSupportSession(params: RunnerParams): Promise<RunResult> {
  const { baseUrl, supportCase, allowRefund, onStep } = params;
  const browser = await chromium.launch({ headless: true });

  try {
    const page = await browser.newPage();
    const portalUrl = `${baseUrl}/mock-support`;
    onStep('Opening mock support portal', { portalUrl });
    await page.goto(portalUrl, { waitUntil: 'domcontentloaded' });

    onStep('Filling customer details');
    await page.fill('#customerName', supportCase.customerName);
    if (supportCase.orderId) {
      await page.fill('#orderId', supportCase.orderId);
    }

    onStep('Selecting issue category', { category: supportCase.category });
    await page.selectOption('#issueCategory', supportCase.category);

    const firstAttachment = resolveAttachment(supportCase.attachmentPaths[0]);
    if (firstAttachment) {
      onStep('Uploading attachment', { path: firstAttachment });
      await page.setInputFiles('#uploadInput', firstAttachment);
    }

    onStep('Sending issue summary message');
    await page.fill('#chatInput', supportCase.issue);
    await page.click('#sendBtn');

    await page.waitForSelector('button[data-option="refund"]', { timeout: 5000 });

    const selectedOption = pickOption(supportCase.strategy, allowRefund);
    onStep('Choosing support action', { selectedOption, allowRefund });
    await page.click(`button[data-option="${selectedOption}"]`);

    await page.waitForTimeout(200);
    const portalStatus = (await page.textContent('#resolutionStatus'))?.trim() ?? 'Status unavailable';

    onStep('Captured portal status', { portalStatus });

    return {
      resolution: buildResolution(selectedOption, portalStatus),
      selectedOption,
      portalStatus,
    };
  } finally {
    await browser.close();
  }
}

function resolveAttachment(maybePath: string | undefined): string | undefined {
  if (!maybePath) {
    return undefined;
  }

  const resolved = path.isAbsolute(maybePath)
    ? maybePath
    : path.resolve(process.cwd(), maybePath);

  if (!fs.existsSync(resolved)) {
    return undefined;
  }

  return resolved;
}

function pickOption(strategy: SupportCase['strategy'], allowRefund: boolean): 'refund' | 'human' | 'close' {
  if (strategy === 'refund' && allowRefund) {
    return 'refund';
  }
  if (strategy === 'close') {
    return 'close';
  }
  return 'human';
}

function buildResolution(option: 'refund' | 'human' | 'close', portalStatus: string): string {
  if (option === 'refund') {
    return `Agent secured refund. ${portalStatus}`;
  }
  if (option === 'human') {
    return `Agent escalated to human rep. ${portalStatus}`;
  }
  return `Agent closed the ticket. ${portalStatus}`;
}
