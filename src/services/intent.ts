import type { IssueCategory, Strategy } from '../domain/types.js';

interface IntentResult {
  category: IssueCategory;
  strategy: Strategy;
}

export function inferCategoryAndStrategy(issueText: string): IntentResult {
  const text = issueText.toLowerCase();

  if (matches(text, ['broken', 'damaged', 'cracked', 'defect'])) {
    return { category: 'damaged', strategy: 'refund' };
  }

  if (matches(text, ['late', 'not received', 'delivery', 'shipping'])) {
    return { category: 'shipping', strategy: 'human' };
  }

  if (matches(text, ['refund', 'charged', 'billing', 'invoice', 'double charge'])) {
    return { category: 'billing', strategy: 'refund' };
  }

  if (matches(text, ['login', 'password', 'account', 'locked'])) {
    return { category: 'account', strategy: 'human' };
  }

  return { category: 'unknown', strategy: 'human' };
}

function matches(text: string, candidates: string[]): boolean {
  return candidates.some((candidate) => text.includes(candidate));
}
