#!/usr/bin/env python3
"""Analyze shader interfaces (descriptors, push constants, bindings)."""

import subprocess
import os
import json
import re
from collections import defaultdict

def analyze_shader(path):
    """Analyze a single shader's interface."""
    result = subprocess.run(
        ["/opt/homebrew/bin/spirv-dis", "--no-color", path],
        capture_output=True,
        text=True
    )
    
    if result.returncode != 0:
        return None
    
    disasm = result.stdout
    
    analysis = {
        "file": os.path.basename(path),
        "entry_point": None,
        "execution_mode": None,
        "push_constants": [],
        "descriptors": [],
        "storage_buffers": [],
        "uniform_buffers": [],
        "images": [],
        "samplers": [],
        "local_size": None,
    }
    
    lines = disasm.split('\n')
    
    for line in lines:
        # Entry point
        if 'OpEntryPoint' in line:
            match = re.search(r'OpEntryPoint\s+(\w+)\s+\%\w+\s+"(\w+)"', line)
            if match:
                analysis["entry_point"] = {
                    "execution_model": match.group(1),
                    "name": match.group(2)
                }
        
        # Execution mode / LocalSize
        if 'OpExecutionMode' in line:
            if 'LocalSize' in line:
                match = re.search(r'LocalSize\s+(\d+)\s+(\d+)\s+(\d+)', line)
                if match:
                    analysis["local_size"] = {
                        "x": int(match.group(1)),
                        "y": int(match.group(2)),
                        "z": int(match.group(3))
                    }
            else:
                analysis["execution_mode"] = line.strip()
        
        # Push constants
        if 'PushConstant' in line:
            match = re.search(r'OpVariable\s+\S+\s+(\S+)\s+PushConstant', line)
            if match:
                analysis["push_constants"].append({
                    "line": line.strip()
                })
        
        # Descriptors (Binding + DescriptorSet)
        if 'OpDecorate' in line and 'Binding' in line:
            match = re.search(r'OpDecorate\s+\%(\d+)\s+Binding\s+(\d+)', line)
            if match:
                var_id = match.group(1)
                binding = int(match.group(2))
                # Find the corresponding DescriptorSet
                for l in lines:
                    if f'OpDecorate %{var_id} DescriptorSet' in l:
                        ds_match = re.search(r'DescriptorSet\s+(\d+)', l)
                        if ds_match:
                            descriptor_set = int(ds_match.group(1))
                            analysis["descriptors"].append({
                                "id": var_id,
                                "binding": binding,
                                "descriptor_set": descriptor_set
                            })
                            break
        
        # Storage buffers
        if 'StorageBuffer' in line or ('BufferBlock' in line and 'NonReadable' in disasm):
            analysis["storage_buffers"].append(line.strip())
        
        # Uniform buffers
        if 'Uniform' in line and 'OpVariable' in line:
            analysis["uniform_buffers"].append(line.strip())
        
        # Images
        if 'OpTypeImage' in line:
            analysis["images"].append(line.strip())
        
        # Samplers
        if 'OpTypeSampler' in line or 'SampledImage' in line:
            analysis["samplers"].append(line.strip())
    
    return analysis

def main():
    raw_dir = "raw"
    spv_files = sorted([f for f in os.listdir(raw_dir) if f.endswith('.spv')])
    
    analyses = []
    
    for spv_file in spv_files:
        path = os.path.join(raw_dir, spv_file)
        analysis = analyze_shader(path)
        if analysis:
            analyses.append(analysis)
    
    # Summary statistics
    print("=== Shader Interface Analysis ===\n")
    
    # Entry points
    entry_points = defaultdict(int)
    for a in analyses:
        if a["entry_point"]:
            entry_points[a["entry_point"]["name"]] += 1
    
    print("Entry point names:")
    for name, count in sorted(entry_points.items()):
        print(f"  {name}: {count} shaders")
    print()
    
    # Local sizes
    local_sizes = defaultdict(int)
    for a in analyses:
        if a["local_size"]:
            ls = f"{a['local_size']['x']}x{a['local_size']['y']}x{a['local_size']['z']}"
            local_sizes[ls] += 1
    
    print("Local size distribution:")
    for ls, count in sorted(local_sizes.items()):
        print(f"  {ls}: {count} shaders")
    print()
    
    # Descriptor usage
    desc_counts = defaultdict(lambda: defaultdict(int))
    for a in analyses:
        for d in a["descriptors"]:
            key = f"Set {d['descriptor_set']}, Binding {d['binding']}"
            desc_counts[a["file"]][key] += 1
    
    print("Descriptor usage (Set, Binding):")
    # Aggregate across all shaders
    all_bindings = defaultdict(int)
    for a in analyses:
        for d in a["descriptors"]:
            key = f"Set {d['descriptor_set']}, Binding {d['binding']}"
            all_bindings[key] += 1
    
    for binding, count in sorted(all_bindings.items(), key=lambda x: (int(x[0].split(',')[0].split()[1]), int(x[0].split(',')[1].split()[1]))):
        print(f"  {binding}: {count} shaders")
    print()
    
    # Push constant usage
    push_const_shaders = [a for a in analyses if a["push_constants"]]
    print(f"Shaders using push constants: {len(push_const_shaders)}/{len(analyses)}")
    
    # Storage buffer usage
    ssbo_shaders = [a for a in analyses if a["storage_buffers"]]
    print(f"Shaders using storage buffers: {len(ssbo_shaders)}/{len(analyses)}")
    
    # Image usage
    image_shaders = [a for a in analyses if a["images"]]
    print(f"Shaders using images: {len(image_shaders)}/{len(analyses)}")
    
    # Detailed analysis for a few shaders
    print("\n=== Detailed Analysis (shader_000.spv) ===")
    shader_000 = [a for a in analyses if a["file"] == "shader_000.spv"]
    if shader_000:
        s = shader_000[0]
        print(f"Entry point: {s['entry_point']}")
        print(f"Local size: {s['local_size']}")
        print(f"Descriptors: {len(s['descriptors'])}")
        for d in s['descriptors']:
            print(f"  - Set {d['descriptor_set']}, Binding {d['binding']}")
        print(f"Push constants: {len(s['push_constants'])})")
    
    # Save analysis
    with open("interface_analysis.json", "w") as f:
        json.dump(analyses, f, indent=2)
    print(f"\nSaved detailed analysis to interface_analysis.json")
    
    return analyses

if __name__ == "__main__":
    main()
