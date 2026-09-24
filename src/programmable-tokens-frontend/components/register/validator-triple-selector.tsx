"use client";

import { useState, useMemo } from 'react';
import { Select, SelectOption } from '@/components/ui/select';
import { Module } from '@/types/api';

interface ValidatorTripleSelectorProps {
  modules: Module[];
  onSelect: (
    moduleId: string,
    issueContract: string,
    transferContract: string,
    thirdPartyContract?: string
  ) => void;
  disabled?: boolean;
}

export function ValidatorTripleSelector({
  modules,
  onSelect,
  disabled = false,
}: ValidatorTripleSelectorProps) {
  const [selectedModule, setSelectedModule] = useState<string>('');
  const [selectedIssueContract, setSelectedIssueContract] = useState<string>('');
  const [selectedTransferContract, setSelectedTransferContract] = useState<string>('');
  const [selectedThirdPartyContract, setSelectedThirdPartyContract] = useState<string>('');

  // Get validator options for selected module
  const validatorOptions: SelectOption[] = useMemo(() => {
    if (!selectedModule) return [];

    const selectedDefinition = modules.find(s => s.id === selectedModule);
    return selectedDefinition?.validators.map(v => ({
      value: v.title,
      label: v.title,
    })) || [];
  }, [selectedModule, modules]);

  const thirdPartyOptions: SelectOption[] = useMemo(() => {
    return [
      { value: '', label: '-- None (Optional) --' },
      ...validatorOptions
    ];
  }, [validatorOptions]);

  const handleModuleChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const moduleId = e.target.value;
    setSelectedModule(moduleId);
    // Reset all validator selections when module changes
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
        selectedModule,
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
        selectedModule,
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
        selectedModule,
        selectedIssueContract,
        selectedTransferContract,
        contract || undefined
      );
    }
  };

  const moduleOptions: SelectOption[] = [
    { value: '', label: '-- Select a module --' },
    ...modules.map(s => ({
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
      {/* Step 1: Module Selection */}
      <Select
        label="Step 1: Validation Logic (Module)"
        options={moduleOptions}
        value={selectedModule}
        onChange={handleModuleChange}
        disabled={disabled || modules.length === 0}
        helperText="Choose the validation rules for your programmable token"
      />

      {/* Step 2: Issue Contract Selection */}
      {selectedModule && validatorOptions.length > 0 && (
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
      {selectedModule && selectedIssueContract && (
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
      {selectedModule && selectedIssueContract && selectedTransferContract && (
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
