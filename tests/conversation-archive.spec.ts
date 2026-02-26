import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { ConversationArchive } from '../src/services/conversation-archive.js';
import type { SupportCase } from '../src/domain/types.js';

function sampleCase(): SupportCase {
  const now = new Date().toISOString();
  return {
    id: 'case-abc',
    customerName: 'Test User',
    issue: 'Package damaged and need refund',
    orderId: 'ORD-1',
    attachmentPaths: ['fixtures/damaged-package.txt'],
    category: 'damaged',
    strategy: 'refund',
    status: 'completed',
    createdAt: now,
    updatedAt: now,
    events: [
      { at: now, type: 'case_created', message: 'Case created' },
      {
        at: now,
        type: 'automation_step',
        message: 'Submitting complaint request',
        meta: { provider: 'amazon' },
      },
    ],
    resolutionSummary: 'Refund approved',
  };
}

describe('ConversationArchive', () => {
  it('writes case snapshot and timeline markdown', () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cs-support-archive-'));
    const archive = new ConversationArchive(tempDir);

    archive.archiveCase(sampleCase());

    const casePath = path.join(tempDir, 'case-abc', 'case.json');
    const timelinePath = path.join(tempDir, 'case-abc', 'timeline.md');

    expect(fs.existsSync(casePath)).toBe(true);
    expect(fs.existsSync(timelinePath)).toBe(true);

    const parsed = JSON.parse(fs.readFileSync(casePath, 'utf8')) as SupportCase;
    expect(parsed.id).toBe('case-abc');

    const timeline = fs.readFileSync(timelinePath, 'utf8');
    expect(timeline).toContain('# Case case-abc');
    expect(timeline).toContain('Submitting complaint request');
    expect(timeline).toContain('meta: {"provider":"amazon"}');
  });
});
