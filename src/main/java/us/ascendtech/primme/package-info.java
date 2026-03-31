/**
 * Java FFM (Foreign Function &amp; Memory) bindings for the
 * <a href="https://github.com/primme/primme">PRIMME</a> library —
 * PReconditioned Iterative MultiMethod Eigensolver.
 *
 * <p>This package provides:
 * <ul>
 *   <li>{@link us.ascendtech.primme.PrimmeEigs} — eigenvalue solver</li>
 *   <li>{@link us.ascendtech.primme.PrimmeSvds} — singular value decomposition solver</li>
 * </ul>
 *
 * <p>The native PRIMME library is loaded from the JAR at runtime.
 * If no bundled library matches the current platform, the loader
 * falls back to {@code System.loadLibrary("primme")}.
 */
package us.ascendtech.primme;
