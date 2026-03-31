package us.ascendtech.primme;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

/**
 * High-level Java interface for the PRIMME eigenvalue solver using the FFM API.
 *
 * <p>Each instance must be used from a single thread (the creating thread).
 * Multiple instances may run concurrently from different threads, since the
 * bundled BLAS is sequential and each instance has independent state.
 *
 * <p>Requires PRIMME compiled with 64-bit {@code PRIMME_INT} (the default).
 *
 * <p>Example usage:
 * <pre>{@code
 * try (var eigs = PrimmeEigs.create(n, numEvals, (x, ldx, y, ldy, blockSize) -> {
 *     // your matrix-vector multiply
 * })) {
 *     eigs.setMethod(PrimmeEigs.Method.DYNAMIC);
 *     eigs.setEps(1e-12);
 *     PrimmeEigs.Result result = eigs.solve();
 *     double[] eigenvalues = result.evals();
 * }
 * }</pre>
 */
public final class PrimmeEigs implements AutoCloseable {

    // ── Function descriptors for PRIMME C API ──────────────────────────
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup SYMBOLS;
    private static final MethodHandle MH_PARAMS_CREATE;
    private static final MethodHandle MH_PARAMS_DESTROY;
    private static final MethodHandle MH_SET_METHOD;
    private static final MethodHandle MH_SET_MEMBER;
    private static final MethodHandle MH_DPRIMME;

