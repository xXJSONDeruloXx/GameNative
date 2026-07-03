#define _GNU_SOURCE

#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#ifndef __ANDROID__
#include <linux/stat.h>
#include <sys/vfs.h>
#endif
#ifdef __ANDROID__
#include <android/log.h>
#include <sys/syscall.h>
#endif

#ifndef AT_FDCWD
#define AT_FDCWD -100
#endif

extern char **environ;

static __thread int g_in_hook;

#ifdef __ANDROID__
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "GNRedirect", __VA_ARGS__)
#else
#define LOGW(...) ((void)0)
#endif

static const char *envv(const char *key) {
    const char *value = getenv(key);
    return (value && value[0]) ? value : NULL;
}

static const char *proc_self_exe_override(void) {
    const char *value = envv("GN_PROC_SELF_EXE");
    if (value) return value;
    return envv("REDIRECT_EXEC__PROC_SELF_EXE");
}

static bool starts_with(const char *value, const char *prefix) {
    return value && prefix && strncmp(value, prefix, strlen(prefix)) == 0;
}

static bool ends_with(const char *value, const char *suffix) {
    if (!value || !suffix) return false;
    size_t value_len = strlen(value);
    size_t suffix_len = strlen(suffix);
    return value_len >= suffix_len && strcmp(value + value_len - suffix_len, suffix) == 0;
}

static const char *path_basename(const char *path) {
    const char *slash = path ? strrchr(path, '/') : NULL;
    return slash ? slash + 1 : path;
}

static char *dup_string(const char *value) {
    if (!value) return NULL;
    size_t len = strlen(value) + 1;
    char *out = (char *)malloc(len);
    if (!out) return NULL;
    memcpy(out, value, len);
    return out;
}

static char *replace_prefix(const char *path, const char *old_prefix, const char *new_prefix) {
    if (!path || !old_prefix || !new_prefix || !starts_with(path, old_prefix)) return NULL;
    size_t old_len = strlen(old_prefix);
    size_t new_len = strlen(new_prefix);
    size_t tail_len = strlen(path + old_len);
    char *out = (char *)malloc(new_len + tail_len + 1);
    if (!out) return NULL;
    memcpy(out, new_prefix, new_len);
    memcpy(out + new_len, path + old_len, tail_len + 1);
    return out;
}

static char *join_paths(const char *left, const char *right) {
    if (!left || !right) return NULL;
    size_t left_len = strlen(left);
    size_t right_len = strlen(right);
    bool left_has_slash = left_len > 0 && left[left_len - 1] == '/';
    bool right_has_slash = right[0] == '/';
    size_t total = left_len + right_len + ((left_has_slash || right_has_slash) ? 1 : 2);
    char *out = (char *)malloc(total);
    if (!out) return NULL;
    memcpy(out, left, left_len);
    size_t pos = left_len;
    if (!left_has_slash) out[pos++] = '/';
    if (right_has_slash) right++;
    memcpy(out + pos, right, strlen(right) + 1);
    return out;
}

static bool is_blocked_input_path(const char *path) {
    if (!path) return false;
    return starts_with(path, "/dev/input/event") ||
           starts_with(path, "/dev/input/js") ||
           starts_with(path, "/dev/hidraw");
}

static char *rewrite_shm_name(const char *name) {
    if (!name || !starts_with(name, "/wine")) return NULL;
    const char *root = envv("GN_IMAGEFS_ROOT");
    if (!root) return NULL;
    char *tmp_dir = join_paths(root, "usr/tmp");
    if (!tmp_dir) return NULL;
    char *rewritten = join_paths(tmp_dir, name);
    free(tmp_dir);
    return rewritten;
}

static char *rewrite_imagefs_path(const char *path) {
    const char *root = envv("GN_IMAGEFS_ROOT");
    if (!path) return NULL;
    if (!root) return dup_string(path);

    const char *fixed_prefixes[] = {
        "/data/data/com.winlator/files/imagefs",
        "/data/user/0/com.winlator/files/imagefs",
        "/data/data/com.winlator.cmod/files/imagefs",
        "/data/user/0/com.winlator.cmod/files/imagefs",
        "/data/data/com.winemu/files/usr",
        "/data/user/0/com.winemu/files/usr",
        "/data/data/app.gamenative/files/imagefs",
        "/data/user/0/app.gamenative/files/imagefs",
        NULL
    };

    for (int i = 0; fixed_prefixes[i]; ++i) {
        const char *target = fixed_prefixes[i];
        const char *replacement = root;
        if (strstr(target, "/files/usr")) {
            char *root_usr = join_paths(root, "usr");
            if (!root_usr) continue;
            char *rewritten = replace_prefix(path, target, root_usr);
            free(root_usr);
            if (rewritten) return rewritten;
            continue;
        }
        char *rewritten = replace_prefix(path, target, replacement);
        if (rewritten) return rewritten;
    }

    const char *pkg = envv("GN_PACKAGE_NAME");
    if (pkg) {
        char prefix[PATH_MAX];
        snprintf(prefix, sizeof(prefix), "/data/data/%s/files/imagefs", pkg);
        char *rewritten = replace_prefix(path, prefix, root);
        if (rewritten) return rewritten;

        snprintf(prefix, sizeof(prefix), "/data/user/0/%s/files/imagefs", pkg);
        rewritten = replace_prefix(path, prefix, root);
        if (rewritten) return rewritten;
    }

    if (starts_with(path, "/apex/com.android.runtime/bin/../share/wine") ||
        starts_with(path, "/apex/com.android.runtime/bin//../share/wine")) {
        char *base = join_paths(root, "usr/share/wine");
        if (!base) return dup_string(path);
        const char *needle = strstr(path, "share/wine");
        char *out = needle ? join_paths(base, needle + strlen("share/wine")) : dup_string(base);
        free(base);
        return out ? out : dup_string(path);
    }

    if (starts_with(path, "/apex/com.android.runtime/bin/../lib/wine") ||
        starts_with(path, "/apex/com.android.runtime/bin//../lib/wine")) {
        const char *wine_lib = envv("GN_WINE_LIB");
        if (!wine_lib) return dup_string(path);
        const char *needle = strstr(path, "lib/wine");
        return needle ? join_paths(wine_lib, needle + strlen("lib/wine")) : dup_string(wine_lib);
    }

    return dup_string(path);
}

