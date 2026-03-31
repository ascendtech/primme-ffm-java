package us.ascendtech.primme;

import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests concurrent PRIMME eigenvalue solves from platform and virtual threads.
 * Skipped automatically when the PRIMME native library is not on the classpath.
 */
@NativeAvailable
class PrimmeConcurrentTest {

    static Stream<Arguments> executors() {
        return Stream.of(
                Arguments.of("platform threads", Executors.newFixedThreadPool(4)),
                Arguments.of("virtual threads", Executors.newVirtualThreadPerTaskExecutor())
        );
    }

    /**
     * Runs 4 eigenvalue solves concurrently.
     * Each solves the same trivial identity-scaled matrix: diag(1, 2, ..., 10).
     * Uses a small n so convergence is guaranteed in few iterations.
     */
    @Timeout(30)
    @ParameterizedTest(name = "{0}")
    @MethodSource("executors")
    void concurrentEigsSolves(String name, ExecutorService executor) throws Exception {
        int numTasks = 4;
        int n = 10;
        int numEvals = 1;

        try (executor) {
            List<Future<PrimmeEigs.Result>> futures = new ArrayList<>();

            for (int k = 0; k < numTasks; k++) {
                futures.add(executor.submit(() -> {
                    try (var eigs = PrimmeEigs.create(n, numEvals, (x, ldx, y, ldy, blockSize) -> {
                        for (int b = 0; b < blockSize; b++) {
                            for (int i = 0; i < n; i++) {
                                double xi = x.getAtIndex(ValueLayout.JAVA_DOUBLE, b * ldx + i);
                                y.setAtIndex(ValueLayout.JAVA_DOUBLE, b * ldy + i, (i + 1) * xi);
                            }
                        }
                    })) {
                        eigs.setMethod(PrimmeEigs.Method.DYNAMIC)
                            .setTarget(PrimmeEigs.Target.SMALLEST)
                            .setEps(1e-8)
                            .setPrintLevel(0);
                        return eigs.solve();
                    }
                }));
            }

            for (int k = 0; k < numTasks; k++) {
                PrimmeEigs.Result result = futures.get(k).get(30, TimeUnit.SECONDS);
                assertEquals(numEvals, result.evals().length);
                assertEquals(1.0, result.evals()[0], 1e-6, "Task " + k + " smallest eigenvalue");
            }
        }
    }
}
