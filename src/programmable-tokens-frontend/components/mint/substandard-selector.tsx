"use client";

import { useState, useEffect, useMemo } from 'react';
import { Select, SelectOption } from '@/components/ui/select';
import { Substandard } from '@/types/api';

interface SubstandardSelectorProps {
  substandards: Substandard[];
  onSelect: (substandardId: string, validatorTitle: string) => void;
  disabled?: boolean;
  initialSubstandard?: string;
  initialValidator?: string;
}

export function SubstandardSelector({
  substandards,
  onSelect,
  disabled = false,
  initialSubstandard = '',
  initialValidator = '',
}: SubstandardSelectorProps) {
  const [selectedSubstandard, setSelectedSubstandard] = useState<string>(initialSubstandard);
  const [selectedValidator, setSelectedValidator] = useState<string>(initialValidator);

  // Get validator options for selected substandard (memoized to prevent unnecessary recalculations)
  const validatorOptions: SelectOption[] = useMemo(() => {
    if (!selectedSubstandard) return [];

    const substandard = substandards.find(s => s.id === selectedSubstandard);
    return substandard?.validators.map(v => ({
      value: v.title,
      label: v.title,
    })) || [];
  }, [selectedSubstandard, substandards]);

  // Auto-notify parent if both initial values are provided
  useEffect(() => {
    if (initialSubstandard && initialValidator) {
      onSelect(initialSubstandard, initialValidator);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialSubstandard, initialValidator]);

  const handleSubstandardChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const substandardId = e.target.value;
    setSelectedSubstandard(substandardId);
    setSelectedValidator(''); // Reset validator selection when substandard changes
  };

  const handleValidatorChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const validatorTitle = e.target.value;
    setSelectedValidator(validatorTitle);
    // Only call onSelect when user has selected both substandard and validator
    if (selectedSubstandard && validatorTitle) {
      onSelect(selectedSubstandard, validatorTitle);
    }
  };

  const substandardOptions: SelectOption[] = [
    { value: '', label: '-- Select a substandard --' },
    ...substandards.map(s => ({
      value: s.id,
      label: s.id.charAt(0).toUpperCase() + s.id.slice(1),
    }))
  ];

  const validatorOptionsWithPlaceholder: SelectOption[] = [
    { value: '', label: '-- Select a validator --' },
    ...validatorOptions
  ];

  return (
    <div className="space-y-4">
      <Select
        label="Step 1: Validation Logic (Substandard)"
        options={substandardOptions}
        value={selectedSubstandard}
        onChange={handleSubstandardChange}
        disabled={disabled || substandards.length === 0}
        helperText="Choose the validation rules for your token (e.g., dummy, regulated, etc.)"
      />

      {selectedSubstandard && validatorOptions.length > 0 && (
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
