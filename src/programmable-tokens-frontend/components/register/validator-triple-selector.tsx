"use client";

import { useState, useMemo } from 'react';
import { Select, SelectOption } from '@/components/ui/select';
import { Substandard } from '@/types/api';

interface ValidatorTripleSelectorProps {
  substandards: Substandard[];
  onSelect: (
    substandardId: string,
    issueContract: string,
    transferContract: string,
    thirdPartyContract?: string
  ) => void;
  disabled?: boolean;
}

export function ValidatorTripleSelector({
  substandards,
  onSelect,
  disabled = false,
}: ValidatorTripleSelectorProps) {
  const [selectedSubstandard, setSelectedSubstandard] = useState<string>('');
  const [selectedIssueContract, setSelectedIssueContract] = useState<string>('');
  const [selectedTransferContract, setSelectedTransferContract] = useState<string>('');
  const [selectedThirdPartyContract, setSelectedThirdPartyContract] = useState<string>('');

  // Get validator options for selected substandard
  const validatorOptions: SelectOption[] = useMemo(() => {
    if (!selectedSubstandard) return [];

    const substandard = substandards.find(s => s.id === selectedSubstandard);
    return substandard?.validators.map(v => ({
      value: v.title,
      label: v.title,
    })) || [];
  }, [selectedSubstandard, substandards]);

  const thirdPartyOptions: SelectOption[] = useMemo(() => {
    return [
      { value: '', label: '-- None (Optional) --' },
      ...validatorOptions
    ];
  }, [validatorOptions]);

  const handleSubstandardChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const substandardId = e.target.value;
    setSelectedSubstandard(substandardId);
    // Reset all validator selections when substandard changes
    setSelectedIssueContract('');
    setSelectedTransferContract('');
    setSelectedThirdPartyContract('');
  };

  const handleIssueContractChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const contract = e.target.value;
    setSelectedIssueContract(contract);
    // Notify parent if both required contracts are selected
    if (contract && selectedTransferContract) {
      onSelect(
        selectedSubstandard,
        contract,
        selectedTransferContract,
        selectedThirdPartyContract || undefined
      );
    }
  };

  const handleTransferContractChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const contract = e.target.value;
    setSelectedTransferContract(contract);
    // Notify parent if both required contracts are selected
    if (selectedIssueContract && contract) {
      onSelect(
        selectedSubstandard,
        selectedIssueContract,
        contract,
        selectedThirdPartyContract || undefined
      );
    }
  };

  const handleThirdPartyContractChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const contract = e.target.value;
    setSelectedThirdPartyContract(contract);
    // Notify parent if both required contracts are already selected
    if (selectedIssueContract && selectedTransferContract) {
      onSelect(
        selectedSubstandard,
        selectedIssueContract,
        selectedTransferContract,
        contract || undefined
      );
    }
  };

  const substandardOptions: SelectOption[] = [
    { value: '', label: '-- Select a substandard --' },
    ...substandards.map(s => ({
      value: s.id,
      label: s.id.charAt(0).toUpperCase() + s.id.slice(1),
    }))
  ];

  const issueContractOptions: SelectOption[] = [
    { value: '', label: '-- Select issue contract --' },
    ...validatorOptions
  ];

  const transferContractOptions: SelectOption[] = [
    { value: '', label: '-- Select transfer contract --' },
    ...validatorOptions
  ];

  return (
    <div className="space-y-4">
      {/* Step 1: Substandard Selection */}
      <Select
        label="Step 1: Validation Logic (Substandard)"
        options={substandardOptions}
        value={selectedSubstandard}
        onChange={handleSubstandardChange}
        disabled={disabled || substandards.length === 0}
        helperText="Choose the validation rules for your programmable token"
      />

      {/* Step 2: Issue Contract Selection */}
      {selectedSubstandard && validatorOptions.length > 0 && (
        <Select
          label="Step 2: Issue Contract (Required)"
          options={issueContractOptions}
          value={selectedIssueContract}
          onChange={handleIssueContractChange}
          disabled={disabled}
          helperText="Contract used for minting new tokens"
        />
      )}

      {/* Step 3: Transfer Contract Selection */}
      {selectedSubstandard && selectedIssueContract && (
        <Select
          label="Step 3: Transfer Contract (Required)"
          options={transferContractOptions}
          value={selectedTransferContract}
          onChange={handleTransferContractChange}
          disabled={disabled}
          helperText="Contract used for transferring tokens"
        />
      )}

      {/* Step 4: Third-Party Contract Selection (Optional) */}
      {selectedSubstandard && selectedIssueContract && selectedTransferContract && (
        <Select
          label="Step 4: Third-Party Contract (Optional)"
          options={thirdPartyOptions}
          value={selectedThirdPartyContract}
          onChange={handleThirdPartyContractChange}
          disabled={disabled}
          helperText="Additional validation logic (optional)"
        />
      )}
    </div>
  );
}
