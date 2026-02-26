import fs from 'node:fs';
import path from 'node:path';
import type { SupportCase } from '../domain/types.js';

export class ConversationArchive {
  private readonly baseDir: string;

  constructor(baseDir: string) {
    this.baseDir = path.resolve(baseDir);
    fs.mkdirSync(this.baseDir, { recursive: true });
  }

  archiveCase(supportCase: SupportCase): void {
    const caseDir = path.join(this.baseDir, supportCase.id);
    fs.mkdirSync(caseDir, { recursive: true });

    const caseJsonPath = path.join(caseDir, 'case.json');
    const transcriptPath = path.join(caseDir, 'timeline.md');

    fs.writeFileSync(caseJsonPath, `${JSON.stringify(supportCase, null, 2)}\n`, 'utf8');
    fs.writeFileSync(transcriptPath, renderTranscript(supportCase), 'utf8');
  }
}

function renderTranscript(supportCase: SupportCase): string {
  const lines: string[] = [
    `# Case ${supportCase.id}`,
    '',
    `- Customer: ${supportCase.customerName}`,
    `- Status: ${supportCase.status}`,
    `- Category: ${supportCase.category}`,
    `- Strategy: ${supportCase.strategy}`,
    `- Created: ${supportCase.createdAt}`,
    `- Updated: ${supportCase.updatedAt}`,
  ];

  if (supportCase.orderId) {
    lines.push(`- Order ID: ${supportCase.orderId}`);
  }

  if (supportCase.attachmentPaths.length > 0) {
    lines.push(`- Attachments: ${supportCase.attachmentPaths.join(', ')}`);
  }

  if (supportCase.resolutionSummary) {
    lines.push(`- Resolution: ${supportCase.resolutionSummary}`);
  }

  if (supportCase.lastError) {
    lines.push(`- Last Error: ${supportCase.lastError}`);
  }

  lines.push('', '## Issue', '', supportCase.issue, '', '## Timeline', '');

  for (const event of supportCase.events) {
    lines.push(`- ${event.at} [${event.type}] ${event.message}`);
    if (event.meta && Object.keys(event.meta).length > 0) {
      lines.push(`  - meta: ${JSON.stringify(event.meta)}`);
    }
  }

  lines.push('');

  return lines.join('\n');
}