    // Callback: void (*)(void *x, PRIMME_INT *ldx, void *y, PRIMME_INT *ldy,
    //                     int *blockSize, primme_params *primme, int *ierr)
    private static final FunctionDescriptor MATVEC_DESC = FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS
    );

    static {
        NativeLoader.ensureLoaded();
        SYMBOLS = NativeLoader.symbols();

        MH_PARAMS_CREATE = LINKER.downcallHandle(
                SYMBOLS.find("primme_params_create").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS));

        MH_PARAMS_DESTROY = LINKER.downcallHandle(
                SYMBOLS.find("primme_params_destroy").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        MH_SET_METHOD = LINKER.downcallHandle(
                SYMBOLS.find("primme_set_method").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        MH_SET_MEMBER = LINKER.downcallHandle(
                SYMBOLS.find("primme_set_member").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        MH_DPRIMME = LINKER.downcallHandle(
                SYMBOLS.find("dprimme").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }

    // ── PRIMME params_label constants (from primme_eigs.h) ─────────────
    private static final int PRIMME_n                = 1;
    private static final int PRIMME_matrixMatvec     = 2;
    private static final int PRIMME_numEvals         = 16;
    private static final int PRIMME_target           = 17;
    private static final int PRIMME_eps              = 32;
    private static final int PRIMME_printLevel       = 35;
    private static final int PRIMME_numTargetShifts  = 18;
    private static final int PRIMME_targetShifts     = 19;
    private static final int PRIMME_maxBasisSize     = 23;
    private static final int PRIMME_maxBlockSize     = 25;
    private static final int PRIMME_maxMatvecs       = 26;

    /** Eigenvalue target. Maps to {@code primme_target} enum. */
    public enum Target {
        SMALLEST(0), LARGEST(1), CLOSEST_GEQ(2), CLOSEST_LEQ(3), CLOSEST_ABS(4), LARGEST_ABS(5);
        final int value;
        Target(int v) { this.value = v; }
    }

    /** Solver method. Maps to {@code primme_preset_method} enum. */
    public enum Method {
        DEFAULT(0), DYNAMIC(1), DEFAULT_MIN_TIME(2), DEFAULT_MIN_MATVECS(3),
        ARNOLDI(4), GD(5), GD_PLUS_K(6), GD_OLSEN_PLUS_K(7),
        JD_OLSEN_PLUS_K(8), RQI(9), JDQR(10), JDQMR(11), JDQMR_ETOL(12),
        STEEPEST_DESCENT(13), LOBPCG_ORTHO_BASIS(14), LOBPCG_ORTHO_BASIS_WINDOW(15);
        final int value;
        Method(int v) { this.value = v; }
    }

    /** Callback for matrix-vector multiplication. */
    @FunctionalInterface
    public interface MatrixMultiply {
        /**
         * Compute y = A * x for a block of vectors.
         *
         * @param x         input vectors (column-major, doubles)
         * @param ldx       leading dimension of x
         * @param y         output vectors (column-major, doubles)
         * @param ldy       leading dimension of y
         * @param blockSize number of vectors in the block
         */
        void apply(MemorySegment x, long ldx, MemorySegment y, long ldy, int blockSize);
    }

    /** Result of an eigenvalue computation. */
    public record Result(double[] evals, double[][] evecs, double[] resNorms) {}

    // ── Instance state ─────────────────────────────────────────────────
    private final Arena arena;
    private final MemorySegment params;
    private final MatrixMultiply matMul;
    private final long n;
    private final int numEvals;
    @SuppressWarnings("unused") // prevent GC of upcall stub while params alive
    private MemorySegment upcallStub;
    private boolean closed;
    private volatile Throwable lastCallbackError;

    private PrimmeEigs(long n, int numEvals, MatrixMultiply matMul) {
        this.arena = Arena.ofShared();
        this.n = n;
        this.numEvals = numEvals;
        this.matMul = matMul;

        MemorySegment allocatedParams = null;
        try {
            allocatedParams = (MemorySegment) MH_PARAMS_CREATE.invokeExact();
            if (allocatedParams.equals(MemorySegment.NULL)) {
                throw new PrimmeException(-2);
            }
            this.params = allocatedParams;

            setMemberLong(PRIMME_n, n);
            setMemberInt(PRIMME_numEvals, numEvals);
            installMatvecCallback();
        } catch (Throwable t) {
            // Clean up native params if allocated
            if (allocatedParams != null && !allocatedParams.equals(MemorySegment.NULL)) {
                try { MH_PARAMS_DESTROY.invokeExact(allocatedParams); } catch (Throwable ignored) {}
            }
            arena.close();
            if (t instanceof PrimmeException pe) throw pe;
            throw new RuntimeException("Failed to initialize PRIMME params", t);
        }
    }

    /**
     * Creates a new eigenvalue solver.
     *
     * @param n        matrix dimension (must be positive)
     * @param numEvals number of eigenvalues to compute (must be positive)
     * @param matMul   matrix-vector multiplication callback
     * @throws IllegalArgumentException if n or numEvals is not positive, or matMul is null
     */
    public static PrimmeEigs create(long n, int numEvals, MatrixMultiply matMul) {
        if (n <= 0) throw new IllegalArgumentException("n must be positive: " + n);
        if (numEvals <= 0) throw new IllegalArgumentException("numEvals must be positive: " + numEvals);
        Objects.requireNonNull(matMul, "matMul");
        return new PrimmeEigs(n, numEvals, matMul);
    }

    /** Sets the solver method. */
    public PrimmeEigs setMethod(Method method) {
        try {
            int ret = (int) MH_SET_METHOD.invokeExact(method.value, params);
            if (ret != 0) throw new PrimmeException(ret);
        } catch (PrimmeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return this;
    }

    /** Sets the convergence tolerance. */
    public PrimmeEigs setEps(double eps) {
        setMemberDouble(PRIMME_eps, eps);
        return this;
    }

    /** Sets which eigenvalues to target. */
    public PrimmeEigs setTarget(Target target) {
        setMemberInt(PRIMME_target, target.value);
        return this;
    }

    /** Sets the print level (0 = silent, 5 = max). */
    public PrimmeEigs setPrintLevel(int level) {
        setMemberInt(PRIMME_printLevel, level);
        return this;
    }

    /** Sets the maximum basis size. */
    public PrimmeEigs setMaxBasisSize(int size) {
        setMemberInt(PRIMME_maxBasisSize, size);
        return this;
    }

    /** Sets the maximum block size. */
    public PrimmeEigs setMaxBlockSize(int size) {
        setMemberInt(PRIMME_maxBlockSize, size);
        return this;
    }

    /** Sets the maximum number of matrix-vector multiplications. */
    public PrimmeEigs setMaxMatvecs(long max) {
        setMemberLong(PRIMME_maxMatvecs, max);
        return this;
    }

    /** Sets target shifts for interior eigenvalue problems. */
    public PrimmeEigs setTargetShifts(double... shifts) {
        setMemberInt(PRIMME_numTargetShifts, shifts.length);
        MemorySegment shiftsSegment = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, shifts);
        setMemberPointer(PRIMME_targetShifts, shiftsSegment);
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
     * Runs the eigenvalue solver.
     *
     * @return the computed eigenvalues, eigenvectors, and residual norms
     * @throws PrimmeException if the solver fails
     */
    public Result solve() {
        lastCallbackError = null;

        MemorySegment evals = arena.allocate(ValueLayout.JAVA_DOUBLE, numEvals);
        MemorySegment evecs = arena.allocate(ValueLayout.JAVA_DOUBLE, n * numEvals);
        MemorySegment resNorms = arena.allocate(ValueLayout.JAVA_DOUBLE, numEvals);

        int ret;
        try {
            ret = (int) MH_DPRIMME.invokeExact(evals, evecs, resNorms, params);
        } catch (Throwable t) {
            throw new RuntimeException("dprimme invocation failed", t);
        }
        if (ret != 0) {
            var ex = new PrimmeException(ret);
            if (lastCallbackError != null) ex.addSuppressed(lastCallbackError);
            throw ex;
        }

        double[] evalsArr = evals.toArray(ValueLayout.JAVA_DOUBLE);
        double[] resNormsArr = resNorms.toArray(ValueLayout.JAVA_DOUBLE);
        double[][] evecsArr = new double[numEvals][];
        for (int i = 0; i < numEvals; i++) {
            evecsArr[i] = evecs.asSlice((long) i * n * Double.BYTES, n * Double.BYTES)
                    .toArray(ValueLayout.JAVA_DOUBLE);
        }

        return new Result(evalsArr, evecsArr, resNormsArr);
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
                                MemorySegment.class));

        upcallStub = LINKER.upcallStub(target, MATVEC_DESC, arena);
        // PRIMME's set_member for function pointers expects the pointer passed
        // directly as void* (not &pointer), per its internal value_t union trick
        callSetMember(PRIMME_matrixMatvec, upcallStub);
    }

    @SuppressWarnings("unused") // called via upcall from native code
    private void matvecTrampoline(
            MemorySegment x, MemorySegment ldxPtr,
            MemorySegment y, MemorySegment ldyPtr,
            MemorySegment blockSizePtr, MemorySegment primmePtr,
            MemorySegment ierrPtr) {

        long ldx = ldxPtr.reinterpret(Long.BYTES).get(ValueLayout.JAVA_LONG, 0);
        long ldy = ldyPtr.reinterpret(Long.BYTES).get(ValueLayout.JAVA_LONG, 0);
        int blockSize = blockSizePtr.reinterpret(Integer.BYTES).get(ValueLayout.JAVA_INT, 0);

        try {
            matMul.apply(
                    x.reinterpret(ldx * blockSize * Double.BYTES),
                    ldx,
                    y.reinterpret(ldy * blockSize * Double.BYTES),
                    ldy,
                    blockSize);
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
