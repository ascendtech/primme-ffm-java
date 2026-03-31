package us.ascendtech.primme;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

/**
 * High-level Java interface for the PRIMME SVD solver using the FFM API.
 *
 * <p>Each instance must be used from a single thread (the creating thread).
 * Multiple instances may run concurrently from different threads, since the
 * bundled BLAS is sequential and each instance has independent state.
 *
 * <p>Requires PRIMME compiled with 64-bit {@code PRIMME_INT} (the default).
 *
 * <p>Example usage:
 * <pre>{@code
 * try (var svds = PrimmeSvds.create(m, n, numSvals, (x, ldx, y, ldy, blockSize, transpose) -> {
 *     // your matrix-vector multiply (or transpose multiply)
 * })) {
 *     svds.setMethod(PrimmeSvds.Method.DEFAULT);
 *     svds.setEps(1e-12);
 *     PrimmeSvds.Result result = svds.solve();
 *     double[] singularValues = result.svals();
 * }
 * }</pre>
 */
public final class PrimmeSvds implements AutoCloseable {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup SYMBOLS;
    private static final MethodHandle MH_PARAMS_CREATE;
    private static final MethodHandle MH_PARAMS_DESTROY;
    private static final MethodHandle MH_SET_METHOD;
    private static final MethodHandle MH_SET_MEMBER;
    private static final MethodHandle MH_DPRIMME_SVDS;

