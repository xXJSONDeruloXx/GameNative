#!/usr/bin/env python3
"""
Validate SPIR-V shaders embedded in the layer.
This script checks that the embedded shaders are valid SPIR-V.
"""

import subprocess
import sys
import tempfile
import os

# Shader registry from shaders_embedded.hpp
# This mirrors the SHADER_REGISTRY in the header
SHADER_REGISTRY = {
    "shader_000.spv": 32388,
    "shader_001.spv": 16504,
    "shader_003.spv": 22696,
    "shader_004.spv": 18208,
    "shader_005.spv": 26052,
    "shader_006.spv": 18240,
    "shader_007.spv": 23848,
    "shader_008.spv": 19656,
    "shader_009.spv": 27112,
    "shader_010.spv": 19884,
    "shader_011.spv": 27144,
    # ... more shaders would be listed here
}

def check_spirv_val():
    """Check if spirv-val is available."""
    try:
        result = subprocess.run(["spirv-val", "--version"], 
                              capture_output=True, text=True)
        return result.returncode == 0
    except FileNotFoundError:
        return False

def validate_shader(shader_name, shader_data):
    """Validate a single SPIR-V shader."""
    # Check magic number
    if len(shader_data) < 4:
        return False, "Shader too small"
    
    magic = int.from_bytes(shader_data[:4], byteorder='little')
    if magic != 0x07230203:
        return False, f"Invalid magic number: 0x{magic:08x}"
    
    # Write to temp file for spirv-val
    with tempfile.NamedTemporaryFile(suffix='.spv', delete=False) as f:
        f.write(shader_data)
        temp_path = f.name
    
    try:
        result = subprocess.run(
            ["spirv-val", "--target-env", "vulkan1.0", temp_path],
            capture_output=True, text=True
        )
        if result.returncode == 0:
            return True, "Valid"
        else:
            return False, result.stderr.strip()
    except Exception as e:
        return False, str(e)
    finally:
        os.unlink(temp_path)

def main():
    print("=== GN Framegen Layer - Shader Validation ===\n")
    
    # Check for spirv-val
    if not check_spirv_val():
        print("WARNING: spirv-val not found in PATH")
        print("  Install from: https://github.com/KhronosGroup/SPIRV-Tools")
        print("  Or run: apt-get install spirv-tools (Ubuntu/Debian)")
        print("  Or run: brew install spirv-tools (macOS)\n")
        print("Continuing with basic checks only...\n")
        spirv_val_available = False
    else:
        print("✓ spirv-val found\n")
        spirv_val_available = True
    
    # Read the embedded shaders header
    header_path = os.path.join(os.path.dirname(__file__), "src", "shaders_embedded.hpp")
    
    if not os.path.exists(header_path):
        print(f"ERROR: Shaders header not found: {header_path}")
        return 1
    
    print(f"Reading shaders from: {header_path}\n")
    
    with open(header_path, 'rb') as f:
        header_content = f.read()
    
    # Count shader arrays in the file
    shader_count = header_content.count(b"constexpr uint8_t SHADER_")
    print(f"Found {shader_count} shader arrays in header\n")
    
    # Basic validation - check magic numbers
    valid_count = 0
    invalid_count = 0
    
    # Find all shader arrays and validate
    import re
    
    # Pattern to match shader definitions
    pattern = rb'constexpr uint8_t (SHADER_\w+)\[\] = \{([^}]+)\};'
    matches = re.findall(pattern, header_content, re.DOTALL)
    
    print(f"Validating {len(matches)} shaders:\n")
    print(f"{'Shader':<20} {'Size':<10} {'Magic':<10} {'Status'}")
    print("-" * 60)
    
    for shader_name, shader_data_raw in matches:
        shader_name = shader_name.decode('utf-8')
        
        # Parse hex bytes
        try:
            hex_bytes = re.findall(rb'0x[0-9a-fA-F]{2}', shader_data_raw)
            shader_data = bytes([int(b.decode()[2:], 16) for b in hex_bytes])
        except Exception as e:
            print(f"{shader_name:<20} {'ERROR':<10} {'N/A':<10} Parse failed: {e}")
            invalid_count += 1
            continue
        
        # Check magic number
        if len(shader_data) >= 4:
            magic = int.from_bytes(shader_data[:4], byteorder='little')
            magic_ok = magic == 0x07230203
        else:
            magic = 0
            magic_ok = False
        
        # Validate with spirv-val if available
        if spirv_val_available and magic_ok:
            is_valid, message = validate_shader(shader_name, shader_data)
        else:
            is_valid = magic_ok
            message = "Valid magic" if magic_ok else "Invalid magic"
        
        status = "✓ VALID" if is_valid else f"✗ {message}"
        magic_str = f"0x{magic:08x}"
        
        print(f"{shader_name:<20} {len(shader_data):<10} {magic_str:<10} {status}")
        
        if is_valid:
            valid_count += 1
        else:
            invalid_count += 1
    
    print("\n" + "=" * 60)
    print(f"Validation complete:")
    print(f"  Valid:   {valid_count}")
    print(f"  Invalid: {invalid_count}")
    print(f"  Total:   {valid_count + invalid_count}")
    
    if invalid_count == 0:
        print("\n✓ All shaders validated successfully!")
        return 0
    else:
        print(f"\n✗ {invalid_count} shader(s) failed validation")
        return 1

if __name__ == "__main__":
    sys.exit(main())
