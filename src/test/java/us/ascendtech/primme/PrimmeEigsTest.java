package us.ascendtech.primme;

import java.lang.foreign.ValueLayout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PrimmeEigs}.
 * Skipped automatically when the PRIMME native library is not on the classpath.
 */
@NativeAvailable
class PrimmeEigsTest {

    /**
     * Computes eigenvalues of a diagonal matrix diag(1, 2, ..., n).
     * The smallest eigenvalues should be 1, 2, 3.
     */
    @Test
    void solvesDiagonalMatrix() {
        int n = 100;
        int numEvals = 3;

        try (var eigs = PrimmeEigs.create(n, numEvals, (x, ldx, y, ldy, blockSize) -> {
            // y = diag(1..n) * x
            for (int b = 0; b < blockSize; b++) {
                for (int i = 0; i < n; i++) {
                    double xi = x.getAtIndex(ValueLayout.JAVA_DOUBLE, b * ldx + i);
                    y.setAtIndex(ValueLayout.JAVA_DOUBLE, b * ldy + i, (i + 1) * xi);
                }
            }
        })) {
            eigs.setMethod(PrimmeEigs.Method.DEFAULT_MIN_MATVECS)
                .setTarget(PrimmeEigs.Target.SMALLEST)
                .setEps(1e-12)
                .setPrintLevel(0);

            PrimmeEigs.Result result = eigs.solve();

            assertEquals(numEvals, result.evals().length);
            assertEquals(1.0, result.evals()[0], 1e-10);
            assertEquals(2.0, result.evals()[1], 1e-10);
            assertEquals(3.0, result.evals()[2], 1e-10);

            for (double rn : result.resNorms()) {
                assertTrue(rn < 1e-10, "Residual norm too large: " + rn);
            }
        }
    }

    /**
     * Computes the largest eigenvalues of a diagonal matrix.
     */
    @Test
    void solvesLargestEigenvalues() {
        int n = 100;
        int numEvals = 2;

        try (var eigs = PrimmeEigs.create(n, numEvals, (x, ldx, y, ldy, blockSize) -> {
            for (int b = 0; b < blockSize; b++) {
                for (int i = 0; i < n; i++) {
                    double xi = x.getAtIndex(ValueLayout.JAVA_DOUBLE, b * ldx + i);
                    y.setAtIndex(ValueLayout.JAVA_DOUBLE, b * ldy + i, (i + 1) * xi);
                }
            }
        })) {
            eigs.setMethod(PrimmeEigs.Method.DEFAULT)
                .setTarget(PrimmeEigs.Target.LARGEST)
                .setEps(1e-12)
                .setPrintLevel(0);

            PrimmeEigs.Result result = eigs.solve();

            assertEquals(numEvals, result.evals().length);
            assertEquals(100.0, result.evals()[0], 1e-10);
            assertEquals(99.0, result.evals()[1], 1e-10);
        }
    }
}
