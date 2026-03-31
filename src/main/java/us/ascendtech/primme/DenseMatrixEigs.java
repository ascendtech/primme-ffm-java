package us.ascendtech.primme;

import java.lang.foreign.ValueLayout;

/**
 * {@link PrimmeEigs.MatrixMultiply} implementation for a dense {@code double[][]} matrix.
 * The matrix is stored as {@code A[row][col]} (row-major Java convention).
 */
public final class DenseMatrixEigs implements PrimmeEigs.MatrixMultiply {

    private final double[][] matrix;
    private final int n;

    public DenseMatrixEigs(double[][] matrix) {
        this.matrix = matrix;
        this.n = matrix.length;
    }

    @Override
    public void apply(java.lang.foreign.MemorySegment x, long ldx,
                      java.lang.foreign.MemorySegment y, long ldy, int blockSize) {
        for (int b = 0; b < blockSize; b++) {
            for (int i = 0; i < n; i++) {
                double sum = 0.0;
                double[] row = matrix[i];
                for (int j = 0; j < n; j++) {
                    sum += row[j] * x.getAtIndex(ValueLayout.JAVA_DOUBLE, b * ldx + j);
                }
                y.setAtIndex(ValueLayout.JAVA_DOUBLE, b * ldy + i, sum);
            }
        }
    }
}
