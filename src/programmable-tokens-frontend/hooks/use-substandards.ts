"use client";

import { useState, useEffect } from 'react';
import { Substandard } from '@/types/api';
import { getSubstandards } from '@/lib/api';

export function useSubstandards() {
  const [substandards, setSubstandards] = useState<Substandard[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    async function fetchSubstandards() {
      try {
        setIsLoading(true);
        setError(null);
        const data = await getSubstandards();
        setSubstandards(data);
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load substandards');
      } finally {
        setIsLoading(false);
      }
    }

    fetchSubstandards();
  }, []);

  return {
    substandards,
    isLoading,
    error,
    refetch: () => {
      setIsLoading(true);
      return getSubstandards()
        .then(setSubstandards)
        .catch(err => setError(err.message))
        .finally(() => setIsLoading(false));
    },
  };
}
