#!/usr/bin/env bash
set -euo pipefail

# Builds a self-contained PRIMME shared library for the current platform.
# Output: src/main/resources/native/<platform>/libprimme.{so,dylib,dll}

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Detect platform
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)
case "$OS" in
    linux*)  PLATFORM_OS="linux" ;;
    darwin*) PLATFORM_OS="macos" ;;
    mingw*|msys*|cygwin*) PLATFORM_OS="windows" ;;
    *) echo "Unsupported OS: $OS"; exit 1 ;;
esac
case "$ARCH" in
    x86_64|amd64) PLATFORM_ARCH="x86_64" ;;
    aarch64|arm64) PLATFORM_ARCH="aarch64" ;;
    *) echo "Unsupported arch: $ARCH"; exit 1 ;;
esac
PLATFORM="${PLATFORM_OS}-${PLATFORM_ARCH}"
echo "Building for $PLATFORM"

WORK="$SCRIPT_DIR/.native-build"
mkdir -p "$WORK"

# Build PRIMME
if [ ! -d "$WORK/primme" ]; then
    git clone --depth 1 --branch v3.2 https://github.com/primme/primme.git "$WORK/primme"
fi
cd "$WORK/primme"
MAKE=make
if [ "$PLATFORM_OS" = "windows" ]; then
    MAKE=mingw32-make
fi
if [ "$PLATFORM_OS" = "windows" ]; then
    $MAKE lib PRIMME_WITH_HALF=no PRIMME_WITH_FLOAT=no CFLAGS="-O2" SHELL=bash
else
    $MAKE lib PRIMME_WITH_HALF=no PRIMME_WITH_FLOAT=no CFLAGS="-O2 -fPIC"
fi

# Link shared library
OUTPUT_DIR="$SCRIPT_DIR/src/main/resources/native/$PLATFORM"
mkdir -p "$OUTPUT_DIR"

if [ "$PLATFORM_OS" = "macos" ]; then
    cc -shared -o "$OUTPUT_DIR/libprimme.dylib" \
        -Wl,-force_load,lib/libprimme.a \
        -framework Accelerate -lm
elif [ "$PLATFORM_OS" = "windows" ]; then
    # Windows: statically link everything into a single primme.dll
    cd "$WORK/primme"

    # Find static OpenBLAS from MSYS2 package
    OPENBLAS_LIB=$(find /mingw64/lib -name "libopenblas*.a" -not -name "*.dll.a" | head -1)
    if [ -z "$OPENBLAS_LIB" ]; then
        echo "Error: static OpenBLAS not found. Available files:"
        ls -la /mingw64/lib/libopenblas* 2>/dev/null || echo "  (none)"
        exit 1
    fi
    echo "Using OpenBLAS: $OPENBLAS_LIB"

    # Find static Fortran runtime
    GFORTRAN_A=$(gfortran -print-file-name=libgfortran.a 2>/dev/null)
    QUADMATH_A=$(gfortran -print-file-name=libquadmath.a 2>/dev/null)
    FORTRAN_LIBS=""
    [ -f "$GFORTRAN_A" ] && FORTRAN_LIBS="$FORTRAN_LIBS $GFORTRAN_A"
    [ -f "$QUADMATH_A" ] && FORTRAN_LIBS="$FORTRAN_LIBS $QUADMATH_A"
    echo "Fortran libs: $FORTRAN_LIBS"

    # Static link everything to produce a self-contained DLL
    GOMP_A=$(gcc -print-file-name=libgomp.a 2>/dev/null)
    PTHREAD_A=$(gcc -print-file-name=libpthread.a 2>/dev/null)
    EXTRA_LIBS=""
    [ -f "$GOMP_A" ] && EXTRA_LIBS="$EXTRA_LIBS $GOMP_A"
    [ -f "$PTHREAD_A" ] && EXTRA_LIBS="$EXTRA_LIBS $PTHREAD_A"

    gcc -shared -o "$OUTPUT_DIR/primme.dll" \
        -Wl,--whole-archive lib/libprimme.a "$OPENBLAS_LIB" -Wl,--no-whole-archive \
        $FORTRAN_LIBS $EXTRA_LIBS \
        -static-libgcc \
        -lm

    strip "$OUTPUT_DIR/primme.dll" 2>/dev/null || true
else
    # Linux: build OpenBLAS from source (sequential, pure C)
    if [ ! -d "$WORK/openblas" ]; then
        git clone --depth 1 --branch v0.3.32 https://github.com/OpenMathLib/OpenBLAS.git "$WORK/openblas"
    fi
    cd "$WORK/openblas"
    OPENBLAS_OPTS="USE_THREAD=0 USE_OPENMP=0 NOFORTRAN=1 C_LAPACK=1 DYNAMIC_ARCH=1"
    if [ "$PLATFORM_ARCH" = "aarch64" ]; then
        OPENBLAS_OPTS="$OPENBLAS_OPTS TARGET=ARMV8"
    fi
    $MAKE -j$(nproc) $OPENBLAS_OPTS PREFIX="$WORK/openblas-install"
    $MAKE PREFIX="$WORK/openblas-install" install

    cd "$WORK/primme"

    gcc -shared -o "$OUTPUT_DIR/libprimme.so" \
        -Wl,--whole-archive lib/libprimme.a "$WORK/openblas-install/lib/libopenblas.a" -Wl,--no-whole-archive \
        -lm -lpthread

    strip "$OUTPUT_DIR/libprimme.so" 2>/dev/null || true
fi

echo "Built: $OUTPUT_DIR"
ls -lh "$OUTPUT_DIR"
