# PRIMME FFM Java

Java bindings for the [PRIMME](https://github.com/primme/primme) eigenvalue and SVD solver library, using the [Foreign Function & Memory (FFM)](https://openjdk.org/jeps/454) API.

The native PRIMME library (with OpenBLAS statically linked) is bundled inside per-platform JARs -- no system BLAS/LAPACK installation required.

## Requirements

- Java 25 or later
- Supported platforms: Linux x86_64/aarch64, macOS aarch64, Windows x86_64

## Installation

Add the API JAR and your platform's native JAR:

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("us.ascend-tech:primme-ffm-java:VERSION")
    implementation("us.ascend-tech:primme-ffm-java:VERSION:linux-x86_64")
}
```

**Maven:**

```xml
<dependency>
    <groupId>us.ascend-tech</groupId>
    <artifactId>primme-ffm-java</artifactId>
    <version>VERSION</version>
</dependency>
<dependency>
    <groupId>us.ascend-tech</groupId>
    <artifactId>primme-ffm-java</artifactId>
    <version>VERSION</version>
    <classifier>linux-x86_64</classifier>
</dependency>
```

Available classifiers: `linux-x86_64`, `linux-aarch64`, `macos-aarch64`, `windows-x86_64`

## Usage

### Eigenvalues (dense matrix)

For dense `double[][]` matrices, use the built-in `DenseMatrixEigs`:

```java
double[][] A = ...; // n x n symmetric matrix
try (var eigs = PrimmeEigs.create(n, 3, new DenseMatrixEigs(A))) {
    eigs.setMethod(PrimmeEigs.Method.DYNAMIC)
        .setTarget(PrimmeEigs.Target.SMALLEST)
        .setEps(1e-12);

    PrimmeEigs.Result result = eigs.solve();
    double[] eigenvalues = result.evals();      // [λ1, λ2, λ3]
    double[][] eigenvectors = result.evecs();    // evecs[i] is the i-th eigenvector
    double[] residuals = result.resNorms();
}
```

### Singular values (dense matrix)

For dense `double[][]` matrices, use the built-in `DenseMatrixSvds`:

```java
double[][] A = ...; // m x n matrix
try (var svds = PrimmeSvds.create(m, n, 3, new DenseMatrixSvds(A))) {
    svds.setMethod(PrimmeSvds.Method.DEFAULT)
        .setTarget(PrimmeSvds.Target.LARGEST)
        .setEps(1e-12);

    PrimmeSvds.Result result = svds.solve();
    double[] singularValues = result.svals();
}
```

### Custom matrix-vector multiply

For sparse, implicit, or non-standard matrices, implement the `MatrixMultiply` callback directly. PRIMME never accesses the matrix — it only calls your callback to compute `y = A * x`.

Note that `PrimmeEigs.MatrixMultiply` and `PrimmeSvds.MatrixMultiply` are different interfaces:

| | `PrimmeEigs.MatrixMultiply` | `PrimmeSvds.MatrixMultiply` |
|---|---|---|
| Operation | `y = A * x` only | `y = A * x` or `y = A' * x` |
| Matrix shape | square (n x n) | rectangular (m x n) |
| Extra parameter | — | `transpose` (0 or 1) |

The eigenvalue solver only needs forward multiplication (the matrix must be symmetric/Hermitian). The SVD solver needs both directions because it internally converts the SVD into an eigenvalue problem on `A'A` or `AA'`.

**Eigenvalues** — implement `PrimmeEigs.MatrixMultiply`:

```java
try (var eigs = PrimmeEigs.create(n, numEvals, (x, ldx, y, ldy, blockSize) -> {
    // Compute y = A * x for each of the blockSize vectors
    for (int b = 0; b < blockSize; b++) {
        for (int i = 0; i < n; i++) {
            double yi = 0;
            // ... your matrix-vector product logic ...
            y.setAtIndex(JAVA_DOUBLE, b * ldy + i, yi);
        }
    }
})) {
    eigs.setMethod(PrimmeEigs.Method.DYNAMIC).setEps(1e-12);
    PrimmeEigs.Result result = eigs.solve();
}
```

**Singular values** — implement `PrimmeSvds.MatrixMultiply`, which adds a `transpose` flag:

