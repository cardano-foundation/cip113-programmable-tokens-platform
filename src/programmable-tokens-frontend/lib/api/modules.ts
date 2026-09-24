/**
 * Modules API
 */

import { ModulesResponse } from '@/types/api';
import { apiGet } from './client';

/**
 * Fetch available modules from backend
 */
export async function getModules(): Promise<ModulesResponse> {
  return apiGet<ModulesResponse>('/modules');
}

/**
 * Get validator titles for a specific module
 */
export function getValidatorTitles(moduleId: string, modules: ModulesResponse): string[] {
  const selectedDefinition = modules.find(s => s.id === moduleId);
  if (!selectedDefinition) return [];

  return selectedDefinition.validators.map(v => v.title);
}

/**
 * Check if a validator exists in a module
 */
export function hasValidator(
  moduleId: string,
  validatorTitle: string,
  modules: ModulesResponse
): boolean {
  const titles = getValidatorTitles(moduleId, modules);
  return titles.includes(validatorTitle);
}
