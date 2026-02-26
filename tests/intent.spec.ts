import { describe, expect, it } from 'vitest';
import { inferCategoryAndStrategy } from '../src/services/intent.js';

describe('inferCategoryAndStrategy', () => {
  it('maps billing keywords to refund strategy', () => {
    const result = inferCategoryAndStrategy('I was double charged on my invoice');
    expect(result).toEqual({ category: 'billing', strategy: 'refund' });
  });

  it('maps shipping issues to human strategy', () => {
    const result = inferCategoryAndStrategy('My delivery is late and not received');
    expect(result).toEqual({ category: 'shipping', strategy: 'human' });
  });

  it('prioritizes damaged classification over refund keyword', () => {
    const result = inferCategoryAndStrategy('My package is damaged and I need a refund');
    expect(result).toEqual({ category: 'damaged', strategy: 'refund' });
  });

  it('falls back to unknown category', () => {
    const result = inferCategoryAndStrategy('Need help');
    expect(result).toEqual({ category: 'unknown', strategy: 'human' });
  });
});
