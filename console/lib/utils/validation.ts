import type { AgentQueryParam } from '@/types/homer';

export interface ValidationResult {
  isValid: boolean;
  error?: string;
}

const INTEGER = /^-?\d+$/;

// Per-type pre-checks of a non-empty value. Only the legacy `STRING` type
// rejects spaces; `string` and `ANY` accept any non-empty value, so they have none.
const TYPE_CHECKS = new Map<AgentQueryParam['type'], { valid: (value: string) => boolean; error: string }>([
  ['STRING', { valid: (value) => !value.includes(' '), error: 'String values cannot contain spaces' }],
  ['NUMBER', { valid: (value) => INTEGER.test(value), error: 'Must be a valid integer' }],
  ['integer', { valid: (value) => INTEGER.test(value.trim()), error: 'Must be a valid integer' }],
  ['number', { valid: (value) => Number.isFinite(Number(value)), error: 'Must be a valid number' }],
  ['boolean', { valid: (value) => isBooleanLiteral(value), error: "Must be 'true' or 'false'" }],
]);

/** The type-specific error for a non-empty value, or `null` when it passes. */
function typeError(type: AgentQueryParam['type'], value: string): string | null {
  const check = TYPE_CHECKS.get(type);
  return check && !check.valid(value) ? check.error : null;
}

function isBooleanLiteral(value: string): boolean {
  const normalized = value.trim().toLowerCase();
  return normalized === 'true' || normalized === 'false';
}

/**
 * Validates a single parameter value based on its type and transformer
 */
export function validateParameterValue(
  param: AgentQueryParam,
  value: string
): ValidationResult {
  // Required field validation
  if (param.required && !value.trim()) {
    return {
      isValid: false,
      error: 'This field is required',
    };
  }

  // Skip further validation if empty and not required
  if (!value.trim()) {
    return { isValid: true };
  }

  // JSONPath transformer requires valid JSON
  if (param.transformer?.type === 'jsonPath') {
    try {
      JSON.parse(value);
      // Skip type validation - the backend will validate the extracted value
      return { isValid: true };
    } catch {
      return {
        isValid: false,
        error: 'Must be valid JSON (JSONPath transformer requires JSON input)',
      };
    }
  }

  // Type-specific validation: a best-effort pre-check; the server's 400 is
  // authoritative. Constraint fields (values/pattern/min/...) are checked
  // server-side only.
  const error = typeError(param.type, value);
  if (error) {
    return { isValid: false, error };
  }

  return { isValid: true };
}

/**
 * Validates all parameters and returns a map of validation errors
 */
export function validateAllParameters(
  params: AgentQueryParam[],
  values: Record<string, string>
): Record<string, string> {
  const errors: Record<string, string> = {};

  params.forEach((param) => {
    const value = values[param.paramName] || '';
    const result = validateParameterValue(param, value);

    if (!result.isValid && result.error) {
      errors[param.paramName] = result.error;
    }
  });

  return errors;
}

/**
 * Checks if the form is valid and can be submitted
 */
export function isFormValid(
  params: AgentQueryParam[],
  values: Record<string, string>,
  errors: Record<string, string>
): boolean {
  // Check if there are any validation errors
  if (Object.keys(errors).length > 0) {
    return false;
  }

  // Check if all required fields have values
  const allRequiredFieldsFilled = params.every((param) => {
    if (!param.required) return true;
    const value = values[param.paramName] || '';
    return value.trim().length > 0;
  });

  return allRequiredFieldsFilled;
}
