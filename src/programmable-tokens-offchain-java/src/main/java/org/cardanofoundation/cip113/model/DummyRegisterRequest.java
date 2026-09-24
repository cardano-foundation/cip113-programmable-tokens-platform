package org.cardanofoundation.cip113.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Registration request for the "dummy" module.
 * This is a simple reference implementation with no additional fields.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class DummyRegisterRequest extends RegisterTokenRequest {
    // No additional fields needed for dummy module
}
