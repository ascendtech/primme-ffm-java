package us.ascendtech.primme;

import java.lang.foreign.ValueLayout;

/**
 * {@link PrimmeSvds.MatrixMultiply} implementation for a dense {@code double[][]} matrix.
 * The matrix is stored as {@code A[row][col]} (row-major Java convention).
 * Handles both {@code A*x} and {@code A'*x} based on the transpose flag.
 */
public final class DenseMatrixSvds implements PrimmeSvds.MatrixMultiply {

    private final double[][] matrix;
    private final int m;
    private final int n;

    public DenseMatrixSvds(double[][] matrix) {
        this.matrix = matrix;
        this.m = matrix.length;
        this.n = matrix[0].length;
    }

    @Override
    public void apply(java.lang.foreign.MemorySegment x, long ldx,
                      java.lang.foreign.MemorySegment y, long ldy,
                      int blockSize, int transpose) {
        int rows = (transpose == 0) ? m : n;
        int cols = (transpose == 0) ? n : m;

        for (int b = 0; b < blockSize; b++) {
            for (int i = 0; i < rows; i++) {
                double sum = 0.0;
                for (int j = 0; j < cols; j++) {
                    double aij = (transpose == 0) ? matrix[i][j] : matrix[j][i];
                    sum += aij * x.getAtIndex(ValueLayout.JAVA_DOUBLE, b * ldx + j);
                }
                y.setAtIndex(ValueLayout.JAVA_DOUBLE, b * ldy + i, sum);
            }
        }
    }
}
