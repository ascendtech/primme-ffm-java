package us.ascendtech.primme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the dense matrix implementations.
 * Skipped automatically when the PRIMME native library is not on the classpath.
 */
@NativeAvailable
class DenseMatrixTest {

    /**
     * Eigenvalues of a symmetric tridiagonal matrix.
     * T = [2 -1  0 ...]
     *     [-1  2 -1 ...]
     *     [0 -1  2  ...]
     * Eigenvalues: 2 - 2*cos(k*pi/(n+1)) for k=1..n
     * Smallest eigenvalue ≈ 2 - 2*cos(pi/(n+1))
     */
    @Test
    void eigsWithDenseMatrix() {
        int n = 50;
        double[][] A = new double[n][n];
        for (int i = 0; i < n; i++) {
            A[i][i] = 2.0;
            if (i > 0) { A[i][i - 1] = -1.0; A[i - 1][i] = -1.0; }
        }

        try (var eigs = PrimmeEigs.create(n, 3, new DenseMatrixEigs(A))) {
            eigs.setMethod(PrimmeEigs.Method.DEFAULT)
                .setTarget(PrimmeEigs.Target.SMALLEST)
                .setEps(1e-12)
                .setPrintLevel(0);

            PrimmeEigs.Result result = eigs.solve();

            // Analytical eigenvalues
            for (int k = 0; k < 3; k++) {
                double expected = 2.0 - 2.0 * Math.cos((k + 1) * Math.PI / (n + 1));
                assertEquals(expected, result.evals()[k], 1e-8,
                        "Eigenvalue " + k);
            }
        }
    }

    /**
     * Singular values of a rectangular matrix.
     * A[i][j] = 1/(i+j+1) (Hilbert-like matrix, m x n)
     */
    @Test
    void svdsWithDenseMatrix() {
        int m = 20;
        int n = 15;
        double[][] A = new double[m][n];
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++)
                A[i][j] = 1.0 / (i + j + 1);

        try (var svds = PrimmeSvds.create(m, n, 3, new DenseMatrixSvds(A))) {
            svds.setMethod(PrimmeSvds.Method.DEFAULT)
                .setTarget(PrimmeSvds.Target.LARGEST)
                .setEps(1e-10)
                .setPrintLevel(0);

            PrimmeSvds.Result result = svds.solve();

            // Largest singular value of a Hilbert-like matrix is well-conditioned
            assertEquals(3, result.svals().length);
            assertTrue(result.svals()[0] > result.svals()[1],
                    "Singular values should be in decreasing order");
            assertTrue(result.svals()[1] > result.svals()[2],
                    "Singular values should be in decreasing order");

            for (double rn : result.resNorms()) {
                assertTrue(rn < 1e-8, "Residual norm too large: " + rn);
            }
        }
    }
}
