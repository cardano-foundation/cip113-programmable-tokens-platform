/** Show the backend's issuance stage and recovery advice instead of raw JSON. */
export function keriIssueErrorMessage(error: unknown): string {
  if (!(error instanceof Error)) return 'Failed to issue credential';
  if (error.message === 'Request timeout') {
    return 'The request timed out. Issuance may have completed; check Veridian before trying to issue again.';
  }
  try {
    const body: unknown = JSON.parse(error.message);
    if (body && typeof body === 'object' && 'error' in body &&
        typeof body.error === 'string' && body.error.trim()) {
      return body.error;
    }
  } catch {
    // The API also returns plain-text errors.
  }
  return error.message;
}
