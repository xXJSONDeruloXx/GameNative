# CRIU / GPU-inclusive restore research for GameNative teardown-resume

_Last updated: 2026-04-11_

## Goal

Research whether there is any realistic modern path to:

1. checkpoint a running GameNative game,
2. fully tear down the app/runtime,
3. later restore the game from disk,
4. including enough graphics/runtime state to actually resume.

This note separates **direct local evidence** from **external/public research** and then maps both back onto GameNative.

---

## Direct local evidence from Dreams of Aether experiments

### What was tested

Using the attached ADB device and the already-installed **Dreams of Aether** build, I added two experimental tools:

- `tools/experimental_wine_snapshot.py`
- `tools/experimental_wine_restore.py`

The experiment flow was:

1. identify the GameNative Wine process tree,
2. freeze it with `SIGSTOP`,
3. dump memory regions from `/proc/<pid>/mem`,
4. `am force-stop app.gamenative`,
5. relaunch GameNative,
6. relaunch Dreams of Aether to create a fresh Wine tree,
7. freeze the new tree,
8. write dumped bytes back into matching `/proc/<pid>/mem` regions,
9. resume the processes and observe outcome.

### Direct findings

#### 1) Same-UID memory access is possible on this device/build

On this debug build of GameNative, the app UID was able to:

- read another Wine child process's `/proc/<pid>/maps`
- read actual bytes from `/proc/<pid>/mem`
- write bytes back into `/proc/<pid>/mem`

That means the core low-level primitive is **not blocked** here.

#### 2) In-place suspend/resume already exists and works

GameNative/Winlator already has suspend/resume hooks using `SIGSTOP` / `SIGCONT` for Wine processes.

Relevant local code:

- `app/src/main/java/com/winlator/core/ProcessHelper.java`
- `app/src/main/java/com/winlator/xenvironment/XEnvironment.java`
- `app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java`

This supports **RAM-only suspension**, not durable disk-backed restoration.

#### 3) Raw dump + raw write-back after full teardown did **not** restore the game

Two full teardown/restore attempts were run.

##### Attempt A

Snapshot:
- `artifacts/experimental-snapshots/doa-hidden-prompt`
- size: about **736 MB**

Result:
- large amount of memory wrote back successfully
- after resume, the Wine/game processes died
- GameNative fell back to the library UI

##### Attempt B

Snapshot:
- `artifacts/experimental-snapshots/doa-loading-fulltree`
- size: about **1.5 GB**
- explicitly included `DreamsOfAether.exe`

Restore summary:
- pairs matched: **14**
- regions written: **1382**
- regions missing: **1987**
- bytes written total: **768,622,592**
- `DreamsOfAether.exe` alone:
  - regions written: **460**
  - regions missing: **459**
  - bytes written: **489,865,216**

Observed result:
- after resume, only `app.gamenative` remained alive
- Wine/game processes were gone
- app returned to the library UI

### Direct conclusion from local testing

**Raw process memory is not enough to restore a GameNative game after full teardown.**

Even with successful memory dump + write-back, the recreated Wine runtime did not survive resume.

---

## External/public research

## 1) CRIU still does not solve normal X11 + GPU app restore by itself

### Direct evidence

CRIU official docs say:

- dumping/restoring an app connected to a real X server is not currently supported because part of its state lives in the X server
- tasks with opened or mmap'd char/block devices generally cannot be dumped, except for specific virtual-device cases

Sources:
- CRIU "What cannot be checkpointed": https://criu.org/What_cannot_be_checkpointed
- CRIU "X applications": https://criu.org/X_applications

### Why this matters for GameNative

GameNative games run through:

- Wine process tree
- X11/X server path
- GPU/graphics driver stack
- Android userspace/device files

That is almost the exact category CRIU warns about.

---

## 2) The big 2024-2025 development is **GPU plugins**, not generic game restore

### NVIDIA path: `cuda-checkpoint` + CRIU

Recent NVIDIA work is real and important:

- 2024 NVIDIA introduced `cuda-checkpoint` for checkpointing CUDA apps with CRIU
- CUDA 12.8 / 2025 docs added official checkpoint/restore APIs in the CUDA driver API
- newer docs/repos mention process-tree support and GPU migration features

Sources:
- NVIDIA technical blog: https://developer.nvidia.com/blog/checkpointing-cuda-applications-with-criu/
- CUDA Driver API checkpointing docs: https://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__CHECKPOINT.html
- NVIDIA repo: https://github.com/NVIDIA/cuda-checkpoint
- CUDA features archive 12.8.1 / 13.x