```java
try (var svds = PrimmeSvds.create(m, n, numSvals, (x, ldx, y, ldy, blockSize, transpose) -> {
    // transpose == 0: compute y = A * x   (x has n rows, y has m rows)
    // transpose == 1: compute y = A' * x  (x has m rows, y has n rows)
    int rows = (transpose == 0) ? m : n;
    int cols = (transpose == 0) ? n : m;
    for (int b = 0; b < blockSize; b++) {
        for (int i = 0; i < rows; i++) {
            double yi = 0;
            for (int j = 0; j < cols; j++) {
                double aij = (transpose == 0) ? getA(i, j) : getA(j, i);
                yi += aij * x.getAtIndex(JAVA_DOUBLE, b * ldx + j);
            }
            y.setAtIndex(JAVA_DOUBLE, b * ldy + i, yi);
        }
    }
})) {
    svds.setMethod(PrimmeSvds.Method.DEFAULT).setEps(1e-12);
    PrimmeSvds.Result result = svds.solve();
}
```

The callback parameters: `x` and `y` are column-major `MemorySegment`s of doubles. `ldx`/`ldy` are leading dimensions (stride between columns). `blockSize` is the number of vectors to multiply at once (set by the solver, usually 1).

### Callback Errors

If your matrix-vector callback throws, PRIMME will report a solver failure. The original exception is preserved:

```java
try {
    result = eigs.solve();
} catch (PrimmeException e) {
    Throwable cause = eigs.lastCallbackError(); // the original exception
}
```

## JVM Flags

Enable native access for the FFM API:

```
--enable-native-access=ALL-UNNAMED
```

Or in Gradle:

```kotlin
tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
```

## Thread Safety

Each `PrimmeEigs` / `PrimmeSvds` instance must be used from a single thread. Multiple instances can run concurrently from different threads, including virtual threads.

On Linux and macOS, the bundled BLAS is sequential (built with `USE_THREAD=0`), so there is no thread contention between concurrent solves. On Windows, the BLAS uses OpenMP threading -- set `OMP_NUM_THREADS=1` if running many solves in parallel.

For parallel matrix-vector products within a single solve, use Java parallelism in your callback:

```java
PrimmeEigs.create(n, numEvals, (x, ldx, y, ldy, blockSize) -> {
    IntStream.range(0, blockSize).parallel().forEach(b -> {
        // compute y[b] = A * x[b]
    });
});
```

## Building from Source

```bash
git clone https://github.com/ascendtech/primme-ffm-java.git
cd primme-ffm-java
./gradlew
```

Running `./gradlew` with no arguments builds the native library (if not already present), compiles, and runs tests. The first build takes ~3 minutes (OpenBLAS compilation); subsequent builds skip the native step and finish in seconds.

You can also run the native build or tests separately:
- `./gradlew buildNative` — build just the native library
- `./gradlew build` — compile and test (assumes native already built)
- `./gradlew cleanNative` — force a native rebuild on next run

The native build auto-detects your OS/arch and produces a self-contained shared library:
- **Linux/Windows**: clones OpenBLAS v0.3.32 (sequential) and PRIMME v3.2, statically links everything
- **macOS**: clones PRIMME v3.2, links against the Accelerate framework

The native library is placed in `src/main/resources/native/<platform>/` (gitignored). Tests auto-detect it and run when present.

### Prerequisites

- **Linux**: `gcc`, `make`, `git`
- **macOS**: Xcode command line tools, `git`
- **Windows**: MSYS2 with `mingw-w64-x86_64-gcc`, `mingw-w64-x86_64-gcc-fortran`, `mingw-w64-x86_64-openblas`, `mingw-w64-x86_64-make`, `git`

## Published JARs

| JAR | Contents |
|---|---|
| `primme-ffm-java-VERSION.jar` | Java API (~21 KB) |
| `primme-ffm-java-VERSION-linux-x86_64.jar` | Linux x86_64 native (~6 MB) |
| `primme-ffm-java-VERSION-linux-aarch64.jar` | Linux aarch64 native |
| `primme-ffm-java-VERSION-macos-aarch64.jar` | macOS aarch64 native |
| `primme-ffm-java-VERSION-windows-x86_64.jar` | Windows x86_64 native |

Linux/Windows JARs include OpenBLAS statically linked. macOS JARs link against the Accelerate framework (always available).

## License

BSD-3-Clause (same as PRIMME)
