#!/usr/bin/env python3
"""Validate all extracted SPIR-V shaders and generate summary."""

import subprocess
import os
import json

def validate_shaders():
    raw_dir = "raw"
    results = []
    
    spv_files = sorted([f for f in os.listdir(raw_dir) if f.endswith('.spv')])
    
    for spv_file in spv_files:
        path = os.path.join(raw_dir, spv_file)
        result = subprocess.run(
            ["/opt/homebrew/bin/spirv-val", "--target-env", "vulkan1.3", path],
            capture_output=True,
            text=True
        )
        
        # Get file size
        size = os.path.getsize(path)
        
        # Get disassembly info
        disasm = subprocess.run(
            ["/opt/homebrew/bin/spirv-dis", "--no-color", path],
            capture_output=True,
            text=True
        )
        
        info = {
            "file": spv_file,
            "size": size,
            "valid": result.returncode == 0,
            "error": result.stderr if result.returncode != 0 else None
        }
        
        # Parse header info from disassembly
        if disasm.returncode == 0:
            lines = disasm.stdout.split('\n')[:20]
            for line in lines:
                if 'Version:' in line:
                    info["version"] = line.split(':')[1].strip()
                elif 'Generator:' in line:
                    info["generator"] = line.split(':')[1].strip()
                elif 'Bound:' in line:
                    info["bound"] = int(line.split(':')[1].strip().split()[0])
                elif 'LocalSize' in line:
                    # Extract local size from OpExecutionMode
                    parts = line.split()
                    if 'LocalSize' in parts:
                        idx = parts.index('LocalSize')
                        info["local_size"] = f"{parts[idx+1]} {parts[idx+2]} {parts[idx+3]}"
        
        results.append(info)
    
    # Summary
    valid_count = sum(1 for r in results if r["valid"])
    invalid_count = len(results) - valid_count
    
    print(f"=== Validation Summary ===")
    print(f"Total shaders: {len(results)}")
    print(f"Valid: {valid_count}")
    print(f"Invalid (likely truncated): {invalid_count}")
    print()
    
    # Sort by size
    by_size = sorted(results, key=lambda x: x["size"], reverse=True)
    print("Largest shaders (possibly truncated):")
    for r in by_size[:5]:
        status = "✓" if r["valid"] else "✗"
        print(f"  {status} {r['file']}: {r['size']:,} bytes")
    print()
    
    # Local sizes
    local_sizes = {}
    for r in results:
        if "local_size" in r:
            ls = r["local_size"]
            local_sizes[ls] = local_sizes.get(ls, 0) + 1
    
    print("LocalSize distribution:")
    for ls, count in sorted(local_sizes.items()):
        print(f"  {ls}: {count} shaders")
    print()
    
    # Invalid shaders
    invalid = [r for r in results if not r["valid"]]
    if invalid:
        print("Invalid shaders:")
        for r in invalid:
            print(f"  {r['file']}: {r['size']:,} bytes")
            if r["error"]:
                print(f"    Error: {r['error'][:100]}")
    
    # Save JSON
    with open("shader_analysis.json", "w") as f:
        json.dump(results, f, indent=2)
    print(f"\nSaved analysis to shader_analysis.json")
    
    return results

if __name__ == "__main__":
    validate_shaders()
