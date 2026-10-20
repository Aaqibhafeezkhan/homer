package homer.app.infra

import java.security.MessageDigest

/**
 * Constant-time comparison of an expected and provided bearer token.
 *
 * Plain `==` on Strings short-circuits at the first mismatching char,
 * so the wall-clock time to reject a wrong token leaks the length of
 * the matching prefix. [MessageDigest.isEqual] is documented to run in
 * time independent of input contents (and is safe to call on byte
 * arrays of unequal length), which closes that side channel.
 */
internal fun matchesTokenConstantTime(
    expected: String,
    provided: String,
): Boolean =
    MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        provided.toByteArray(Charsets.UTF_8),
    )
