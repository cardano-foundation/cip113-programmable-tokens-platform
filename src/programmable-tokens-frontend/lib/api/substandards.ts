/**
 * Substandards API
 */

import { SubstandardsResponse } from '@/types/api';
import { apiGet } from './client';

/**
 * Fetch available substandards from backend
 */
export async function getSubstandards(): Promise<SubstandardsResponse> {
  return apiGet<SubstandardsResponse>('/substandards');
}

/**
 * Get validator titles for a specific substandard
 */
export function getValidatorTitles(substandardId: string, substandards: SubstandardsResponse): string[] {
  const substandard = substandards.find(s => s.id === substandardId);
  if (!substandard) return [];

  return substandard.validators.map(v => v.title);
}

/**
 * Check if a validator exists in a substandard
 */
export function hasValidator(
  substandardId: string,
  validatorTitle: string,
  substandards: SubstandardsResponse
): boolean {
  const titles = getValidatorTitles(substandardId, substandards);
  return titles.includes(validatorTitle);
}