### Important limitation

This is **not** generic GPU/game checkpointing.

It is a vendor-managed flow for **CUDA workloads** where the GPU state is cooperatively drained/released before CRIU runs.

That is very different from:

- arbitrary Vulkan/OpenGL game state
- Wine graphics state
- Android/mobile GPU game teardown-resume

### Best interpretation

This is proof that **GPU-inclusive checkpointing is becoming practical when the vendor owns the stack and exposes explicit checkpoint APIs**.

It is **not** proof that a Windows game in Wine on Android can be restored the same way.

---

## 3) AMD also has an official CRIU plugin path, but for ROCm/HPC-style workloads

AMD public docs say:

- `amdgpu_plugin` was upstreamed into CRIU
- ROCm checkpoint/restore support exists
- some docs mention single- and multi-GPU support, cross-system restore, containers, and ML frameworks
- release notes still show hardware/OS/version-specific issues in 2025

Sources:
- CRIU GPU checkpointing: https://criu.org/GPU_Checkpointing
- ROCm changelog / CRIU support notes: https://rocm.docs.amd.com/en/docs-6.1.0/about/CHANGELOG.html
- AMD GPU Driver release notes: https://instinct.docs.amd.com/projects/amdgpu-docs/en/docs-30.10/documentation/release-notes.html
- ROCm 7.11 preview notes: https://rocm.docs.amd.com/en/7.11.0-preview/about/release-notes.html

### Best interpretation

AMD and NVIDIA both now validate the same broad direction:

> GPU checkpoint/restore can work when there is a vendor-specific GPU-aware path.

But those paths target:

- Linux server/HPC workloads
- CUDA/ROCm compute
- supported hardware/drivers

Not:

- Wine desktop games
- Android devices
- X11-backed interactive graphics applications

---

## 4) CRIUgpu is the most interesting recent development, but still not a direct GameNative path

A 2025 paper, **CRIUgpu: Transparent Checkpointing of GPU-Accelerated Workloads**, claims:

- transparent checkpointing for CUDA and ROCm workloads
- no steady-state runtime overhead
- support upstreamed into CRIU 4.0
- much better recovery behavior than older interception-based approaches

Sources:
- arXiv: https://arxiv.org/abs/2502.16631
- HTML mirror: https://ar5iv.labs.arxiv.org/html/2502.16631v1
- CRIU 4.0 release notes: https://criu.org/Download/criu/4.0

### Why this is interesting

This is the clearest sign that **GPU-aware checkpoint/restore is materially advancing in upstream Linux**.

### Why it still does not give GameNative a near-term solution

The workloads discussed are still essentially:

- CUDA / ROCm
- data center / HPC / ML
- supported Linux systems and drivers

The paper is evidence that the field is moving, but not that arbitrary game+graphics session restore is solved.

---

## 5) DMTCP does not look like the answer here

Recent public DMTCP evidence shows:

- OpenGL support is old / plugin-based
- CUDA checkpointing is still described as experimental
- no authoritative Vulkan checkpoint/restart support was found through 2025

Sources:
- DMTCP news: https://dmtcp.github.io/news.html
- DMTCP home: https://dmtcp.sourceforge.io/
- DMTCP quick start note about X/OpenGL limitations

### Best interpretation

DMTCP currently looks less promising than vendor-backed CRIU GPU work for this problem domain.

---

## 6) Public research is trending toward vendor-managed GPU restore, not generic game save-state

Another promising research direction found:

- `SJTU-IPADS/PhoenixOS`
- SOSP'25 paper claims concurrent OS-level GPU checkpoint/restore and future cross-node migration direction

Sources:
- GitHub: https://github.com/SJTU-IPADS/PhoenixOS
- paper: https://ipads.se.sjtu.edu.cn/zh/publications/sosp25-wei.pdf

### Best interpretation

This further supports the same pattern:

- checkpoint/restore is becoming more GPU-aware,
- but mainly in research systems and compute/server contexts,
- not yet as a drop-in answer for consumer games running through Wine.

---

## 7) VM / vGPU migration is a more complete graphics-state path than CRIU, but it is the wrong level for GameNative

NVIDIA's current vGPU migration docs say live migration replicates:

- VM system memory
- CPU execution state
- vGPU framebuffer
- vGPU execution state

and then switches execution to the destination host with minimal interruption.

