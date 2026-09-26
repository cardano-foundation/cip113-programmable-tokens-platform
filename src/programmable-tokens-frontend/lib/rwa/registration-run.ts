let generation = 0;

export function currentRegistrationRun(): number { return generation; }

export function resetRegistrationRun(): void { generation += 1; }

export function assertRegistrationRun(expected: number): void {
  if (generation !== expected)
    throw new Error('This registration was reset. Start a new attempt.');
}