    // Callback: void (*)(void *x, int64_t *ldx, void *y, int64_t *ldy,
    //                     int *blockSize, int *transpose,
    //                     primme_svds_params *primme_svds, int *ierr)
    private static final FunctionDescriptor MATVEC_DESC = FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS
    );

    static {
        NativeLoader.ensureLoaded();
        SYMBOLS = NativeLoader.symbols();

        MH_PARAMS_CREATE = LINKER.downcallHandle(
                SYMBOLS.find("primme_svds_params_create").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS));

        MH_PARAMS_DESTROY = LINKER.downcallHandle(
                SYMBOLS.find("primme_svds_params_destroy").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        MH_SET_METHOD = LINKER.downcallHandle(
                SYMBOLS.find("primme_svds_set_method").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        MH_SET_MEMBER = LINKER.downcallHandle(
                SYMBOLS.find("primme_svds_set_member").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        MH_DPRIMME_SVDS = LINKER.downcallHandle(
                SYMBOLS.find("dprimme_svds").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }

    // ── PRIMME svds_params_label constants (from primme_svds.h) ────────
    private static final int PRIMME_SVDS_m                    = 3;
    private static final int PRIMME_SVDS_n                    = 4;
    private static final int PRIMME_SVDS_matrixMatvec         = 5;
    private static final int PRIMME_SVDS_numSvals             = 18;
    private static final int PRIMME_SVDS_target               = 19;
    private static final int PRIMME_SVDS_eps                  = 29;
    private static final int PRIMME_SVDS_printLevel           = 36;
    private static final int PRIMME_SVDS_maxBasisSize         = 32;
    private static final int PRIMME_SVDS_maxBlockSize         = 33;
    private static final int PRIMME_SVDS_maxMatvecs           = 34;
    private static final int PRIMME_SVDS_numTargetShifts      = 20;
    private static final int PRIMME_SVDS_targetShifts         = 21;

    /** Which singular values to target. */
    public enum Target {
        LARGEST(0), SMALLEST(1), CLOSEST_ABS(2);
        final int value;
        Target(int v) { this.value = v; }
    }

    /** SVD solver method. */
    public enum Method {
        DEFAULT(0), HYBRID(1), NORMAL_EQUATIONS(2), AUGMENTED(3);
        final int value;
        Method(int v) { this.value = v; }
    }

    /** Eigenvalue sub-method for each stage of the SVD solver. */
    public enum EigsMethod {
        DEFAULT(0), DYNAMIC(1), DEFAULT_MIN_TIME(2), DEFAULT_MIN_MATVECS(3),
        ARNOLDI(4), GD(5), GD_PLUS_K(6), GD_OLSEN_PLUS_K(7),
        JD_OLSEN_PLUS_K(8), RQI(9), JDQR(10), JDQMR(11), JDQMR_ETOL(12),
        STEEPEST_DESCENT(13), LOBPCG_ORTHO_BASIS(14), LOBPCG_ORTHO_BASIS_WINDOW(15);
        final int value;
        EigsMethod(int v) { this.value = v; }
    }

    /** Callback for matrix-vector multiplication (with transpose flag). */
    @FunctionalInterface
    public interface MatrixMultiply {
        /**
         * Compute y = A*x (transpose==0) or y = A'*x (transpose==1).
         *
         * @param x         input vectors
         * @param ldx       leading dimension of x
         * @param y         output vectors
         * @param ldy       leading dimension of y
         * @param blockSize number of vectors
         * @param transpose 0 for A*x, 1 for A'*x
         */
        void apply(MemorySegment x, long ldx, MemorySegment y, long ldy, int blockSize, int transpose);
    }

    /** Result of an SVD computation. */
    public record Result(double[] svals, double[][] svecs, double[] resNorms) {}

    // ── Instance state ─────────────────────────────────────────────────
    private final Arena arena;
    private final MemorySegment params;
    private final MatrixMultiply matMul;
    private final long m;
    private final long n;
    private final int numSvals;
    @SuppressWarnings("unused") // prevent GC of upcall stub while params alive
    private MemorySegment upcallStub;
    private boolean closed;
    private volatile Throwable lastCallbackError;

    private PrimmeSvds(long m, long n, int numSvals, MatrixMultiply matMul) {
        this.arena = Arena.ofShared();
        this.m = m;
        this.n = n;
        this.numSvals = numSvals;
        this.matMul = matMul;

        MemorySegment allocatedParams = null;
        try {
            allocatedParams = (MemorySegment) MH_PARAMS_CREATE.invokeExact();
            if (allocatedParams.equals(MemorySegment.NULL)) {
                throw new PrimmeException(-2);
            }
            this.params = allocatedParams;

            setMemberLong(PRIMME_SVDS_m, m);
            setMemberLong(PRIMME_SVDS_n, n);
            setMemberInt(PRIMME_SVDS_numSvals, numSvals);
            installMatvecCallback();
        } catch (Throwable t) {
            if (allocatedParams != null && !allocatedParams.equals(MemorySegment.NULL)) {
                try { MH_PARAMS_DESTROY.invokeExact(allocatedParams); } catch (Throwable ignored) {}
            }
            arena.close();
            if (t instanceof PrimmeException pe) throw pe;
            throw new RuntimeException("Failed to initialize PRIMME SVDS params", t);
        }
    }

    /**
     * Creates a new SVD solver.
     *
     * @param m        number of rows (must be positive)
     * @param n        number of columns (must be positive)
     * @param numSvals number of singular values to compute (must be positive)
     * @param matMul   matrix-vector multiplication callback
     * @throws IllegalArgumentException if m, n, or numSvals is not positive, or matMul is null
     */
    public static PrimmeSvds create(long m, long n, int numSvals, MatrixMultiply matMul) {
        if (m <= 0) throw new IllegalArgumentException("m must be positive: " + m);
        if (n <= 0) throw new IllegalArgumentException("n must be positive: " + n);
        if (numSvals <= 0) throw new IllegalArgumentException("numSvals must be positive: " + numSvals);
        Objects.requireNonNull(matMul, "matMul");
        return new PrimmeSvds(m, n, numSvals, matMul);
    }

    /** Sets the SVD solver method and eigs sub-methods for each stage. */
    public PrimmeSvds setMethod(Method method, EigsMethod stage1, EigsMethod stage2) {
        try {
            int ret = (int) MH_SET_METHOD.invokeExact(
                    method.value, stage1.value, stage2.value, params);
            if (ret != 0) throw new PrimmeException(ret);
        } catch (PrimmeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return this;
    }

    /** Sets the SVD solver method with default sub-methods. */
    public PrimmeSvds setMethod(Method method) {
        return setMethod(method, EigsMethod.DEFAULT, EigsMethod.DEFAULT);
    }

    /** Sets the convergence tolerance. */
    public PrimmeSvds setEps(double eps) {
        setMemberDouble(PRIMME_SVDS_eps, eps);
        return this;
    }

    /** Sets which singular values to target. */
    public PrimmeSvds setTarget(Target target) {
        setMemberInt(PRIMME_SVDS_target, target.value);
        return this;
    }

    /** Sets the print level (0 = silent, 5 = max). */
    public PrimmeSvds setPrintLevel(int level) {
        setMemberInt(PRIMME_SVDS_printLevel, level);
        return this;
    }

    /** Sets the maximum basis size. */
    public PrimmeSvds setMaxBasisSize(int size) {
        setMemberInt(PRIMME_SVDS_maxBasisSize, size);
        return this;
    }

    /** Sets the maximum block size. */
    public PrimmeSvds setMaxBlockSize(int size) {
        setMemberInt(PRIMME_SVDS_maxBlockSize, size);
        return this;
    }

    /** Sets the maximum number of matrix-vector multiplications. */
    public PrimmeSvds setMaxMatvecs(long max) {
        setMemberLong(PRIMME_SVDS_maxMatvecs, max);
        return this;
    }

    /** Sets target shifts for interior singular value problems. */
    public PrimmeSvds setTargetShifts(double... shifts) {
        setMemberInt(PRIMME_SVDS_numTargetShifts, shifts.length);
        MemorySegment shiftsSegment = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, shifts);
        setMemberPointer(PRIMME_SVDS_targetShifts, shiftsSegment);
        return this;
    }

    /**
     * Returns the last exception thrown by the MatrixMultiply callback, or null.
     * Useful for diagnosing solver failures caused by callback errors.
     */
    public Throwable lastCallbackError() {
        return lastCallbackError;
    }

    /**
     * Runs the SVD solver.
     *
     * @return the computed singular values, singular vectors, and residual norms
     * @throws PrimmeException if the solver fails
     */
    public Result solve() {
        lastCallbackError = null;

        MemorySegment svals = arena.allocate(ValueLayout.JAVA_DOUBLE, numSvals);
        long svecsLen = (m + n) * numSvals;
        MemorySegment svecs = arena.allocate(ValueLayout.JAVA_DOUBLE, svecsLen);
        MemorySegment resNorms = arena.allocate(ValueLayout.JAVA_DOUBLE, numSvals);

        int ret;
        try {
            ret = (int) MH_DPRIMME_SVDS.invokeExact(svals, svecs, resNorms, params);
        } catch (Throwable t) {
            throw new RuntimeException("dprimme_svds invocation failed", t);
        }
        if (ret != 0) {
            var ex = new PrimmeException(ret);
            if (lastCallbackError != null) ex.addSuppressed(lastCallbackError);
            throw ex;
        }

        double[] svalsArr = svals.toArray(ValueLayout.JAVA_DOUBLE);
        double[] resNormsArr = resNorms.toArray(ValueLayout.JAVA_DOUBLE);
        double[][] svecsArr = new double[numSvals][];
        for (int i = 0; i < numSvals; i++) {
            long offset = (long) i * (m + n) * Double.BYTES;
            svecsArr[i] = svecs.asSlice(offset, (m + n) * Double.BYTES)
                    .toArray(ValueLayout.JAVA_DOUBLE);
        }

        return new Result(svalsArr, svecsArr, resNormsArr);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                MH_PARAMS_DESTROY.invokeExact(params);
            } catch (Throwable t) {
                // best-effort cleanup
            }
            arena.close();
        }
    }

    // ── Private helpers ────────────────────────────────────────────────

    private void installMatvecCallback() throws Throwable {
        MethodHandle target = MethodHandles.lookup()
                .bind(this, "matvecTrampoline",
                        MethodType.methodType(void.class,
                                MemorySegment.class, MemorySegment.class,
                                MemorySegment.class, MemorySegment.class,
                                MemorySegment.class, MemorySegment.class,
                                MemorySegment.class, MemorySegment.class));

        upcallStub = LINKER.upcallStub(target, MATVEC_DESC, arena);
        // PRIMME's set_member for function pointers expects the pointer passed
        // directly as void* (not &pointer), per its internal value_t union trick
        callSetMember(PRIMME_SVDS_matrixMatvec, upcallStub);
    }

    @SuppressWarnings("unused") // called via upcall from native code
    private void matvecTrampoline(
            MemorySegment x, MemorySegment ldxPtr,
            MemorySegment y, MemorySegment ldyPtr,
            MemorySegment blockSizePtr, MemorySegment transposePtr,
            MemorySegment primmePtr, MemorySegment ierrPtr) {

        long ldx = ldxPtr.reinterpret(Long.BYTES).get(ValueLayout.JAVA_LONG, 0);
        long ldy = ldyPtr.reinterpret(Long.BYTES).get(ValueLayout.JAVA_LONG, 0);
        int blockSize = blockSizePtr.reinterpret(Integer.BYTES).get(ValueLayout.JAVA_INT, 0);
        int transpose = transposePtr.reinterpret(Integer.BYTES).get(ValueLayout.JAVA_INT, 0);

        try {
            matMul.apply(
                    x.reinterpret(ldx * blockSize * Double.BYTES),
                    ldx,
                    y.reinterpret(ldy * blockSize * Double.BYTES),
                    ldy,
                    blockSize,
                    transpose);
            ierrPtr.reinterpret(Integer.BYTES).set(ValueLayout.JAVA_INT, 0, 0);
        } catch (Exception e) {
            lastCallbackError = e;
            ierrPtr.reinterpret(Integer.BYTES).set(ValueLayout.JAVA_INT, 0, -1);
        }
    }

    private void setMemberInt(int label, int value) {
        MemorySegment valSeg = arena.allocate(ValueLayout.JAVA_INT);
        valSeg.set(ValueLayout.JAVA_INT, 0, value);
        callSetMember(label, valSeg);
    }

    private void setMemberLong(int label, long value) {
        MemorySegment valSeg = arena.allocate(ValueLayout.JAVA_LONG);
        valSeg.set(ValueLayout.JAVA_LONG, 0, value);
        callSetMember(label, valSeg);
    }

    private void setMemberDouble(int label, double value) {
        MemorySegment valSeg = arena.allocate(ValueLayout.JAVA_DOUBLE);
        valSeg.set(ValueLayout.JAVA_DOUBLE, 0, value);
        callSetMember(label, valSeg);
    }

    private void setMemberPointer(int label, MemorySegment pointer) {
        MemorySegment valSeg = arena.allocate(ValueLayout.ADDRESS);
        valSeg.set(ValueLayout.ADDRESS, 0, pointer);
        callSetMember(label, valSeg);
    }

    private void callSetMember(int label, MemorySegment value) {
        try {
            int ret = (int) MH_SET_MEMBER.invokeExact(params, label, value);
            if (ret != 0) throw new PrimmeException(ret);
        } catch (PrimmeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