Sources:
- NVIDIA vGPU features: https://docs.nvidia.com/vgpu/knowledge-base/latest/vgpu-features.html
- NVIDIA vGPU user guide: https://docs.nvidia.com/vgpu/latest/grid-vgpu-user-guide/
- R550 whats-new: https://docs.nvidia.com/vgpu/17.0/whats-new-vgpu/index.html
- R570 whats-new: https://docs.nvidia.com/vgpu/18.0/whats-new-vgpu/index.html
- Windows Server release notes: https://docs.nvidia.com/vgpu/latest/grid-vgpu-release-notes-microsoft-windows-server/index.html

### Why this matters

This is one of the few public, productized examples of **graphics-inclusive migration after teardown/switchover** that appears to preserve enough GPU execution state for applications to continue.

### Why it still does not solve GameNative

That mechanism works at the **VM / hypervisor / vGPU** layer, not at the user-space process layer.

GameNative is not running each game inside a migratable VM with a vendor-managed vGPU. It is running:

- Android app process
- Wine process tree
- mobile GPU stack
- X11/Winlator-like userspace runtime

So this is evidence for a broader architectural truth:

> Full graphics-state survival becomes much more realistic when the checkpoint boundary is the whole VM, not a single process tree.

But it does not provide a near-term implementation path for GameNative.

---

## 8) Vulkan exposes diagnostics, not full restore

Recent Khronos/Vulkan materials do **not** point to a standardized Vulkan checkpoint/restore facility for full application state.

What exists publicly:

- `VK_NV_device_diagnostic_checkpoints`
- `VK_AMD_buffer_marker`
- device-loss diagnostics and post-mortem markers

What does **not** exist publicly as a standard Vulkan feature:

- save full GPU/device/application state
- later restore that state after teardown
- resume execution from the same point automatically

Sources:
- Khronos forum discussion: https://community.khronos.org/t/after-a-vk-device-lost-what-options-are-there/103923
- Vulkan spec: https://github.khronos.org/Vulkan-Site/spec/latest/chapters/devsandqueues.html
- `VK_NV_device_diagnostic_checkpoints`: https://docs.vulkan.org/refpages/latest/refpages/source/VK_NV_device_diagnostic_checkpoints.html

### Best interpretation

For Vulkan today, recovery is still fundamentally **application-managed recreation**, not transparent restore of a prior live device state.

That is another major reason generic game teardown-resume remains so hard.

---

## 9) Android app freezer is not a teardown-restore mechanism

Android's cached-app freezer is real, but official AOSP documentation makes clear that it is:

- a system-managed cached-process feature
- not a public app API
- not equivalent to durable checkpoint/restore

Important documented behavior:

- Android says the freezer exposes **no official APIs** and uses hidden system APIs internally
- synchronous Binder calls into a frozen app can cause Android to **kill** that frozen remote process
- asynchronous calls may be buffered until unfreeze, but can still fail if buffers overflow
- public SDK support is mainly for **detection/mitigation**, not saving and restoring full app state
- `restoreCheckpoint` in Android docs belongs to **userdata checkpoint/OTA rollback**, not process-state restore

Sources:
- Cached apps freezer (AOSP): https://source.android.com/docs/core/perf/cached-apps-freezer
- Binder freezer guidance: https://source.android.com/docs/core/architecture/ipc/binder-freezer
- `IBinder` frozen-state callbacks: https://developer.android.com/reference/kotlin/android/os/IBinder
- `RemoteCallbackList`: https://developer.android.com/reference/android/os/RemoteCallbackList
- `ApplicationExitInfo`: https://developer.android.com/reference/android/app/ApplicationExitInfo
- User Data Checkpoint: https://source.android.com/docs/core/ota/user-data-checkpoint

### Best interpretation

Android freezer may help a best-effort **RAM-only background pause** strategy, but it is not a path to:

- full teardown,
- disk-backed snapshot,
- later game restoration.

---

## 10) Public Proton/Wine evidence does not show a checkpoint/restore path

### Proton

Public Proton materials do **not** show a documented CRIU/checkpoint/restore feature:

- Proton repo README shows no CRIU/checkpoint/restore support
- GitHub issue searches for Proton + CRIU return no clear feature discussions

Sources:
- Proton repo: https://github.com/ValveSoftware/Proton
- GitHub search `repo:ValveSoftware/Proton CRIU`

### Wine

Public WineHQ material also does **not** show a supported end-to-end checkpoint/restore feature for Wine process state.

The closest relevant signals are weak/indirect:

- a Wine bug thread referencing Linux support added partly for CRIU-related reasons
- discussion that Wine process state can be inconsistent after crash / SIGKILL

