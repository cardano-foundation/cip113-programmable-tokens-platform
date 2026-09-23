"use client";

import { useState, useEffect, useMemo } from 'react';
import { Select, SelectOption } from '@/components/ui/select';
import { Module } from '@/types/api';

interface ModuleSelectorProps {
  modules: Module[];
  onSelect: (moduleId: string, validatorTitle: string) => void;
  disabled?: boolean;
  initialModule?: string;
  initialValidator?: string;
}

export function ModuleSelector({
  modules,
  onSelect,
  disabled = false,
  initialModule = '',
  initialValidator = '',
}: ModuleSelectorProps) {
  const [selectedModule, setSelectedModule] = useState<string>(initialModule);
  const [selectedValidator, setSelectedValidator] = useState<string>(initialValidator);

  // Get validator options for selected module (memoized to prevent unnecessary recalculations)
  const validatorOptions: SelectOption[] = useMemo(() => {
    if (!selectedModule) return [];

    const selectedDefinition = modules.find(s => s.id === selectedModule);
    return selectedDefinition?.validators.map(v => ({
      value: v.title,
      label: v.title,
    })) || [];
  }, [selectedModule, modules]);

  // Auto-notify parent if both initial values are provided
  useEffect(() => {
    if (initialModule && initialValidator) {
      onSelect(initialModule, initialValidator);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialModule, initialValidator]);

  const handleModuleChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const moduleId = e.target.value;
    setSelectedModule(moduleId);
    setSelectedValidator(''); // Reset validator selection when module changes
  };

  const handleValidatorChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const validatorTitle = e.target.value;
    setSelectedValidator(validatorTitle);
    // Only call onSelect when user has selected both module and validator
    if (selectedModule && validatorTitle) {
      onSelect(selectedModule, validatorTitle);
    }
  };

  const moduleOptions: SelectOption[] = [
    { value: '', label: '-- Select a module --' },
    ...modules.map(s => ({
      value: s.id,
      label: s.name || s.id.charAt(0).toUpperCase() + s.id.slice(1),
    }))
  ];

  const selectedDescription = modules.find(s => s.id === selectedModule)?.description;

  const validatorOptionsWithPlaceholder: SelectOption[] = [
    { value: '', label: '-- Select a validator --' },
    ...validatorOptions
  ];

  return (
    <div className="space-y-4">
      <Select
        label="Step 1: Validation Logic (Module)"
        options={moduleOptions}
        value={selectedModule}
        onChange={handleModuleChange}
        disabled={disabled || modules.length === 0}
        helperText="Choose the validation rules for your token"
      />

      {selectedDescription && (
        <p className="text-sm text-gray-600 -mt-2">{selectedDescription}</p>
      )}

      {selectedModule && validatorOptions.length > 0 && (
        <Select
          label="Step 2: Validator Script"
          options={validatorOptionsWithPlaceholder}
          value={selectedValidator}
          onChange={handleValidatorChange}
          disabled={disabled}
          helperText="Select which validator contract to use for minting"
        />
      )}
    </div>
  );
}
