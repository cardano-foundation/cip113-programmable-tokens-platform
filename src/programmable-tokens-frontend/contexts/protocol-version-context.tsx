"use client";

import React, { createContext, useContext, useState, useEffect, useCallback, ReactNode } from 'react';
import { ProtocolVersionInfo } from '@/types/api';
import { getProtocolVersions } from '@/lib/api';

interface ProtocolVersionContextType {
  /** Declare that this consumer needs protocol versions. Idempotent; see the provider. */
  request: () => void;
  versions: ProtocolVersionInfo[];
  selectedVersion: ProtocolVersionInfo | null;
  isLoading: boolean;
  error: string | null;
  selectVersion: (txHash: string) => void;
  resetToDefault: () => void;
}

const ProtocolVersionContext = createContext<ProtocolVersionContextType | undefined>(undefined);

const STORAGE_KEY = 'selectedProtocolVersion';

export function ProtocolVersionProvider({ children }: { children: ReactNode }) {
  /**
   * Nothing is fetched until a component actually asks for a protocol version.
   *
   * ⛔ THIS PROVIDER IS MOUNTED BY THE ROOT LAYOUT, SO IT WRAPS EVERY ROUTE. Fetching on
   * mount meant every page made a backend call whether or not it had any use for one —
   * including `/sign`, which is the page a ceremony participant opens on a machine that may
   * have no route to this backend at all. There the call cannot succeed, and the page a
   * participant was told is self-contained visibly errors while they are deciding whether to
   * trust a transaction.
   *
   * Subscribing on first use rather than special-casing one route: any page that needs a
   * version still gets one, and any page that does not makes no request. See T-061.
   */
  const [wanted, setWanted] = useState(false);
  // Stable identity: consumers put this in an effect's dependency list.
  const request = useCallback(() => setWanted(true), []);
  const [versions, setVersions] = useState<ProtocolVersionInfo[]>([]);
  const [selectedTxHash, setSelectedTxHash] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Load versions from API and initialize selection — only once something has asked.
  useEffect(() => {
    if (!wanted) return;
    async function loadVersions() {
      try {
        setIsLoading(true);
        setError(null);
        const data = await getProtocolVersions();
        console.log('Loaded protocol versions:', data);
        setVersions(data);

        // Check localStorage first
        const saved = localStorage.getItem(STORAGE_KEY);
        console.log('Saved protocol version from localStorage:', saved);

        if (saved) {
          // Verify the saved version exists in the loaded data
          const savedVersion = data.find(v => v.txHash === saved);
          if (savedVersion) {
            console.log('Using saved protocol version:', savedVersion.txHash);
            setSelectedTxHash(saved);
            return;
          } else {
            console.log('Saved version not found in API response, clearing localStorage');
            localStorage.removeItem(STORAGE_KEY);
          }
        }

        // No valid saved version, use default or first available
        const defaultVersion = data.find(v => v.default);
        console.log('Default protocol version:', defaultVersion);

        if (defaultVersion) {
          console.log('Using default protocol version:', defaultVersion.txHash);
          setSelectedTxHash(defaultVersion.txHash);
        } else {
          console.log('No default version found, using first available');
          if (data.length > 0) {
            console.log('Using first protocol version:', data[0].txHash);
            setSelectedTxHash(data[0].txHash);
          }
        }
      } catch (err) {
        console.error('Failed to load protocol versions:', err);
        setError('Failed to load protocol versions');
      } finally {
        setIsLoading(false);
      }
    }

    loadVersions();
  }, [wanted]);

  // Save to localStorage when changed
  useEffect(() => {
    if (selectedTxHash) {
      localStorage.setItem(STORAGE_KEY, selectedTxHash);
    }
  }, [selectedTxHash]);

  const selectedVersion = selectedTxHash
    ? versions.find(v => v.txHash === selectedTxHash) || null
    : null;

  const selectVersion = (txHash: string) => {
    setSelectedTxHash(txHash);
  };

  const resetToDefault = () => {
    console.log('resetToDefault called');
    console.log('Available versions:', versions);
    const defaultVersion = versions.find(v => v.default);
    console.log('Found default version:', defaultVersion);
    if (defaultVersion) {
      console.log('Resetting to default version:', defaultVersion.txHash);
      setSelectedTxHash(defaultVersion.txHash);
    } else {
      console.warn('No default version found to reset to');
    }
  };

  const value: ProtocolVersionContextType = {
    versions,
    selectedVersion,
    request,
    isLoading,
    error,
    selectVersion,
    resetToDefault,
  };

  return (
    <ProtocolVersionContext.Provider value={value}>
      {children}
    </ProtocolVersionContext.Provider>
  );
}

export function useProtocolVersion() {
  const context = useContext(ProtocolVersionContext);
  if (context === undefined) {
    throw new Error('useProtocolVersion must be used within a ProtocolVersionProvider');
  }
  // Declaring interest is what triggers the fetch. A component that calls this hook wants a
  // protocol version; one that never calls it costs no request. The effect runs once per
  // consumer mount and the provider's own guard makes repeats free.
  const { request } = context;
  useEffect(() => { request(); }, [request]);
  return context;
}
