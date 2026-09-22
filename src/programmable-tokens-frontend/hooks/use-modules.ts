"use client";

import { useState, useEffect } from 'react';
import { Module } from '@/types/api';
import { getModules } from '@/lib/api';

export function useModules() {
  const [modules, setModules] = useState<Module[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    async function fetchModules() {
      try {
        setIsLoading(true);
        setError(null);
        const data = await getModules();
        setModules(data);
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load modules');
      } finally {
        setIsLoading(false);
      }
    }

    fetchModules();
  }, []);

  return {
    modules,
    isLoading,
    error,
    refetch: () => {
      setIsLoading(true);
      return getModules()
        .then(setModules)
        .catch(err => setError(err.message))
        .finally(() => setIsLoading(false));
    },
  };
}