Sources:
- Wine bug 52313 thread: https://list.winehq.org/archives/list/wine-bugs%40list.winehq.org/thread/ZDRXHPMNPC6UWDLWMZJATTYAXW2LHO4D/
- Wine mailing list message: https://list.winehq.org/archives/list/wine-gitlab%40list.winehq.org/message/6Q7YBSZQKQ6W5NANB3GNXFDAGXDKU5MM/

### Best interpretation

If Valve/Proton or Wine had a serious near-term process-checkpoint story for game sessions, some public evidence would likely exist in:

- docs,
- issue trackers,
- release notes,
- or developer discussions.

So far, the absence of that evidence is itself a useful signal.

---

## 11) Android Vulkan still looks like a diagnostics-only story, not restore

Public Vulkan/Android evidence still points to:

- diagnostic checkpoints / markers
- device-loss debugging
- application-managed recreation after device loss

not:

- transparent save/restore of live GPU state,
- or resume-after-teardown on Android.

Sources:
- Vulkan debugging chapter: https://docs.vulkan.org/spec/latest/chapters/debugging.html
- Vulkan devices/queues chapter: https://github.khronos.org/Vulkan-Site/spec/latest/chapters/devsandqueues.html
- Android Vulkan extension matrix: https://developer.android.com/agi/vulkan-extensions

### Best interpretation

On Android Vulkan specifically, this appears to remain a **research gap** rather than an available product/API path.

---

## Paths that look real vs not-real for GameNative

## A) Real today: in-RAM suspend/resume

### Description

Freeze the Wine tree with `SIGSTOP`/`SIGCONT` or a freezer-like mechanism and keep all state in RAM.

### Pros

- already partially implemented
- no serialization/deserialization problem
- preserves process/GPU/X11 relationships as long as the process tree stays alive

### Cons

- not durable across app teardown
- vulnerable to Android killing the app under memory pressure
- still not true "Quick Resume from disk"

### Practical status

**Most realistic near-term path**.

---

## B) Maybe real in the future: vendor-backed GPU-aware Linux checkpointing

### Description

A future world where:

- GPU vendor plugins exist for the actual graphics stack in use
- graphics API resources can be drained/restored
- display/X11/compositor state is captured or reconstructed
- userland runtime (Wine, translators, helper daemons) is plugin-aware

### Why this matters

This is the direction modern Linux checkpointing is actually moving.

### Why it still does not help GameNative now

GameNative would still need all of the following to line up:

- Android support
- mobile GPU vendor support
- Wine support or Wine-aware integration
- X11/compositor support
- likely Box86/Box64 integration

This is far beyond current publicly documented capabilities.

---

## C) Not realistic today: generic CRIU restore of a GameNative game after teardown

### Why

Current evidence says the following state is either external, unstable, or mismatched after teardown:

- X server state
- GPU driver/device state
- Wine server state
- handles/fds/shared-memory objects
- helper process topology
- translated/JIT state

### Practical status

**Not viable with current public tooling for this stack.**

---

## Best current answer

### Direct evidence

From actual local teardown testing on Dreams of Aether:

- raw memory dump works
- raw memory write-back works
- post-teardown restore still fails

### Research evidence

Recent 2024-2025 advances are real, but they are concentrated in:

- **CUDA + CRIU**
- **ROCm + CRIU**
- **GPU vendor plugin paths**
- **HPC / ML / server workloads**

### Combined conclusion

There **are** recent developments in GPU-inclusive checkpointing, but they do **not** currently provide a practical path to full teardown-and-restore for GameNative games on Android.

The most credible near-term productizable feature remains:

- **RAM-only suspension / resume while the process tree stays alive**

not:

- full disk-backed teardown restore.

---

## Follow-up ideas worth researching next

1. **Can GameNative expose a user-facing RAM suspend/resume feature cleanly?**
   - background app
   - pause Wine tree
   - resume if process survives

2. **Can Android app-freezer/cgroup-freezer behavior be used more safely than plain SIGSTOP?**
   - maybe less fragile than per-process signals
   - still not durable across kill

3. **Can any graphics stack used by GameNative be made vendor-aware for checkpointing?**
   - likely only if future Android/mobile GPU vendors expose explicit checkpoint APIs

4. **Would a partial-session restore feature be more realistic?**
   - persist filesystem state + preserve RAM only opportunistically
   - advertise as best-effort resume, not guaranteed restore

---

## Files created during this work

- `tools/experimental_wine_snapshot.py`
- `tools/experimental_wine_restore.py`
- this note: `CRIU_GPU_RESTORE_RESEARCH.md`

Large experimental dump artifacts exist locally under `artifacts/experimental-snapshots/` but are not intended for commit.
