package rw.gov.risa.sso.wrapper.policy.accessmatrix;

/** A clamped page: 1-based {@code page}, {@code size} in [1, 200], 0-based {@code start} offset. */
public record PageBounds(int page, int size, int start) {
}