static bool should_use_linker_for_exec(const char *filename) {
#ifdef __ANDROID__
    const char *root = envv("GN_IMAGEFS_ROOT");
    const char *use_linker = envv("GN_EXEC_USE_LINKER");
    if (!root || !use_linker || strcmp(use_linker, "1") != 0) return false;
    if (!filename || !starts_with(filename, root)) return false;
    if (strcmp(filename, "/system/bin/linker") == 0 || strcmp(filename, "/system/bin/linker64") == 0) return false;
    if (ends_with(filename, "/wine-preloader")) return false;
    return true;
#else
    (void)filename;
    return false;
#endif
}

static int readlink_raw(const char *path, char *buf, size_t bufsiz) {
    typedef ssize_t (*readlink_fn)(const char *, char *, size_t);
    static readlink_fn real_readlink_fn;
    if (!real_readlink_fn) real_readlink_fn = (readlink_fn)dlsym(RTLD_NEXT, "readlink");
    if (!real_readlink_fn) {
        errno = ENOSYS;
        return -1;
    }
    return (int)real_readlink_fn(path, buf, bufsiz);
}

static bool fd_is_blocked_input(int fd) {
    char proc_path[64];
    char target[PATH_MAX];
    snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", fd);
    int len = readlink_raw(proc_path, target, sizeof(target) - 1);
    if (len <= 0) return false;
    target[len] = '\0';
    return is_blocked_input_path(target);
}

static char **clone_env_with_ld_preload(char *const envp[], const char *extra_preload) {
    if (!extra_preload || !extra_preload[0]) return NULL;

    char *const *source = envp ? envp : environ;
    int count = 0;
    while (source && source[count]) count++;

    size_t extra_len = strlen(extra_preload);
    char *new_ld = NULL;
    int ld_index = -1;
    for (int i = 0; i < count; ++i) {
        if (strncmp(source[i], "LD_PRELOAD=", 11) == 0) {
            ld_index = i;
            size_t old_len = strlen(source[i] + 11);
            new_ld = (char *)malloc(11 + extra_len + (old_len ? old_len + 1 : 0) + 1);
            if (!new_ld) return NULL;
            memcpy(new_ld, "LD_PRELOAD=", 11);
            memcpy(new_ld + 11, extra_preload, extra_len);
            size_t pos = 11 + extra_len;
            if (old_len) {
                new_ld[pos++] = ':';
                memcpy(new_ld + pos, source[i] + 11, old_len + 1);
            } else {
                new_ld[pos] = '\0';
            }
            break;
        }
    }

    int out_count = count + (ld_index < 0 ? 2 : 1);
    char **out = (char **)calloc((size_t)out_count, sizeof(char *));
    if (!out) {
        free(new_ld);
        return NULL;
    }

    for (int i = 0; i < count; ++i) {
        if (i == ld_index) {
            out[i] = new_ld;
        } else {
            out[i] = source[i];
        }
    }

    if (ld_index < 0) {
        new_ld = (char *)malloc(11 + extra_len + 1);
        if (!new_ld) {
            free(out);
            return NULL;
        }
        memcpy(new_ld, "LD_PRELOAD=", 11);
        memcpy(new_ld + 11, extra_preload, extra_len + 1);
        out[count] = new_ld;
        out[count + 1] = NULL;
    } else {
        out[count] = NULL;
    }

    return out;
}

static void free_cloned_env(char **envp) {
    if (!envp) return;
    for (int i = 0; envp[i]; ++i) {
        if (strncmp(envp[i], "LD_PRELOAD=", 11) == 0 && (i == 0 || envp[i - 1] != envp[i])) {
            free(envp[i]);
        }
    }
    free(envp);
}

static char **maybe_add_goldberg_preload(const char *filename, char *const envp[]) {
#ifndef __ANDROID__
    const char *base = path_basename(filename);
    if (!base || strcmp(base, "wineserver") != 0) return NULL;
    const char *root = envv("GN_IMAGEFS_ROOT");
    if (!root) return NULL;
    char *candidate = join_paths(root, "libpluviagoldberg.so");
    if (!candidate) return NULL;
    bool exists = access(candidate, F_OK) == 0;
    char **cloned = exists ? clone_env_with_ld_preload(envp, candidate) : NULL;
    free(candidate);
    return cloned;
#else
    (void)filename;
    (void)envp;
    return NULL;
#endif
}

static char **make_linker_argv(const char *linker, const char *target, char *const argv[]) {
    int argc = 0;
    if (argv) while (argv[argc]) argc++;

    char **out = (char **)calloc((size_t)argc + 3, sizeof(char *));
    if (!out) return NULL;
    out[0] = (char *)linker;
    out[1] = (char *)target;
    for (int i = 1; i < argc; ++i) out[i + 1] = argv[i];
    out[argc + 1] = NULL;
    return out;
}

