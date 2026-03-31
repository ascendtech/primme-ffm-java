package us.ascendtech.primme;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads the PRIMME native library bundled inside the JAR.
 * <p>
 * Supported platforms: linux-x86_64, linux-aarch64, macos-x86_64, macos-aarch64, windows-x86_64.
 * The native library is expected at {@code /native/<platform>/libprimme.<ext>}
 * on the classpath.
 */
public final class NativeLoader {

    private static volatile boolean loaded;
    private static volatile SymbolLookup symbols;

    private NativeLoader() {}

    /** Returns a {@link SymbolLookup} backed by the loaded PRIMME library. */
    public static SymbolLookup symbols() {
        ensureLoaded();
        return symbols;
    }

    /** Ensures the native library is loaded exactly once. */
    public static void ensureLoaded() {
        if (!loaded) {
            synchronized (NativeLoader.class) {
                if (!loaded) {
                    loadFromJar();
                    loaded = true;
                }
            }
        }
    }

    private static void loadFromJar() {
        String platform = detectPlatform();
        String libName = System.mapLibraryName("primme"); // libprimme.so / libprimme.dylib / primme.dll
        String resourcePath = "/native/" + platform + "/" + libName;

        try (InputStream in = NativeLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                // Fall back to system library path
                System.loadLibrary("primme");
            } else {
                Path tmpDir = Files.createTempDirectory("primme-native");
                Path tmpLib = tmpDir.resolve(libName);
                Files.copy(in, tmpLib, StandardCopyOption.REPLACE_EXISTING);
                tmpLib.toFile().deleteOnExit();
                tmpDir.toFile().deleteOnExit();
                System.load(tmpLib.toAbsolutePath().toString());
            }
            // loaderLookup() finds symbols from libraries loaded by System.load/loadLibrary
            symbols = SymbolLookup.loaderLookup();
        } catch (IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract PRIMME native library: " + e.getMessage());
        }
    }

    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        String osKey;
        if (os.contains("linux")) {
            osKey = "linux";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osKey = "macos";
        } else if (os.contains("win")) {
            osKey = "windows";
        } else {
            throw new UnsupportedOperationException("Unsupported OS: " + os);
        }

        String archKey;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            archKey = "x86_64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            archKey = "aarch64";
        } else {
            throw new UnsupportedOperationException("Unsupported architecture: " + arch);
        }

        return osKey + "-" + archKey;
    }
}
