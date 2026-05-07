#!/usr/bin/env python3
"""
Extract SPIR-V shaders from libGameScopeVK.so

SPIR-V magic number: 0x07230203 (little-endian: 03 23 02 07)
GameScopeVK contains 54 embedded shaders in .rodata section.
"""

import struct
import sys
import os

SPIRV_MAGIC = b'\x03\x23\x02\x07'  # 0x07230203 in little-endian

def extract_shaders(input_path, output_dir):
    """Extract SPIR-V shaders from binary file."""
    
    with open(input_path, 'rb') as f:
        data = f.read()
    
    # Find all SPIR-V magic numbers
    offsets = []
    start = 0
    while True:
        idx = data.find(SPIRV_MAGIC, start)
        if idx == -1:
            break
        offsets.append(idx)
        start = idx + 1
    
    print(f"Found {len(offsets)} potential SPIR-V shaders")
    
    os.makedirs(output_dir, exist_ok=True)
    
    # Extract each shader
    for i, offset in enumerate(offsets):
        # Determine shader size - look for next magic or reasonable boundary
        if i < len(offsets) - 1:
            next_offset = offsets[i + 1]
            max_size = next_offset - offset
        else:
            max_size = len(data) - offset
        
        # SPIR-V files have size encoded at offset 4 (words 1-2)
        # But for safety, we'll use a maximum reasonable size
        # and trim based on validation later
        
        # Read first 20 words to determine actual size
        header = data[offset:offset+20*4]
        if len(header) < 20*4:
            continue
            
        words = struct.unpack('<' + 'I'*20, header)
        
        # Word count is determined by the module length, which is variable
        # For now, extract up to a reasonable size or until next pattern
        extract_size = min(max_size, 65536)  # Max 64KB per shader
        
        shader_data = data[offset:offset+extract_size]
        
        # Write raw shader
        output_path = os.path.join(output_dir, f'shader_{i:03d}.spv')
        with open(output_path, 'wb') as f:
            f.write(shader_data)
        
        print(f"  Shader {i:3d}: offset=0x{offset:06x}, size=~{extract_size} bytes -> {output_path}")
    
    return len(offsets)

if __name__ == '__main__':
    if len(sys.argv) < 2:
        # Default paths
        input_path = '/Users/kurt/Developer/GameNative/app/src/main/assets/gamescope_vk/android_arm64_v8a/libGameScopeVK.so'
        output_dir = os.path.dirname(os.path.abspath(__file__))
    else:
        input_path = sys.argv[1]
        output_dir = sys.argv[2] if len(sys.argv) > 2 else 'extracted'
    
    count = extract_shaders(input_path, output_dir)
    print(f"\nExtracted {count} shaders to: {output_dir}")
