package us.ascendtech.primme;

import java.lang.foreign.ValueLayout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PrimmeSvds}.
 * Skipped automatically when the PRIMME native library is not on the classpath.
 */
@NativeAvailable
class PrimmeSvdsTest {

    /**
     * Computes singular values of a diagonal matrix diag(1, 2, ..., n).
     * The largest singular values should be n, n-1, n-2.
     */
    @Test
    void solvesDiagonalMatrix() {
        int m = 100;
        int n = 100;
        int numSvals = 3;

        try (var svds = PrimmeSvds.create(m, n, numSvals, (x, ldx, y, ldy, blockSize, transpose) -> {
            // A = diag(1..n), so A*x = diag(1..n)*x and A'*x = same
            int rows = (transpose == 0) ? m : n;
            for (int b = 0; b < blockSize; b++) {
                for (int i = 0; i < rows; i++) {
                    double xi = x.getAtIndex(ValueLayout.JAVA_DOUBLE, b * ldx + i);
                    y.setAtIndex(ValueLayout.JAVA_DOUBLE, b * ldy + i, (i + 1) * xi);
                }
            }
        })) {
            svds.setMethod(PrimmeSvds.Method.DEFAULT)
                .setTarget(PrimmeSvds.Target.LARGEST)
                .setEps(1e-10)
                .setPrintLevel(0);

            PrimmeSvds.Result result = svds.solve();

            assertEquals(numSvals, result.svals().length);
            assertEquals(100.0, result.svals()[0], 1e-6);
            assertEquals(99.0, result.svals()[1], 1e-6);
            assertEquals(98.0, result.svals()[2], 1e-6);

            for (double rn : result.resNorms()) {
                assertTrue(rn < 1e-6, "Residual norm too large: " + rn);
            }
        }
    }

    /**
     * Computes smallest singular values.
     */
    @Test
    void solvesSmallestSingularValues() {
        int m = 50;
        int n = 50;
        int numSvals = 2;

        try (var svds = PrimmeSvds.create(m, n, numSvals, (x, ldx, y, ldy, blockSize, transpose) -> {
            int rows = (transpose == 0) ? m : n;
            for (int b = 0; b < blockSize; b++) {
                for (int i = 0; i < rows; i++) {
                    double xi = x.getAtIndex(ValueLayout.JAVA_DOUBLE, b * ldx + i);
                    y.setAtIndex(ValueLayout.JAVA_DOUBLE, b * ldy + i, (i + 1) * xi);
                }
            }
        })) {
            svds.setMethod(PrimmeSvds.Method.NORMAL_EQUATIONS)
                .setTarget(PrimmeSvds.Target.SMALLEST)
                .setEps(1e-10)
                .setMaxBasisSize(30)
                .setMaxMatvecs(100000)
                .setPrintLevel(0);

            PrimmeSvds.Result result = svds.solve();

            assertEquals(numSvals, result.svals().length);
            assertEquals(1.0, result.svals()[0], 1e-8);
            assertEquals(2.0, result.svals()[1], 1e-8);
        }
    }
}
