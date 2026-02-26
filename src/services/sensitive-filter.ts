/**
 * Sensitive Data Filter
 *
 * Strips PII and sensitive patterns from widget state before sending to the LLM.
 * This is a defense-in-depth measure: we don't want to accidentally leak
 * sensitive data from the support chat to the Claude API.
 */

import type { WidgetState } from './claude-client.js';

// ─── Patterns ───────────────────────────────────────────────────────────────

const SSN_PATTERN = /\b\d{3}[-.\s]?\d{2}[-.\s]?\d{4}\b/g;
const CREDIT_CARD_PATTERN = /\b(?:\d{4}[-.\s]?){3}\d{4}\b/g;
const CVV_PATTERN = /\bcvv:?\s*\d{3,4}\b/gi;
const PHONE_PATTERN = /\b(?:\+?1[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}\b/g;
const EMAIL_PATTERN = /\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b/g;
const PASSWORD_PATTERN = /(?:password|passwd|pwd)\s*[:=]\s*\S+/gi;

interface FilterRule {
  pattern: RegExp;
  replacement: string;
  description: string;
}

const FILTER_RULES: FilterRule[] = [
  { pattern: SSN_PATTERN, replacement: '[SSN REDACTED]', description: 'SSN' },
  { pattern: CREDIT_CARD_PATTERN, replacement: '[CARD REDACTED]', description: 'Credit card' },
  { pattern: CVV_PATTERN, replacement: '[CVV REDACTED]', description: 'CVV' },
  { pattern: PASSWORD_PATTERN, replacement: '[PASSWORD REDACTED]', description: 'Password' },
];

/** Rules that redact but preserve partial info for the LLM to reference */
const PARTIAL_REDACT_RULES: FilterRule[] = [
  { pattern: PHONE_PATTERN, replacement: '[PHONE ***]', description: 'Phone' },
  { pattern: EMAIL_PATTERN, replacement: '[EMAIL ***]', description: 'Email' },
];

// ─── Filter function ────────────────────────────────────────────────────────

/**
 * Filter sensitive data from widget state.
 * Returns a new WidgetState with sensitive patterns redacted.
 */
export function filterSensitiveData(state: WidgetState): WidgetState {
  return {
    ...state,
    messages: state.messages.map((msg) => ({
      ...msg,
      text: filterText(msg.text),
    })),
    inputField: {
      ...state.inputField,
      value: filterText(state.inputField.value),
      placeholder: state.inputField.placeholder,
    },
    buttons: state.buttons.map((btn) => ({
      ...btn,
      label: filterText(btn.label),
    })),
  };
}

/**
 * Apply all filter rules to a text string.
 */
function filterText(text: string): string {
  let filtered = text;

  for (const rule of FILTER_RULES) {
    filtered = filtered.replace(rule.pattern, rule.replacement);
  }

  for (const rule of PARTIAL_REDACT_RULES) {
    filtered = filtered.replace(rule.pattern, rule.replacement);
  }

  return filtered;
}
