import { describe, it, expect } from 'vitest';
import { filterSensitiveData } from '../src/services/sensitive-filter.js';
import type { WidgetState } from '../src/services/claude-client.js';

function makeState(messages: Array<{ sender: 'user' | 'agent'; text: string }>): WidgetState {
  return {
    provider: 'test',
    timestamp: Date.now(),
    messages: messages.map((m) => ({ ...m, timestamp: null })),
    buttons: [],
    inputField: { found: true, value: '', placeholder: '' },
    typingIndicator: false,
    url: 'https://example.com',
  };
}

describe('filterSensitiveData', () => {
  it('redacts SSN patterns', () => {
    const state = makeState([{ sender: 'agent', text: 'Your SSN is 123-45-6789' }]);
    const filtered = filterSensitiveData(state);
    expect(filtered.messages[0].text).toContain('[SSN REDACTED]');
    expect(filtered.messages[0].text).not.toContain('123-45-6789');
  });

  it('redacts credit card patterns', () => {
    const state = makeState([{ sender: 'user', text: 'Card: 4111-1111-1111-1111' }]);
    const filtered = filterSensitiveData(state);
    expect(filtered.messages[0].text).toContain('[CARD REDACTED]');
    expect(filtered.messages[0].text).not.toContain('4111');
  });

  it('redacts password patterns', () => {
    const state = makeState([{ sender: 'agent', text: 'password: myS3cret!' }]);
    const filtered = filterSensitiveData(state);
    expect(filtered.messages[0].text).toContain('[PASSWORD REDACTED]');
    expect(filtered.messages[0].text).not.toContain('myS3cret');
  });

  it('partially redacts email', () => {
    const state = makeState([{ sender: 'agent', text: 'Email: john@example.com' }]);
    const filtered = filterSensitiveData(state);
    expect(filtered.messages[0].text).toContain('[EMAIL ***]');
    expect(filtered.messages[0].text).not.toContain('john@example.com');
  });

  it('preserves non-sensitive text', () => {
    const state = makeState([{ sender: 'user', text: 'My order was damaged, I need a refund' }]);
    const filtered = filterSensitiveData(state);
    expect(filtered.messages[0].text).toBe('My order was damaged, I need a refund');
  });

  it('filters input field value', () => {
    const state = makeState([]);
    state.inputField.value = 'My SSN is 123-45-6789';
    const filtered = filterSensitiveData(state);
    expect(filtered.inputField.value).toContain('[SSN REDACTED]');
  });
});