__attribute__((visibility("default"))) int execve(const char *filename, char *const argv[], char *const envp[]) {
    typedef int (*execve_fn)(const char *, char *const[], char *const[]);
    static execve_fn real_execve;
    if (!real_execve) real_execve = (execve_fn)dlsym(RTLD_NEXT, "execve");
    if (!real_execve) {
        errno = ENOSYS;
        return -1;
    }
    if (g_in_hook) return real_execve(filename, argv, envp);

    g_in_hook = 1;
    char *rewritten = rewrite_imagefs_path(filename);
    char **patched_env = maybe_add_goldberg_preload(rewritten ? rewritten : filename, envp);
    char *const *effective_env = patched_env ? patched_env : envp;
    int rc;

    if (rewritten && ends_with(rewritten, "/wine-preloader")) {
        free_cloned_env(patched_env);
        free(rewritten);
        g_in_hook = 0;
        errno = EACCES;
        return -1;
    }

    if (should_use_linker_for_exec(rewritten ? rewritten : filename)) {
#ifdef __ANDROID__
        const char *linker = sizeof(void *) == 8 ? "/system/bin/linker64" : "/system/bin/linker";
        char **linker_argv = make_linker_argv(linker, rewritten ? rewritten : filename, argv);
        if (!linker_argv) {
            free_cloned_env(patched_env);
            free(rewritten);
            g_in_hook = 0;
            errno = ENOMEM;
            return -1;
        }
        rc = real_execve(linker, linker_argv, (char *const *)effective_env);
        free(linker_argv);
#else
        rc = real_execve(rewritten ? rewritten : filename, argv, (char *const *)effective_env);
#endif
    } else {
        rc = real_execve(rewritten ? rewritten : filename, argv, (char *const *)effective_env);
    }

    int saved = errno;
    free_cloned_env(patched_env);
    free(rewritten);
    g_in_hook = 0;
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int execv(const char *path, char *const argv[]) {
    return execve(path, argv, environ);
}

#ifndef __ANDROID__
__attribute__((visibility("default"))) int execvp(const char *file, char *const argv[]) {
    typedef int (*execvp_fn)(const char *, char *const[]);
    static execvp_fn real_execvp;
    if (!real_execvp) real_execvp = (execvp_fn)dlsym(RTLD_NEXT, "execvp");
    if (!real_execvp) {
        errno = ENOSYS;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(file);
    int rc = real_execvp(rewritten ? rewritten : file, argv);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}
#endif

static int call_open_common(const char *path, int flags, mode_t mode, bool has_mode) {
    typedef int (*open_fn)(const char *, int, ...);
    static open_fn real_open;
    if (!real_open) real_open = (open_fn)dlsym(RTLD_NEXT, "open");
    if (!real_open) {
        errno = ENOSYS;
        return -1;
    }
    if (is_blocked_input_path(path)) {
        errno = ENOENT;
        return -1;
    }
    char *rewritten = g_in_hook ? dup_string(path) : rewrite_imagefs_path(path);
    int rc = has_mode ? real_open(rewritten ? rewritten : path, flags, mode)
                      : real_open(rewritten ? rewritten : path, flags);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int open(const char *path, int flags, ...) {
    mode_t mode = 0;
    bool has_mode = (flags & O_CREAT) != 0;
    if (has_mode) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return call_open_common(path, flags, mode, has_mode);
}

__attribute__((visibility("default"))) int open64(const char *path, int flags, ...) {
    mode_t mode = 0;
    bool has_mode = (flags & O_CREAT) != 0;
    if (has_mode) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return call_open_common(path, flags, mode, has_mode);
}

static int call_openat_common(int dirfd, const char *path, int flags, mode_t mode, bool has_mode) {
    typedef int (*openat_fn)(int, const char *, int, ...);
    static openat_fn real_openat;
    if (!real_openat) real_openat = (openat_fn)dlsym(RTLD_NEXT, "openat");
    if (!real_openat) {
        errno = ENOSYS;
        return -1;
    }
    if (is_blocked_input_path(path)) {
        errno = ENOENT;
        return -1;
    }
    char *rewritten = g_in_hook ? dup_string(path) : rewrite_imagefs_path(path);
    int rc = has_mode ? real_openat(dirfd, rewritten ? rewritten : path, flags, mode)
                      : real_openat(dirfd, rewritten ? rewritten : path, flags);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    bool has_mode = (flags & O_CREAT) != 0;
    if (has_mode) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return call_openat_common(dirfd, path, flags, mode, has_mode);
}

#ifndef __ANDROID__
__attribute__((visibility("default"))) int openat64(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    bool has_mode = (flags & O_CREAT) != 0;
    if (has_mode) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return call_openat_common(dirfd, path, flags, mode, has_mode);
}

__attribute__((visibility("default"))) int openat2(int dirfd, const char *path, const void *how, size_t size) {
    typedef int (*openat2_fn)(int, const char *, const void *, size_t);
    static openat2_fn real_openat2;
    if (!real_openat2) real_openat2 = (openat2_fn)dlsym(RTLD_NEXT, "openat2");
    if (!real_openat2) {
        errno = ENOSYS;
        return -1;
    }
    if (is_blocked_input_path(path)) {
        errno = ENOENT;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_openat2(dirfd, rewritten ? rewritten : path, how, size);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}
#endif

__attribute__((visibility("default"))) FILE *fopen(const char *path, const char *mode) {
    typedef FILE *(*fopen_fn)(const char *, const char *);
    static fopen_fn real_fopen;
    if (!real_fopen) real_fopen = (fopen_fn)dlsym(RTLD_NEXT, "fopen");
    if (!real_fopen) {
        errno = ENOSYS;
        return NULL;
    }
    if (is_blocked_input_path(path)) {
        errno = ENOENT;
        return NULL;
    }
    char *rewritten = rewrite_imagefs_path(path);
    FILE *fp = real_fopen(rewritten ? rewritten : path, mode);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return fp;
}

#ifndef __ANDROID__
__attribute__((visibility("default"))) FILE *fopen64(const char *path, const char *mode) {
    typedef FILE *(*fopen64_fn)(const char *, const char *);
    static fopen64_fn real_fopen64;
    if (!real_fopen64) real_fopen64 = (fopen64_fn)dlsym(RTLD_NEXT, "fopen64");
    if (!real_fopen64) {
        errno = ENOSYS;
        return NULL;
    }
    if (is_blocked_input_path(path)) {
        errno = ENOENT;
        return NULL;
    }
    char *rewritten = rewrite_imagefs_path(path);
    FILE *fp = real_fopen64(rewritten ? rewritten : path, mode);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return fp;
}

__attribute__((visibility("default"))) DIR *opendir(const char *name) {
    typedef DIR *(*opendir_fn)(const char *);
    static opendir_fn real_opendir;
    if (!real_opendir) real_opendir = (opendir_fn)dlsym(RTLD_NEXT, "opendir");
    if (!real_opendir) {
        errno = ENOSYS;
        return NULL;
    }
    if (is_blocked_input_path(name)) {
        errno = ENOENT;
        return NULL;
    }
    char *rewritten = rewrite_imagefs_path(name);
    DIR *dir = real_opendir(rewritten ? rewritten : name);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return dir;
}
#endif

__attribute__((visibility("default"))) int access(const char *path, int mode) {
    typedef int (*access_fn)(const char *, int);
    static access_fn real_access;
    if (!real_access) real_access = (access_fn)dlsym(RTLD_NEXT, "access");
    if (!real_access) {
        errno = ENOSYS;
        return -1;
    }
    if (is_blocked_input_path(path)) {
        errno = ENOENT;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_access(rewritten ? rewritten : path, mode);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int stat(const char *path, struct stat *st) {
    typedef int (*stat_fn)(const char *, struct stat *);
    static stat_fn real_stat;
    if (!real_stat) real_stat = (stat_fn)dlsym(RTLD_NEXT, "stat");
    if (!real_stat) {
        errno = ENOSYS;
        return -1;
    }
    if (is_blocked_input_path(path)) {
        errno = ENOENT;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_stat(rewritten ? rewritten : path, st);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int lstat(const char *path, struct stat *st) {
    typedef int (*lstat_fn)(const char *, struct stat *);
    static lstat_fn real_lstat;
    if (!real_lstat) real_lstat = (lstat_fn)dlsym(RTLD_NEXT, "lstat");
    if (!real_lstat) {
        errno = ENOSYS;
        return -1;
    }
    if (is_blocked_input_path(path)) {
        errno = ENOENT;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_lstat(rewritten ? rewritten : path, st);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int fstatat(int dirfd, const char *path, struct stat *st, int flags) {
    typedef int (*fstatat_fn)(int, const char *, struct stat *, int);
    static fstatat_fn real_fstatat;
    if (!real_fstatat) real_fstatat = (fstatat_fn)dlsym(RTLD_NEXT, "fstatat");
    if (!real_fstatat) {
        errno = ENOSYS;
        return -1;
    }
    if (is_blocked_input_path(path)) {
        errno = ENOENT;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_fstatat(dirfd, rewritten ? rewritten : path, st, flags);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

#ifndef __ANDROID__
__attribute__((visibility("default"))) int stat64(const char *path, struct stat64 *st) {
    typedef int (*stat64_fn)(const char *, struct stat64 *);
    static stat64_fn real_stat64;
    if (!real_stat64) real_stat64 = (stat64_fn)dlsym(RTLD_NEXT, "stat64");
    if (!real_stat64) {
        errno = ENOSYS;
        return -1;
    }
    if (is_blocked_input_path(path)) {
        errno = ENOENT;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_stat64(rewritten ? rewritten : path, st);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int lstat64(const char *path, struct stat64 *st) {
    typedef int (*lstat64_fn)(const char *, struct stat64 *);
    static lstat64_fn real_lstat64;
    if (!real_lstat64) real_lstat64 = (lstat64_fn)dlsym(RTLD_NEXT, "lstat64");
    if (!real_lstat64) {
        errno = ENOSYS;
        return -1;
    }
    if (is_blocked_input_path(path)) {
        errno = ENOENT;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_lstat64(rewritten ? rewritten : path, st);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int fstat64(int fd, struct stat64 *st) {
    typedef int (*fstat64_fn)(int, struct stat64 *);
    static fstat64_fn real_fstat64;
    if (!real_fstat64) real_fstat64 = (fstat64_fn)dlsym(RTLD_NEXT, "fstat64");
    if (!real_fstat64) {
        errno = ENOSYS;
        return -1;
    }
    return real_fstat64(fd, st);
}

__attribute__((visibility("default"))) int fstatat64(int dirfd, const char *path, struct stat64 *st, int flags) {
    typedef int (*fstatat64_fn)(int, const char *, struct stat64 *, int);
    static fstatat64_fn real_fstatat64;
    if (!real_fstatat64) real_fstatat64 = (fstatat64_fn)dlsym(RTLD_NEXT, "fstatat64");
    if (!real_fstatat64) {
        errno = ENOSYS;
        return -1;
    }
    if (is_blocked_input_path(path)) {
        errno = ENOENT;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_fstatat64(dirfd, rewritten ? rewritten : path, st, flags);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int __xstat(int ver, const char *path, struct stat *st) {
    typedef int (*xstat_fn)(int, const char *, struct stat *);
    static xstat_fn real_xstat;
    if (!real_xstat) real_xstat = (xstat_fn)dlsym(RTLD_NEXT, "__xstat");
    if (!real_xstat) {
        errno = ENOSYS;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_xstat(ver, rewritten ? rewritten : path, st);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int __xstat64(int ver, const char *path, struct stat64 *st) {
    typedef int (*xstat64_fn)(int, const char *, struct stat64 *);
    static xstat64_fn real_xstat64;
    if (!real_xstat64) real_xstat64 = (xstat64_fn)dlsym(RTLD_NEXT, "__xstat64");
    if (!real_xstat64) {
        errno = ENOSYS;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_xstat64(ver, rewritten ? rewritten : path, st);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int __lxstat(int ver, const char *path, struct stat *st) {
    typedef int (*lxstat_fn)(int, const char *, struct stat *);
    static lxstat_fn real_lxstat;
    if (!real_lxstat) real_lxstat = (lxstat_fn)dlsym(RTLD_NEXT, "__lxstat");
    if (!real_lxstat) {
        errno = ENOSYS;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_lxstat(ver, rewritten ? rewritten : path, st);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int __lxstat64(int ver, const char *path, struct stat64 *st) {
    typedef int (*lxstat64_fn)(int, const char *, struct stat64 *);
    static lxstat64_fn real_lxstat64;
    if (!real_lxstat64) real_lxstat64 = (lxstat64_fn)dlsym(RTLD_NEXT, "__lxstat64");
    if (!real_lxstat64) {
        errno = ENOSYS;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_lxstat64(ver, rewritten ? rewritten : path, st);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int __fxstatat(int ver, int dirfd, const char *path, struct stat *st, int flags) {
    typedef int (*fxstatat_fn)(int, int, const char *, struct stat *, int);
    static fxstatat_fn real_fxstatat;
    if (!real_fxstatat) real_fxstatat = (fxstatat_fn)dlsym(RTLD_NEXT, "__fxstatat");
    if (!real_fxstatat) {
        errno = ENOSYS;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_fxstatat(ver, dirfd, rewritten ? rewritten : path, st, flags);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int __fxstatat64(int ver, int dirfd, const char *path, struct stat64 *st, int flags) {
    typedef int (*fxstatat64_fn)(int, int, const char *, struct stat64 *, int);
    static fxstatat64_fn real_fxstatat64;
    if (!real_fxstatat64) real_fxstatat64 = (fxstatat64_fn)dlsym(RTLD_NEXT, "__fxstatat64");
    if (!real_fxstatat64) {
        errno = ENOSYS;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_fxstatat64(ver, dirfd, rewritten ? rewritten : path, st, flags);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *stx) {
    typedef int (*statx_fn)(int, const char *, int, unsigned int, struct statx *);
    static statx_fn real_statx;
    if (!real_statx) real_statx = (statx_fn)dlsym(RTLD_NEXT, "statx");
    if (!real_statx) {
        errno = ENOSYS;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_statx(dirfd, rewritten ? rewritten : path, flags, mask, stx);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}
#endif

__attribute__((visibility("default"))) ssize_t readlink(const char *path, char *buf, size_t bufsiz) {
    typedef ssize_t (*readlink_fn)(const char *, char *, size_t);
    static readlink_fn real_readlink_fn;
    if (!real_readlink_fn) real_readlink_fn = (readlink_fn)dlsym(RTLD_NEXT, "readlink");
    if (!real_readlink_fn) {
        errno = ENOSYS;
        return -1;
    }
    const char *override = proc_self_exe_override();
    if (override && path && strcmp(path, "/proc/self/exe") == 0) {
        size_t len = strlen(override);
        size_t copy = len < bufsiz ? len : bufsiz;
        if (copy > 0) memcpy(buf, override, copy);
        return (ssize_t)copy;
    }
    if (is_blocked_input_path(path)) {
        errno = ENOENT;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    ssize_t rc = real_readlink_fn(rewritten ? rewritten : path, buf, bufsiz);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) char *realpath(const char *path, char *resolved_path) {
    typedef char *(*realpath_fn)(const char *, char *);
    static realpath_fn real_realpath;
    if (!real_realpath) real_realpath = (realpath_fn)dlsym(RTLD_NEXT, "realpath");
    if (!real_realpath) {
        errno = ENOSYS;
        return NULL;
    }
    const char *override = proc_self_exe_override();
    if (override && path && strcmp(path, "/proc/self/exe") == 0) {
        if (resolved_path) {
            strncpy(resolved_path, override, PATH_MAX - 1);
            resolved_path[PATH_MAX - 1] = '\0';
            return resolved_path;
        }
        return dup_string(override);
    }
    if (is_blocked_input_path(path)) {
        errno = ENOENT;
        return NULL;
    }
    char *rewritten = rewrite_imagefs_path(path);
    char *rc = real_realpath(rewritten ? rewritten : path, resolved_path);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

#ifndef __ANDROID__
__attribute__((visibility("default"))) void *dlopen(const char *filename, int flags) {
    typedef void *(*dlopen_fn)(const char *, int);
    static dlopen_fn real_dlopen;
    if (!real_dlopen) real_dlopen = (dlopen_fn)dlsym(RTLD_NEXT, "dlopen");
    if (!real_dlopen) {
        errno = ENOSYS;
        return NULL;
    }
    char *rewritten = rewrite_imagefs_path(filename);
    void *handle = real_dlopen(rewritten ? rewritten : filename, flags);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return handle;
}

__attribute__((visibility("default"))) int chdir(const char *path) {
    typedef int (*chdir_fn)(const char *);
    static chdir_fn real_chdir;
    if (!real_chdir) real_chdir = (chdir_fn)dlsym(RTLD_NEXT, "chdir");
    if (!real_chdir) {
        errno = ENOSYS;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_chdir(rewritten ? rewritten : path);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int mkdir(const char *path, mode_t mode) {
    typedef int (*mkdir_fn)(const char *, mode_t);
    static mkdir_fn real_mkdir;
    if (!real_mkdir) real_mkdir = (mkdir_fn)dlsym(RTLD_NEXT, "mkdir");
    if (!real_mkdir) {
        errno = ENOSYS;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_mkdir(rewritten ? rewritten : path, mode);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int rename(const char *oldpath, const char *newpath) {
    typedef int (*rename_fn)(const char *, const char *);
    static rename_fn real_rename;
    if (!real_rename) real_rename = (rename_fn)dlsym(RTLD_NEXT, "rename");
    if (!real_rename) {
        errno = ENOSYS;
        return -1;
    }
    char *old_rewritten = rewrite_imagefs_path(oldpath);
    char *new_rewritten = rewrite_imagefs_path(newpath);
    int rc = real_rename(old_rewritten ? old_rewritten : oldpath,
                         new_rewritten ? new_rewritten : newpath);
    int saved = errno;
    free(old_rewritten);
    free(new_rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int symlink(const char *target, const char *linkpath) {
    typedef int (*symlink_fn)(const char *, const char *);
    static symlink_fn real_symlink;
    if (!real_symlink) real_symlink = (symlink_fn)dlsym(RTLD_NEXT, "symlink");
    if (!real_symlink) {
        errno = ENOSYS;
        return -1;
    }
    char *target_rewritten = rewrite_imagefs_path(target);
    char *link_rewritten = rewrite_imagefs_path(linkpath);
    int rc = real_symlink(target_rewritten ? target_rewritten : target,
                          link_rewritten ? link_rewritten : linkpath);
    int saved = errno;
    free(target_rewritten);
    free(link_rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int unlink(const char *path) {
    typedef int (*unlink_fn)(const char *);
    static unlink_fn real_unlink;
    if (!real_unlink) real_unlink = (unlink_fn)dlsym(RTLD_NEXT, "unlink");
    if (!real_unlink) {
        errno = ENOSYS;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_unlink(rewritten ? rewritten : path);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int rmdir(const char *path) {
    typedef int (*rmdir_fn)(const char *);
    static rmdir_fn real_rmdir;
    if (!real_rmdir) real_rmdir = (rmdir_fn)dlsym(RTLD_NEXT, "rmdir");
    if (!real_rmdir) {
        errno = ENOSYS;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_rmdir(rewritten ? rewritten : path);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int posix_spawn(pid_t *pid, const char *path,
                                                        const posix_spawn_file_actions_t *file_actions,
                                                        const posix_spawnattr_t *attrp,
                                                        char *const argv[], char *const envp[]) {
    typedef int (*posix_spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                                  const posix_spawnattr_t *, char *const[], char *const[]);
    static posix_spawn_fn real_posix_spawn;
    if (!real_posix_spawn) real_posix_spawn = (posix_spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
    if (!real_posix_spawn) return ENOSYS;

    char *rewritten = rewrite_imagefs_path(path);
    char **patched_env = maybe_add_goldberg_preload(rewritten ? rewritten : path, envp);
    int rc = real_posix_spawn(pid, rewritten ? rewritten : path, file_actions, attrp, argv,
                              patched_env ? patched_env : envp);
    free_cloned_env(patched_env);
    free(rewritten);
    return rc;
}

__attribute__((visibility("default"))) int posix_spawnp(pid_t *pid, const char *file,
                                                         const posix_spawn_file_actions_t *file_actions,
                                                         const posix_spawnattr_t *attrp,
                                                         char *const argv[], char *const envp[]) {
    typedef int (*posix_spawnp_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                                   const posix_spawnattr_t *, char *const[], char *const[]);
    static posix_spawnp_fn real_posix_spawnp;
    if (!real_posix_spawnp) real_posix_spawnp = (posix_spawnp_fn)dlsym(RTLD_NEXT, "posix_spawnp");
    if (!real_posix_spawnp) return ENOSYS;

    char *rewritten = rewrite_imagefs_path(file);
    char **patched_env = maybe_add_goldberg_preload(rewritten ? rewritten : file, envp);
    int rc = real_posix_spawnp(pid, rewritten ? rewritten : file, file_actions, attrp, argv,
                               patched_env ? patched_env : envp);
    free_cloned_env(patched_env);
    free(rewritten);
    return rc;
}

__attribute__((visibility("default"))) int shm_open(const char *name, int oflag, mode_t mode) {
    typedef int (*shm_open_fn)(const char *, int, mode_t);
    static shm_open_fn real_shm_open;
    if (!real_shm_open) real_shm_open = (shm_open_fn)dlsym(RTLD_NEXT, "shm_open");
    if (!real_shm_open) {
        errno = ENOSYS;
        return -1;
    }
    char *rewritten = rewrite_shm_name(name);
    int rc = real_shm_open(rewritten ? rewritten : name, oflag, mode);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) int shm_unlink(const char *name) {
    typedef int (*shm_unlink_fn)(const char *);
    static shm_unlink_fn real_shm_unlink;
    if (!real_shm_unlink) real_shm_unlink = (shm_unlink_fn)dlsym(RTLD_NEXT, "shm_unlink");
    if (!real_shm_unlink) {
        errno = ENOSYS;
        return -1;
    }
    char *rewritten = rewrite_shm_name(name);
    int rc = real_shm_unlink(rewritten ? rewritten : name);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) void *XOpenDisplay(const char *display_name) {
    typedef void *(*xopen_fn)(const char *);
    static xopen_fn real_xopen;
    if (!real_xopen) real_xopen = (xopen_fn)dlsym(RTLD_NEXT, "XOpenDisplay");
    if (!real_xopen) return NULL;
    const char *effective = display_name ? display_name : envv("DISPLAY");
    if (!effective) effective = ":0";
    return real_xopen(effective);
}

__attribute__((visibility("default"))) int fstat(int fd, struct stat *st) {
    typedef int (*fstat_fn)(int, struct stat *);
    static fstat_fn real_fstat;
    if (!real_fstat) real_fstat = (fstat_fn)dlsym(RTLD_NEXT, "fstat");
    if (!real_fstat) {
        errno = ENOSYS;
        return -1;
    }
    return real_fstat(fd, st);
}

__attribute__((visibility("default"))) int fstatfs(int fd, struct statfs *buf) {
    typedef int (*fstatfs_fn)(int, struct statfs *);
    static fstatfs_fn real_fstatfs;
    if (!real_fstatfs) real_fstatfs = (fstatfs_fn)dlsym(RTLD_NEXT, "fstatfs");
    if (!real_fstatfs) {
        errno = ENOSYS;
        return -1;
    }
    return real_fstatfs(fd, buf);
}

__attribute__((visibility("default"))) FILE *fdopen(int fd, const char *mode) {
    typedef FILE *(*fdopen_fn)(int, const char *);
    static fdopen_fn real_fdopen;
    if (!real_fdopen) real_fdopen = (fdopen_fn)dlsym(RTLD_NEXT, "fdopen");
    if (!real_fdopen) {
        errno = ENOSYS;
        return NULL;
    }
    return real_fdopen(fd, mode);
}

__attribute__((visibility("default"))) DIR *fdopendir(int fd) {
    typedef DIR *(*fdopendir_fn)(int);
    static fdopendir_fn real_fdopendir;
    if (!real_fdopendir) real_fdopendir = (fdopendir_fn)dlsym(RTLD_NEXT, "fdopendir");
    if (!real_fdopendir) {
        errno = ENOSYS;
        return NULL;
    }
    return real_fdopendir(fd);
}

__attribute__((visibility("default"))) FILE *fopencookie(void *cookie, const char *mode, cookie_io_functions_t io_funcs) {
    typedef FILE *(*fopencookie_fn)(void *, const char *, cookie_io_functions_t);
    static fopencookie_fn real_fopencookie;
    if (!real_fopencookie) real_fopencookie = (fopencookie_fn)dlsym(RTLD_NEXT, "fopencookie");
    if (!real_fopencookie) {
        errno = ENOSYS;
        return NULL;
    }
    return real_fopencookie(cookie, mode, io_funcs);
}

__attribute__((visibility("default"))) char *getcwd(char *buf, size_t size) {
    typedef char *(*getcwd_fn)(char *, size_t);
    static getcwd_fn real_getcwd;
    if (!real_getcwd) real_getcwd = (getcwd_fn)dlsym(RTLD_NEXT, "getcwd");
    if (!real_getcwd) {
        errno = ENOSYS;
        return NULL;
    }
    return real_getcwd(buf, size);
}

__attribute__((visibility("default"))) int __open64_2(const char *path, int flags) {
    typedef int (*open64_2_fn)(const char *, int);
    static open64_2_fn real_open64_2;
    if (!real_open64_2) real_open64_2 = (open64_2_fn)dlsym(RTLD_NEXT, "__open64_2");
    if (!real_open64_2) {
        errno = ENOSYS;
        return -1;
    }
    char *rewritten = rewrite_imagefs_path(path);
    int rc = real_open64_2(rewritten ? rewritten : path, flags);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) FILE *popen(const char *command, const char *type) {
    typedef FILE *(*popen_fn)(const char *, const char *);
    static popen_fn real_popen;
    if (!real_popen) real_popen = (popen_fn)dlsym(RTLD_NEXT, "popen");
    if (!real_popen) {
        errno = ENOSYS;
        return NULL;
    }
    return real_popen(command, type);
}

__attribute__((visibility("default"))) struct dirent *readdir(DIR *dirp) {
    typedef struct dirent *(*readdir_fn)(DIR *);
    static readdir_fn real_readdir;
    if (!real_readdir) real_readdir = (readdir_fn)dlsym(RTLD_NEXT, "readdir");
    if (!real_readdir) {
        errno = ENOSYS;
        return NULL;
    }
    return real_readdir(dirp);
}

__attribute__((visibility("default"))) ssize_t write(int fd, const void *buf, size_t count) {
    typedef ssize_t (*write_fn)(int, const void *, size_t);
    static write_fn real_write;
    if (!real_write) real_write = (write_fn)dlsym(RTLD_NEXT, "write");
    if (!real_write) {
        errno = ENOSYS;
        return -1;
    }
    return real_write(fd, buf, count);
}

__attribute__((visibility("default"))) int vasprintf(char **strp, const char *fmt, va_list ap) {
    typedef int (*vasprintf_fn)(char **, const char *, va_list);
    static vasprintf_fn real_vasprintf;
    if (!real_vasprintf) real_vasprintf = (vasprintf_fn)dlsym(RTLD_NEXT, "vasprintf");
    if (!real_vasprintf) {
        errno = ENOSYS;
        return -1;
    }
    return real_vasprintf(strp, fmt, ap);
}

__attribute__((visibility("default"))) int asprintf(char **strp, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int rc = vasprintf(strp, fmt, ap);
    va_end(ap);
    return rc;
}

__attribute__((visibility("default"))) char *replace_substring(const char *value) {
    return rewrite_imagefs_path(value);
}

__attribute__((visibility("default"))) void *my_dlopen(const char *filename, int flags) {
    return dlopen(filename, flags);
}

__attribute__((visibility("default"))) FILE *my_fopen(const char *path, const char *mode) {
    return fopen(path, mode);
}

__attribute__((visibility("default"))) int my_open(const char *path, int flags, mode_t mode) {
    return open(path, flags, mode);
}

__attribute__((visibility("default"))) int my_stat(const char *path, struct stat *st) {
    return stat(path, st);
}

__attribute__((visibility("default"))) int my_lstat(const char *path, struct stat *st) {
    return lstat(path, st);
}

__attribute__((visibility("default"))) int my_fstat(int fd, struct stat *st) {
    return fstat(fd, st);
}

__attribute__((visibility("default"))) int my_fstatat(int dirfd, const char *path, struct stat *st, int flags) {
    return fstatat(dirfd, path, st, flags);
}

__attribute__((visibility("default"))) void *my_XOpenDisplay(const char *display_name) {
    return XOpenDisplay(display_name);
}

__attribute__((visibility("default"))) int debug_log(const char *fmt, ...) {
    (void)fmt;
    return 0;
}

__attribute__((visibility("default"))) void pluviagoldberg_on_load(void) {
}
#endif

__attribute__((visibility("default"))) int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    typedef int (*connect_fn)(int, const struct sockaddr *, socklen_t);
    static connect_fn real_connect;
    if (!real_connect) real_connect = (connect_fn)dlsym(RTLD_NEXT, "connect");
    if (!real_connect) {
        errno = ENOSYS;
        return -1;
    }
#ifndef __ANDROID__
    if (addr && addr->sa_family == AF_UNIX && addrlen >= sizeof(sa_family_t)) {
        const struct sockaddr_un *un = (const struct sockaddr_un *)addr;
        char *rewritten = rewrite_imagefs_path(un->sun_path);
        if (rewritten) {
            struct sockaddr_un patched;
            memset(&patched, 0, sizeof(patched));
            patched.sun_family = AF_UNIX;
            strncpy(patched.sun_path, rewritten, sizeof(patched.sun_path) - 1);
            socklen_t patched_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + strlen(patched.sun_path) + 1);
            int rc = real_connect(sockfd, (const struct sockaddr *)&patched, patched_len);
            int saved = errno;
            free(rewritten);
            errno = saved;
            return rc;
        }
    }
#endif
    return real_connect(sockfd, addr, addrlen);
}

__attribute__((visibility("default"))) ssize_t read(int fd, void *buf, size_t count) {
    typedef ssize_t (*read_fn)(int, void *, size_t);
    static read_fn real_read;
    if (!real_read) real_read = (read_fn)dlsym(RTLD_NEXT, "read");
    if (!real_read) {
        errno = ENOSYS;
        return -1;
    }
    if (fd_is_blocked_input(fd)) {
        errno = ENODEV;
        return -1;
    }
    return real_read(fd, buf, count);
}

__attribute__((visibility("default"))) int ioctl(int fd, int request, ...) {
    typedef int (*ioctl_fn)(int, int, ...);
    static ioctl_fn real_ioctl;
    if (!real_ioctl) real_ioctl = (ioctl_fn)dlsym(RTLD_NEXT, "ioctl");
    if (!real_ioctl) {
        errno = ENOSYS;
        return -1;
    }
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    if (fd_is_blocked_input(fd)) {
        errno = ENOTTY;
        return -1;
    }
    return real_ioctl(fd, request, arg);
}

#ifdef __ANDROID__
__attribute__((visibility("default"))) long syscall(long number, ...) {
    typedef long (*syscall_fn)(long, ...);
    static syscall_fn real_syscall;
    if (!real_syscall) real_syscall = (syscall_fn)dlsym(RTLD_NEXT, "syscall");
    if (!real_syscall) {
        errno = ENOSYS;
        return -1;
    }

    va_list ap;
    va_start(ap, number);
    unsigned long a1 = va_arg(ap, unsigned long);
    unsigned long a2 = va_arg(ap, unsigned long);
    unsigned long a3 = va_arg(ap, unsigned long);
    unsigned long a4 = va_arg(ap, unsigned long);
    unsigned long a5 = va_arg(ap, unsigned long);
    unsigned long a6 = va_arg(ap, unsigned long);
    va_end(ap);

    char *rewritten = NULL;
    bool path_syscall = false;
    bool execve_syscall = false;
#if defined(__NR_openat)
    if (number == __NR_openat) {
        rewritten = rewrite_imagefs_path((const char *)a2);
        path_syscall = true;
    }
#endif
#if defined(__NR_fstatat)
    if (!rewritten && number == __NR_fstatat) {
        rewritten = rewrite_imagefs_path((const char *)a2);
        path_syscall = true;
    }
#endif
#if defined(__NR_newfstatat)
    if (!rewritten && number == __NR_newfstatat) {
        rewritten = rewrite_imagefs_path((const char *)a2);
        path_syscall = true;
    }
#endif
#if defined(__NR_readlinkat)
    if (!rewritten && number == __NR_readlinkat) {
        rewritten = rewrite_imagefs_path((const char *)a2);
        path_syscall = true;
    }
#endif
#if defined(__NR_openat2)
    if (!rewritten && number == __NR_openat2) {
        rewritten = rewrite_imagefs_path((const char *)a2);
        path_syscall = true;
    }
#endif
#if defined(__NR_execve)
    if (!rewritten && number == __NR_execve) {
        rewritten = rewrite_imagefs_path((const char *)a1);
        execve_syscall = true;
    }
#endif

    if (path_syscall && is_blocked_input_path((const char *)a2)) {
        free(rewritten);
        errno = ENOENT;
        return -1;
    }

    if (execve_syscall && rewritten && should_use_linker_for_exec(rewritten)) {
        free(rewritten);
        rewritten = NULL;
    }

    if (rewritten) {
        if (execve_syscall) {
            a1 = (unsigned long)rewritten;
        } else {
            a2 = (unsigned long)rewritten;
        }
    }

    long rc = real_syscall(number, a1, a2, a3, a4, a5, a6);
    int saved = errno;
    free(rewritten);
    errno = saved;
    return rc;
}

__attribute__((visibility("default"))) void *mmap(void *addr, size_t length, int prot, int flags, int fd, off_t offset) {
    typedef void *(*mmap_fn)(void *, size_t, int, int, int, off_t);
    static mmap_fn real_mmap;
    if (!real_mmap) real_mmap = (mmap_fn)dlsym(RTLD_NEXT, "mmap");
    if (!real_mmap) {
        errno = ENOSYS;
        return MAP_FAILED;
    }
    return real_mmap(addr, length, prot, flags, fd, offset);
}

__attribute__((visibility("default"))) void *mmap64(void *addr, size_t length, int prot, int flags, int fd, off64_t offset) {
    typedef void *(*mmap64_fn)(void *, size_t, int, int, int, off64_t);
    static mmap64_fn real_mmap64;
    if (!real_mmap64) real_mmap64 = (mmap64_fn)dlsym(RTLD_NEXT, "mmap64");
    if (!real_mmap64) {
        errno = ENOSYS;
        return MAP_FAILED;
    }
    return real_mmap64(addr, length, prot, flags, fd, offset);
}

__attribute__((visibility("default"))) int mprotect(void *addr, size_t len, int prot) {
    typedef int (*mprotect_fn)(void *, size_t, int);
    static mprotect_fn real_mprotect;
    if (!real_mprotect) real_mprotect = (mprotect_fn)dlsym(RTLD_NEXT, "mprotect");
    if (!real_mprotect) {
        errno = ENOSYS;
        return -1;
    }
    return real_mprotect(addr, len, prot);
}

__attribute__((visibility("default"))) int sigaction(int signum, const struct sigaction *act, struct sigaction *oldact) {
    typedef int (*sigaction_fn)(int, const struct sigaction *, struct sigaction *);
    static sigaction_fn real_sigaction;
    if (!real_sigaction) real_sigaction = (sigaction_fn)dlsym(RTLD_NEXT, "sigaction");
    if (!real_sigaction) {
        errno = ENOSYS;
        return -1;
    }
    return real_sigaction(signum, act, oldact);
}
#endif
