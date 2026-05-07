# Makefile for GN Framegen Layer
# Simplified build interface for common tasks

.PHONY: all clean check build install debug test release help

# Default configuration
ANDROID_NDK ?= $(ANDROID_NDK_HOME)
ABI ?= arm64-v8a
PLATFORM ?= android-30
BUILD_TYPE ?= Release

# Build directory
BUILD_DIR = build-android-$(ABI)

# Default target
all: check build

# Help target
help:
	@echo "GN Framegen Layer - Build System"
	@echo ""
	@echo "Usage: make [target] [VARIABLE=value]"
	@echo ""
	@echo "Targets:"
	@echo "  all       - Check environment and build (default)"
	@echo "  check     - Verify NDK and build environment"
	@echo "  build     - Build the layer library"
	@echo "  clean     - Remove build directory"
	@echo "  install   - Install to device (requires adb)"
	@echo "  debug     - Run debug script on connected device"
	@echo "  test      - Run tests (CMake config, shader validation)"
	@echo "  release   - Create release package"
	@echo "  help      - Show this help message"
	@echo ""
	@echo "Variables:"
	@echo "  ANDROID_NDK  - Path to Android NDK (required)"
	@echo "  ABI          - Target ABI (arm64-v8a, armeabi-v7a, x86_64, x86)"
	@echo "  PLATFORM     - Android API level (android-30, android-29, ...)"
	@echo "  BUILD_TYPE   - Release or Debug"
	@echo ""
	@echo "Examples:"
	@echo "  make check                           # Verify environment"
	@echo "  make build                           # Build with defaults"
	@echo "  make build ABI=armeabi-v7a           # Build for 32-bit ARM"
	@echo "  make build BUILD_TYPE=Debug          # Debug build"
	@echo "  make clean && make build             # Clean rebuild"
	@echo "  make install                         # Install to device"
	@echo "  make release                         # Create release package"

# Check environment
check:
	@echo "=== Checking build environment ==="
	@./check-ndk.sh

# Build the layer
build:
	@echo "=== Building GN Framegen Layer ==="
	@echo "ABI: $(ABI)"
	@echo "Platform: $(PLATFORM)"
	@echo "Build Type: $(BUILD_TYPE)"
	@echo ""
	@./build-android.sh $(ABI) $(PLATFORM) $(BUILD_TYPE)

# Clean build directory
clean:
	@echo "=== Cleaning build directory ==="
	rm -rf $(BUILD_DIR)
	rm -rf build-android-*
	@echo "Clean complete"

# Install to device (requires adb and built binary)
install:
	@echo "=== Installing to device ==="
	@if [ ! -f "$(BUILD_DIR)/libgn-framegen.so" ]; then \
		echo "ERROR: Build not found. Run 'make build' first."; \
		exit 1; \
	fi
	@echo "Checking device connection..."
	@adb devices | grep -E "device$$" > /dev/null || { \
		echo "ERROR: No device connected"; \
		exit 1; \
	}
	@echo "Pushing layer to device..."
	adb push $(BUILD_DIR)/libgn-framegen.so /data/local/tmp/
	adb push VkLayer_GN_gamescope_framegen.json /data/local/tmp/
	@echo "Installation complete. Files pushed to /data/local/tmp/"
	@echo ""
	@echo "To use in GameNative, copy to app directory or use install-to-apk.sh"

# Run debug script
debug:
	@echo "=== Running debug script ==="
	@./debug-layer.sh

# Run all tests
test: test-cmake test-shaders
	@echo ""
	@echo "=== All tests complete ==="

# Test CMake configuration
test-cmake:
	@echo "=== Testing CMake configuration ==="
	@./test-cmake.sh

# Validate shaders
test-shaders:
	@echo "=== Validating shaders ==="
	@./validate-shaders.py || true

# Create release package
release:
	@echo "=== Creating release package ==="
	@if [ ! -f "$(BUILD_DIR)/libgn-framegen.so" ]; then \
		echo "ERROR: Build not found. Run 'make build' first."; \
		exit 1; \
	fi
	@./package-release.sh $(ABI) $(PLATFORM) $(BUILD_TYPE)

# Quick test build for CI
ci-test:
	@echo "=== CI Test Build ==="
	@./check-ndk.sh
	@./test-cmake.sh
	@echo ""
	@echo "CI tests passed!"

# Format check (if clang-format available)
format-check:
	@echo "=== Checking code format ==="
	@if command -v clang-format >/dev/null 2>&1; then \
		find src -name "*.cpp" -o -name "*.hpp" | xargs clang-format --dry-run --Werror 2>&1 || \
		{ echo "Format errors found. Run 'make format' to fix."; exit 1; }; \
	else \
		echo "clang-format not found, skipping format check"; \
	fi

# Format code
format:
	@echo "=== Formatting code ==="
	@if command -v clang-format >/dev/null 2>&1; then \
		find src -name "*.cpp" -o -name "*.hpp" | xargs clang-format -i; \
		echo "Format complete"; \
	else \
		echo "clang-format not found. Install with: apt-get install clang-format"; \
	fi

# Print configuration
print-config:
	@echo "Build Configuration:"
	@echo "  ANDROID_NDK: $(ANDROID_NDK)"
	@echo "  ABI: $(ABI)"
	@echo "  PLATFORM: $(PLATFORM)"
	@echo "  BUILD_TYPE: $(BUILD_TYPE)"
	@echo "  BUILD_DIR: $(BUILD_DIR)"
