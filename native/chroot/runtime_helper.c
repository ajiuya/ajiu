#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "runtime_launch.h"
#include "runtime_guest.h"
#include "rootfs_manifest.h"

#include <dirent.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <limits.h>
#include <linux/capability.h>
#include <linux/magic.h>
#include <linux/mount.h>
#include <linux/openat2.h>
#include <linux/resource.h>
#include <linux/securebits.h>
#include <linux/stat.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/utsname.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define DF (O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK)
#define OF (O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK)
#define MAX_TASKS 4096U
#define FRAME_WAIT 1000U
#define CLEANUP_WAIT 5000
#define IPC_MAGIC UINT32_C(0x414a4931)
#define TRY(label, expression) do { stage = (label); if ((expression) < 0) return -1; } while (0)
#define NEED(label, expression) do { stage = (label); if (!(expression)) { errno = ESTALE; return -1; } } while (0)

enum { LOCAL, BASE, ROOT, MANIFEST, VIEWS, VIEW, CGROOT, GROUP, PROCS, KILLCTL, EVENTS, THREADS, TYPE, ANCHORS };
static const char *const anchor_names[ANCHORS] = { NULL, NULL, "root", "manifest", "views", NULL,
    NULL, NULL, "cgroup.procs", "cgroup.kill", "cgroup.events", "cgroup.threads", "cgroup.type" };
enum { MSG_ENROLL = 256, MSG_GATED, MSG_PREPARED, MSG_EXITED, MSG_QUIET, MSG_UNMOUNTED, MSG_FAILURE, MSG_FINISH, MSG_ENROLL_ABORTED };
struct anchor { int fd; bool valid; struct stat st; uint64_t mount_id; };
struct shared {
    pid_t bootstrap;
    uint64_t start;
    dev_t guest_ns_dev;
    ino_t guest_ns_ino;
    unsigned int enrolled, dropped, executing, unsafe, namespace_closed, guest_ns_ready;
};
struct task { pid_t pid; int fd; uint64_t start; };
struct registry { struct task *tasks; size_t count, capacity; };
struct identity { uint64_t start; pid_t parent, tgid; uint32_t uid[4], gid[4], groups[32], groupc; uint64_t caps[5]; int nnp; };
struct runtime {
    const struct ajiu_launch *launch;
    struct anchor a[ANCHORS];
    struct stat namespace;
    struct shared *shared;
    struct registry registry;
    char base_name[48], group_name[40], package[24];
    int cap_last, source[AJIU_LAUNCH_MAX_MOUNTS], native_dir, worker_fd, app_fd;
    bool view_made, group_made;
};
struct packet { uint32_t magic, type, length; unsigned char body[AJIU_MAX_DATA_BYTES]; };
struct enrollment { pid_t pid; uint64_t start; };
struct child_result { uint32_t magic, kind, status, error; };
struct attached { char target[96]; struct anchor anchor; };
struct controller_io { int input, output, error, master, slave; int in[2], out[2], err[2]; };
static const char *stage = "entry";
static volatile sig_atomic_t interrupted;

static unsigned int load(const unsigned int *p) { return __atomic_load_n(p, __ATOMIC_ACQUIRE); }
static void store(unsigned int *p, unsigned int value) { __atomic_store_n(p, value, __ATOMIC_RELEASE); }
static void stop_signal(int sig) { (void)sig; interrupted = 1; }

static int64_t now_ms(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) < 0) return -1;
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int nonblock(int fd)
{
    int flags = fcntl(fd, F_GETFL);
    return flags < 0 ? -1 : fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static int signals(bool guest)
{
    struct sigaction sa = { .sa_handler = SIG_DFL };
    if (sigemptyset(&sa.sa_mask) < 0 || sigprocmask(SIG_SETMASK, &sa.sa_mask, NULL) < 0) return -1;
    for (int sig = 1; sig < NSIG; ++sig) {
        if (sig == SIGKILL || sig == SIGSTOP || (sig >= 32 && sig < SIGRTMIN)) continue;
        sa.sa_handler = !guest && sig == SIGPIPE ? SIG_IGN :
            !guest && (sig == SIGHUP || sig == SIGINT || sig == SIGTERM) ? stop_signal : SIG_DFL;
        if (sigaction(sig, &sa, NULL) < 0) return -1;
    }
    return 0;
}

static int close_except(const int *keep, size_t count)
{
    unsigned int first = 3;
    for (;;) {
        unsigned int next = UINT_MAX;
        for (size_t i = 0; i < count; ++i)
            if (keep[i] >= 3 && (unsigned int)keep[i] >= first && (unsigned int)keep[i] < next)
                next = (unsigned int)keep[i];
        if (next > first && syscall(SYS_close_range, first, next - 1, 0) < 0) return -1;
        if (next == UINT_MAX) return (int)syscall(SYS_close_range, UINT_MAX, UINT_MAX, 0);
        first = next + 1;
    }
}

static int text(int fd, char *buffer, size_t capacity)
{
    size_t used = 0;
    int64_t deadline = now_ms() + FRAME_WAIT;
    for (;;) {
        if (used == capacity - 1 || now_ms() >= deadline) { errno = EOVERFLOW; return -1; }
        ssize_t n = pread(fd, buffer + used, capacity - 1 - used, (off_t)used);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) return -1;
        if (!n) break;
        if (memchr(buffer + used, 0, (size_t)n)) { errno = EPROTO; return -1; }
        used += (size_t)n;
    }
    buffer[used] = 0;
    return 0;
}

static int exact_write(int fd, const void *data, size_t size)
{
    ssize_t n;
    do n = write(fd, data, size); while (n < 0 && errno == EINTR);
    if (n == (ssize_t)size) return 0;
    if (n >= 0) errno = EIO;
    return -1;
}

static bool same(const struct stat *a, const struct stat *b)
{
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino && a->st_mode == b->st_mode &&
           a->st_uid == b->st_uid && a->st_gid == b->st_gid;
}

static int snapshot(struct anchor *a)
{
    struct statx sx = {0};
    TRY("anchor_stat", fstat(a->fd, &a->st));
    TRY("anchor_mount", syscall(SYS_statx, a->fd, "", AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW, STATX_MNT_ID, &sx));
    NEED("anchor_mount_id", sx.stx_mask & STATX_MNT_ID);
    a->mount_id = sx.stx_mnt_id; a->valid = true;
    return 0;
}

static int open_at(int dir, const char *path, int flags, unsigned int resolve)
{
    struct open_how how = { .flags = (uint64_t)(flags | O_NOFOLLOW | O_CLOEXEC | ((flags & O_PATH) ? 0 : O_NONBLOCK)),
        .resolve = resolve | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS };
    return (int)syscall(SYS_openat2, dir, path, &how, sizeof(how));
}

static int anchored(struct anchor *a, int dir, const char *name, int flags)
{
    TRY("anchor_open", a->fd = open_at(dir, name, flags, RESOLVE_BENEATH));
    return snapshot(a);
}

static int current(const struct anchor *a, int dir, const char *name)
{
    struct anchor held = { .fd = a->fd }, found = { .fd = -1 };
    NEED("anchor_known", a->valid);
    if (snapshot(&held) < 0 || anchored(&found, dir, name, O_PATH) < 0) {
        int saved = errno; if (found.fd >= 0) close(found.fd); errno = saved; return -1;
    }
    bool ok = same(&a->st, &held.st) && same(&a->st, &found.st) &&
              a->mount_id == held.mount_id && a->mount_id == found.mount_id;
    TRY("anchor_close", close(found.fd)); NEED("anchor_unchanged", ok);
    return 0;
}

static bool protected_directory(const struct stat *st)
{
    return S_ISDIR(st->st_mode) && (st->st_uid == 0 || st->st_uid == 1000) &&
        !(st->st_mode & 0002) && (!(st->st_mode & 0020) || st->st_gid == 0 || st->st_gid == 1000);
}

static int protected_walk(bool cgroup)
{
    const char *const parts[] = { cgroup ? "sys" : "data", cgroup ? "fs" : "local", "cgroup" };
    int fd = open("/", DF);
    if (fd < 0) return -1;
    for (int i = 0; i <= (cgroup ? 3 : 2); ++i) {
        struct stat st;
        if (fstat(fd, &st) < 0 || !protected_directory(&st)) { close(fd); errno = EPERM; return -1; }
        if (i == (cgroup ? 3 : 2)) return fd;
        int next = open_at(fd, parts[i], DF, RESOLVE_BENEATH), saved = errno;
        close(fd); if (next < 0) { errno = saved; return -1; } fd = next;
    }
    errno = EPROTO; return -1;
}

static int protected_static_elf(int fd)
{
    struct stat st;
    if (fstat(fd, &st) < 0) return -1;
    NEED("protected_native_file", S_ISREG(st.st_mode) && (st.st_uid == 0 || st.st_uid == 1000) &&
        (st.st_gid == 0 || st.st_gid == 1000) && !(st.st_mode & 0022) && (st.st_mode & 0111) && st.st_nlink == 1);
    Elf64_Ehdr elf;
    bool ok = pread(fd, &elf, sizeof(elf), 0) == (ssize_t)sizeof(elf) &&
        !memcmp(elf.e_ident, ELFMAG, SELFMAG) && elf.e_ident[EI_CLASS] == ELFCLASS64 &&
        elf.e_ident[EI_DATA] == ELFDATA2LSB && elf.e_machine == EM_AARCH64 &&
        (elf.e_type == ET_EXEC || elf.e_type == ET_DYN) && elf.e_phnum > 0 && elf.e_phnum <= 128 &&
        elf.e_phentsize == sizeof(Elf64_Phdr) && elf.e_phoff <= (uint64_t)st.st_size &&
        (uint64_t)elf.e_phnum * sizeof(Elf64_Phdr) <= (uint64_t)st.st_size - elf.e_phoff;
    for (unsigned int i = 0; ok && i < elf.e_phnum; ++i) {
        Elf64_Phdr ph;
        ok = pread(fd, &ph, sizeof(ph), (off_t)(elf.e_phoff + i * sizeof(ph))) == (ssize_t)sizeof(ph) && ph.p_type != PT_INTERP;
    }
    NEED("static_native_elf", ok);
    return 0;
}

static int trusted_executable(struct runtime *r)
{
    char path[PATH_MAX + 1];
    ssize_t n = readlink("/proc/self/exe", path, sizeof(path) - 1);
    NEED("apk_executable_path", n > 0 && n < (ssize_t)sizeof(path) - 1);
    path[n] = 0;
    NEED("apk_install_prefix", !strncmp(path, "/data/app/", 10));
    const char *suffix = "/lib/arm64/libajiu_chroot_runtime.so";
    size_t length = strlen(path), tail = strlen(suffix);
    NEED("apk_native_library", length > tail && !strcmp(path + length - tail, suffix));
    char *name = path + length - tail;
    *name = 0;
    char *install = strrchr(path, '/');
    NEED("apk_package_directory", install != NULL);
    ++install;
    static const char *const packages[] = { "com.ajiu.debug", "com.ajiu.beta", "com.ajiu" };
    bool matched = false;
    for (size_t i = 0; i < sizeof(packages) / sizeof(*packages); ++i) {
        size_t size = strlen(packages[i]);
        if (!strncmp(install, packages[i], size) && install[size] == '-' && install[size + 1]) {
            (void)snprintf(r->package, sizeof(r->package), "%s", packages[i]); matched = true; break;
        }
    }
    NEED("apk_package_name", matched);
    *name = '/';
    int fd = open("/", DF);
    if (fd < 0) return -1;
    for (char *p = path + 1; *p;) {
        char *end = strchr(p, '/');
        size_t size = end ? (size_t)(end - p) : strlen(p);
        if (!size || size > 255 || (size == 1 && *p == '.') || (size == 2 && !memcmp(p, "..", 2))) {
            close(fd); errno = EPERM; return -1;
        }
        struct stat st;
        if (fstat(fd, &st) < 0 || !protected_directory(&st)) { close(fd); errno = EPERM; return -1; }
        char component[256]; memcpy(component, p, size); component[size] = 0;
        if (!end) {
            r->native_dir = fcntl(fd, F_DUPFD_CLOEXEC, 3);
            if (r->native_dir < 0) { close(fd); return -1; }
        }
        int next = open_at(fd, component, end ? DF : O_RDONLY, RESOLVE_BENEATH), saved = errno;
        close(fd); if (next < 0) { errno = saved; return -1; } fd = next;
        if (!end) break;
        p = end + 1;
    }
    struct stat installed, running;
    int exe = open("/proc/self/exe", O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (exe < 0) { close(fd); return -1; }
    bool ok = fstat(fd, &installed) == 0 && fstat(exe, &running) == 0 && same(&installed, &running) &&
        protected_static_elf(fd) == 0;
    close(exe); close(fd);
    NEED("apk_static_identity", ok);
    return 0;
}

static int reopen_worker(struct runtime *r)
{
    if (r->worker_fd < 0) return 0;
    struct runtime fresh = { .native_dir = -1, .worker_fd = -1 };
    struct stat old_directory, new_directory, old_worker, new_worker;
    int rc = -1;
    stage = "file_worker_resource";
    if (fstat(r->native_dir, &old_directory) < 0 || fstat(r->worker_fd, &old_worker) < 0 ||
        trusted_executable(&fresh) < 0) goto done;
    stage = "apk_static_identity";
    if (fstat(fresh.native_dir, &new_directory) < 0) goto done;
    if (strcmp(r->package, fresh.package) || !same(&old_directory, &new_directory)) { errno = ESTALE; goto done; }
    stage = "file_worker_resource";
    fresh.worker_fd = open_at(fresh.native_dir, "libajiu_file_worker.so", O_RDONLY, RESOLVE_BENEATH);
    if (fresh.worker_fd < 0 || protected_static_elf(fresh.worker_fd) < 0 || fstat(fresh.worker_fd, &new_worker) < 0) goto done;
    stage = "apk_static_identity";
    if (!same(&old_worker, &new_worker)) { errno = ESTALE; goto done; }
    close(r->worker_fd); close(r->native_dir);
    r->worker_fd = fresh.worker_fd; r->native_dir = fresh.native_dir;
    fresh.worker_fd = fresh.native_dir = -1;
    rc = 0;
done: {
        int saved = errno;
        if (fresh.worker_fd >= 0) close(fresh.worker_fd);
        if (fresh.native_dir >= 0) close(fresh.native_dir);
        errno = saved;
    }
    return rc;
}

static int read_proc(pid_t pid, const char *name, char *buffer, size_t size)
{
    char path[64];
    (void)snprintf(path, sizeof(path), "/proc/%ld/%s", (long)pid, name);
    int fd = open_at(AT_FDCWD, path, O_RDONLY, 0);
    if (fd < 0) return -1;
    int rc = text(fd, buffer, size), saved = errno;
    if (close(fd) < 0 && rc == 0) return -1;
    errno = saved; return rc;
}

static int proc_start(pid_t pid, uint64_t *start, pid_t *parent)
{
    char buffer[4096];
    if (read_proc(pid, "stat", buffer, sizeof(buffer)) < 0) return -1;
    char *p = strrchr(buffer, ')');
    if (!p || p[1] != ' ' || !p[2] || p[3] != ' ') { errno = EPROTO; return -1; }
    p += 4;
    for (int field = 4; field <= 22; ++field) {
        char *end; errno = 0;
        unsigned long long value = strtoull(p, &end, 10);
        if (errno || p == end || (*end != ' ' && *end != '\n' && *end)) { errno = EPROTO; return -1; }
        if (field == 4) { if (value > INT_MAX) { errno = EPROTO; return -1; } *parent = (pid_t)value; }
        if (field == 22) { *start = (uint64_t)value; return 0; }
        p = end; while (*p == ' ') ++p;
    }
    errno = EPROTO; return -1;
}

static int process_group(pid_t pid, const char *name, bool inside)
{
    char buffer[4096], expected[64];
    if (read_proc(pid, "cgroup", buffer, sizeof(buffer)) < 0) return -1;
    (void)snprintf(expected, sizeof(expected), "0::/%s", name);
    bool found = false;
    for (char *p = buffer; *p;) {
        char *end = strchr(p, '\n');
        if (!end) { errno = EPROTO; return -1; }
        *end = 0;
        if (!strncmp(p, "0::/", 4)) {
            bool exact = !strcmp(p, expected);
            bool descendant = !strncmp(p, expected, strlen(expected)) && p[strlen(expected)] == '/';
            if (found || (inside ? !exact : exact || descendant)) { errno = ESTALE; return -1; }
            found = true;
        }
        p = end + 1;
    }
    if (!found) { errno = EPROTO; return -1; }
    return 0;
}

static int identity(pid_t pid, struct identity *id)
{
    memset(id, 0, sizeof(*id));
    if (proc_start(pid, &id->start, &id->parent) < 0) return -1;
    char buffer[16384], *save;
    if (read_proc(pid, "status", buffer, sizeof(buffer)) < 0) return -1;
    unsigned int seen = 0;
    for (char *line = strtok_r(buffer, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        if (!strncmp(line, "Uid:\t", 5) || !strncmp(line, "Gid:\t", 5)) {
            unsigned int *ids = line[0] == 'U' ? id->uid : id->gid;
            int used = 0;
            if (sscanf(line + 5, "%u %u %u %u%n", &ids[0], &ids[1], &ids[2], &ids[3], &used) != 4 || line[5 + used]) return -1;
            seen |= line[0] == 'U' ? 1U : 2U;
        } else if (!strncmp(line, "Tgid:\t", 6)) {
            char *end; errno = 0; unsigned long value = strtoul(line + 6, &end, 10);
            if (errno || end == line + 6 || *end || value <= 1 || value > INT_MAX || (seen & 512U)) return -1;
            id->tgid = (pid_t)value; seen |= 512U;
        } else if (!strncmp(line, "Groups:\t", 8)) {
            char *p = line + 8;
            while (*p == ' ' || *p == '\t') ++p;
            while (*p) {
                char *end; errno = 0; unsigned long n = strtoul(p, &end, 10);
                if (errno || p == end || n > INT_MAX || id->groupc == 32) return -1;
                id->groups[id->groupc++] = (uint32_t)n;
                p = end; while (*p == ' ' || *p == '\t') ++p;
            }
            seen |= 4;
        } else {
            static const char *const keys[] = { "CapEff:\t", "CapPrm:\t", "CapInh:\t", "CapBnd:\t", "CapAmb:\t", "NoNewPrivs:\t" };
            for (size_t i = 0; i < 6; ++i) {
                size_t n = strlen(keys[i]);
                if (strncmp(line, keys[i], n)) continue;
                char *end; errno = 0; unsigned long long value = strtoull(line + n, &end, i == 5 ? 10 : 16);
                if (errno || end == line + n || *end || (i == 5 && value > 1)) return -1;
                if (i == 5) id->nnp = (int)value; else id->caps[i] = value;
                seen |= 8U << i;
            }
        }
    }
    uint64_t again; pid_t parent;
    if (seen != 1023) { errno = ESTALE; return -1; }
    if (proc_start(pid, &again, &parent) < 0) return -1;
    if (again != id->start) { errno = ESTALE; return -1; }
    return 0;
}

static bool app_identity(const struct runtime *r, const struct identity *id)
{
    for (size_t i = 0; i < 4; ++i)
        if (id->uid[i] != r->launch->uid || id->gid[i] != r->launch->gid) return false;
    if (id->groupc != r->launch->group_count || id->nnp != 1) return false;
    for (size_t i = 0; i < 5; ++i) if (id->caps[i]) return false;
    for (uint32_t i = 0; i < id->groupc; ++i) {
        bool found = false;
        for (uint32_t j = 0; j < r->launch->group_count; ++j)
            if (id->groups[i] == r->launch->groups[j]) found = true;
        if (!found) return false;
    }
    return true;
}

static bool guest_identity(const struct identity *id)
{
    bool fully_nonroot = true;
    for (size_t i = 0; i < 4; ++i) {
        if (id->uid[i] > AJIU_GUEST_ID_MAX || id->gid[i] > AJIU_GUEST_ID_MAX) return false;
        if (!id->uid[i]) fully_nonroot = false;
    }
    if (id->nnp != 1 || id->groupc > 32) return false;
    for (uint32_t i = 0; i < id->groupc; ++i)
        if (id->groups[i] > AJIU_GUEST_ID_MAX) return false;
    return ajiu_guest_caps_valid(id->caps, fully_nonroot);
}

static bool guest_inet_group(const struct runtime *r)
{
    for (uint32_t i = 0; i < r->launch->group_count; ++i)
        if (r->launch->groups[i] == 3003) return true;
    return false;
}

static bool guest_initial_identity(const struct runtime *r, const struct identity *id)
{
    for (size_t i = 0; i < 4; ++i) if (id->uid[i] || id->gid[i]) return false;
    bool inet = guest_inet_group(r);
    return id->groupc == (inet ? 1U : 0U) && (!inet || id->groups[0] == 3003) &&
        id->nnp == 1 && ajiu_guest_initial_caps(id->caps);
}

static int process_namespace(pid_t pid, struct stat *ns)
{
    char path[64];
    int n = snprintf(path, sizeof(path), "/proc/%ld/ns/mnt", (long)pid);
    if (n <= 0 || (size_t)n >= sizeof(path)) { errno = EOVERFLOW; return -1; }
    return stat(path, ns);
}

static bool guest_namespace_matches(const struct shared *shared, const struct stat *ns)
{
    return load(&shared->guest_ns_ready) && ns->st_dev == shared->guest_ns_dev &&
        ns->st_ino == shared->guest_ns_ino;
}

static int pidfd_dead(int fd);

static bool member_credentials(const struct runtime *r, const struct identity *id, bool bootstrap)
{
    if (bootstrap && !load(&r->shared->dropped)) {
        for (size_t i = 0; i < 4; ++i) if (id->uid[i] || id->gid[i]) return false;
        return true;
    }
    return guest_identity(id);
}

/* Only disappearance errors can be a normal exit. Confirm the TID itself is now absent:
 * a missing ns link on a still-readable/reused task, EACCES or malformed identity stays unsafe. */
static int thread_read_failure(pid_t tid, int error)
{
    if (error != ENOENT && error != ESRCH) { errno = error ? error : ESTALE; return -1; }
    uint64_t start; pid_t parent;
    if (proc_start(tid, &start, &parent) < 0 && (errno == ENOENT || errno == ESRCH)) return 2;
    errno = ESTALE; return -1;
}

/* 0=stable launch member, 1=another TGID, 2=confirmed naturally gone, -1=unsafe/error. */
static int thread_candidate_snapshot(const struct runtime *r, pid_t pid, pid_t tid,
                                     bool bootstrap, struct identity *id)
{
    struct identity candidate, again;
    if (identity(tid, &candidate) < 0) return thread_read_failure(tid, errno);
    if (candidate.tgid != pid) return 1;
    if (!member_credentials(r, &candidate, bootstrap)) { errno = ESTALE; return -1; }
    if (process_group(tid, r->group_name, true) < 0) return thread_read_failure(tid, errno);
    struct stat before, after;
    if (process_namespace(tid, &before) < 0) return thread_read_failure(tid, errno);
    if (!guest_namespace_matches(r->shared, &before)) { errno = ESTALE; return -1; }
    if (identity(tid, &again) < 0) return thread_read_failure(tid, errno);
    if (again.tgid != pid || again.start != candidate.start || !member_credentials(r, &again, bootstrap)) {
        errno = ESTALE; return -1;
    }
    if (process_namespace(tid, &after) < 0) return thread_read_failure(tid, errno);
    if (before.st_dev != after.st_dev || before.st_ino != after.st_ino) { errno = ESTALE; return -1; }
    if (process_group(tid, r->group_name, true) < 0) return thread_read_failure(tid, errno);
    *id = again;
    return 0;
}

static int live_thread_snapshot(const struct runtime *r, pid_t pid, uint64_t process_start,
                                pid_t process_parent, bool bootstrap, struct identity *id)
{
    /* The group anchor and THREADS inode are pinned/root-owned by group_guard before registry scans.
     * Linux <=6.8 has no PIDFD_THREAD: keep a TGID pidfd and double-check each candidate's Tgid/start.
     * Never depend on /proc/TGID/task after leader pthread_exit, or scan the host /proc directory. */
    if (!r->a[THREADS].valid || r->a[THREADS].fd < 0) { errno = ESTALE; return -1; }
    char *buffer = malloc(MAX_TASKS * 12U + 1);
    if (!buffer) return -1;
    int error = 0, result = -1;
    int64_t begin = now_ms(), deadline = begin + FRAME_WAIT;
    if (begin < 0) error = EIO;
    pid_t tids[MAX_TASKS];
    for (unsigned int pass = 0; !error && pass < 3; ++pass) {
        int64_t now = now_ms();
        if (now < 0 || now >= deadline) { error = ETIMEDOUT; break; }
        if (text(r->a[THREADS].fd, buffer, MAX_TASKS * 12U + 1) < 0) { error = errno; break; }
        size_t count = 0;
        for (char *p = buffer; *p;) {
            char *end; errno = 0; long value = strtol(p, &end, 10);
            if (errno || p == end || *p < '1' || *p > '9' || value <= 1 || value > INT_MAX || *end != '\n' || count == MAX_TASKS) {
                error = EPROTO; break;
            }
            for (size_t i = 0; i < count; ++i) if (tids[i] == (pid_t)value) { error = EPROTO; break; }
            if (error) break;
            tids[count++] = (pid_t)value; p = end + 1;
        }
        pid_t chosen = -1;
        uint64_t chosen_start = 0;
        for (size_t i = 0; !error && i < count; ++i) {
            now = now_ms();
            if (now < 0 || now >= deadline) { error = ETIMEDOUT; break; }
            if (tids[i] == pid) continue;
            struct identity candidate;
            int rc = thread_candidate_snapshot(r, pid, tids[i], bootstrap, &candidate);
            if (rc < 0) { error = errno; break; }
            if (!rc && chosen < 0) { chosen = tids[i]; chosen_start = candidate.start; }
        }
        if (error) break;
        if (chosen < 0) continue; /* Bounded re-snapshot if all candidates naturally vanished. */
        struct identity stable;
        int rc = thread_candidate_snapshot(r, pid, chosen, bootstrap, &stable);
        if (rc == 2) continue;
        if (rc != 0 || stable.start != chosen_start) { error = rc < 0 ? errno : ESTALE; break; }
        /* Registry identity remains the TGID/start, never a candidate thread's birth time. */
        *id = stable; id->start = process_start; id->parent = process_parent; id->tgid = pid;
        result = 0; break;
    }
    free(buffer);
    if (result < 0) errno = error ? error : EAGAIN; /* Churn/no stable witness remains UNKNOWN. */
    return result;
}

/* A UID transition or a dead thread-group leader is not loss of process ownership. */
static int member_snapshot(const struct runtime *r, pid_t pid, uint64_t expected_start,
                           bool bootstrap, struct identity *id)
{
    int fd = (int)syscall(SYS_pidfd_open, pid, 0);
    if (fd < 0) return -1;
    uint64_t start = 0, again; pid_t parent = 0, parent_again;
    int error = 0;
    if (pidfd_dead(fd) != 0 || proc_start(pid, &start, &parent) < 0 ||
        (expected_start && start != expected_start) || process_group(pid, r->group_name, true) < 0) error = ESTALE;
    struct stat before, after;
    bool fallback = false;
    if (!error) {
        if (process_namespace(pid, &before) < 0) {
            if (errno == ENOENT || errno == ESRCH) fallback = true; else error = ESTALE;
        } else if (!guest_namespace_matches(r->shared, &before) || identity(pid, id) < 0 ||
            id->tgid != pid || id->start != start || !member_credentials(r, id, bootstrap) ||
            process_group(pid, r->group_name, true) < 0) error = ESTALE;
        else if (process_namespace(pid, &after) < 0) {
            /* pthread_exit may occur after the first successful namespace sample. The TGID
             * pidfd/start/group still have to survive the same bounded worker-TID validation. */
            if (errno == ENOENT || errno == ESRCH) fallback = true; else error = ESTALE;
        } else if (before.st_dev != after.st_dev || before.st_ino != after.st_ino) error = ESTALE;
    }
    if (!error && fallback && live_thread_snapshot(r, pid, start, parent, bootstrap, id) < 0) error = errno;
    if (!error && (proc_start(pid, &again, &parent_again) < 0 || again != start || pidfd_dead(fd) != 0 ||
        process_group(pid, r->group_name, true) < 0)) error = ESTALE;
    close(fd);
    return error ? (errno = error, -1) : 0;
}

static int pidfd_dead(int fd)
{
    struct pollfd p = { .fd = fd, .events = POLLIN };
    int rc = poll(&p, 1, 0);
    if (rc < 0 || (p.revents & (POLLNVAL | POLLERR))) return -1;
    return rc && (p.revents & POLLIN) ? 1 : 0;
}

static int protected_role(const struct runtime *r, pid_t pid, int fd, uint64_t start)
{
    int dead = pidfd_dead(fd);
    if (dead) return dead < 0 ? -1 : 1;
    struct identity id;
    bool ok = identity(pid, &id) == 0 && id.start == start && process_group(pid, r->group_name, false) == 0;
    for (size_t i = 0; ok && i < 4; ++i) ok = id.uid[i] == 0 && id.gid[i] == 0;
    dead = pidfd_dead(fd);
    if (dead) return dead < 0 ? -1 : 1;
    if (!ok) { errno = ESTALE; return -1; }
    return 0;
}

static int preflight(struct runtime *r)
{
    struct utsname uts;
    NEED("root_identity", getuid() == 0 && geteuid() == 0 && getgid() == 0 && getegid() == 0);
    TRY("architecture", uname(&uts)); NEED("aarch64", !strcmp(uts.machine, "aarch64"));
    if (trusted_executable(r) < 0) return -1;
    TRY("host_environment", clearenv());
    TRY("subreaper", prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0));
    TRY("root_dumpable", prctl(PR_SET_DUMPABLE, 0, 0, 0, 0));
    struct __user_cap_header_struct h = { .version = _LINUX_CAPABILITY_VERSION_3 };
    struct __user_cap_data_struct caps[2] = {{0}};
    TRY("capabilities", syscall(SYS_capget, &h, caps));
    uint64_t effective = ((uint64_t)caps[1].effective << 32) | caps[0].effective;
    uint64_t required = (UINT64_C(1) << CAP_SYS_ADMIN) | (UINT64_C(1) << CAP_SYS_CHROOT) |
        (UINT64_C(1) << CAP_SETUID) | (UINT64_C(1) << CAP_SETGID) | (UINT64_C(1) << CAP_SETPCAP) |
        (UINT64_C(1) << CAP_MKNOD) | (UINT64_C(1) << CAP_SYS_PTRACE) | AJIU_GUEST_CAPS;
    NEED("required_capabilities", (effective & required) == required);
    r->cap_last = -1;
    for (int c = 0; c <= 64; ++c) {
        int present = prctl(PR_CAPBSET_READ, c, 0, 0, 0);
        if (present < 0 && errno == EINVAL && c > CAP_SETPCAP) { r->cap_last = c - 1; break; }
        NEED("capability_range", present >= 0 && c < 64);
    }
    NEED("capability_last", r->cap_last >= CAP_SETFCAP);
    for (int c = 0; c <= r->cap_last; ++c)
        if (AJIU_GUEST_CAPS & (UINT64_C(1) << c))
            NEED("required_capabilities", prctl(PR_CAPBSET_READ, c, 0, 0, 0) == 1);
    TRY("watchdog_namespace", stat("/proc/self/ns/mnt", &r->namespace));
    return 0;
}

static int guest_manifest_version(uint64_t magic)
{
    if (magic == AJIU_MANIFEST_V1) { stage = "legacy_readonly_instance"; errno = ENOTSUP; return -1; }
    stage = "installed_layout";
    if (magic != AJIU_MANIFEST_V2) { errno = ESTALE; return -1; }
    return 0;
}

static int instance(struct runtime *r, bool view)
{
    TRY("rootfs_parent", r->a[LOCAL].fd = protected_walk(false));
    if (snapshot(&r->a[LOCAL]) < 0 || anchored(&r->a[BASE], r->a[LOCAL].fd, r->base_name, DF) < 0) return -1;
    const struct stat *b = &r->a[BASE].st;
    NEED("rootfs_instance", b->st_uid == 0 && b->st_gid == 0 && (b->st_mode & 07777) == 0700 &&
        (uint64_t)b->st_dev == r->launch->rootfs_dev && (uint64_t)b->st_ino == r->launch->rootfs_ino);
    if (anchored(&r->a[ROOT], r->a[BASE].fd, "root", DF) < 0 ||
        anchored(&r->a[MANIFEST], r->a[BASE].fd, "manifest", O_RDONLY) < 0 ||
        anchored(&r->a[VIEWS], r->a[BASE].fd, "views", DF) < 0) return -1;
    const struct stat *root = &r->a[ROOT].st, *manifest = &r->a[MANIFEST].st, *views = &r->a[VIEWS].st;
    NEED("installed_layout", root->st_uid == 0 && root->st_gid == 0 && (root->st_mode & 07777) == 0755 &&
        S_ISREG(manifest->st_mode) && manifest->st_uid == 0 && manifest->st_gid == 0 && manifest->st_nlink == 1 &&
        (manifest->st_mode & 07777) == 0400 && manifest->st_size >= 0 && manifest->st_size <= 19398656 &&
        views->st_uid == 0 && views->st_gid == 0 && (views->st_mode & 07777) == 0700);
    for (int i = ROOT; i <= VIEWS; ++i)
        NEED("installed_mount_identity", r->a[i].st.st_dev == b->st_dev && r->a[i].mount_id == r->a[BASE].mount_id);
    uint64_t header[AJIU_MANIFEST_HEADER_WORDS];
    stage = "installed_layout";
    if (pread(r->a[MANIFEST].fd, header, sizeof(header), 0) != (ssize_t)sizeof(header)) { errno = ESTALE; return -1; }
    if (guest_manifest_version(header[0]) < 0) return -1;
    NEED("installed_layout", header[1] == (uint64_t)root->st_dev && header[2] == (uint64_t)root->st_ino &&
        header[3] == (uint64_t)views->st_dev && header[4] == (uint64_t)views->st_ino && header[5]);
    if (view) {
        if (anchored(&r->a[VIEW], r->a[VIEWS].fd, r->launch->launch_id, DF) < 0) return -1;
        const struct stat *v = &r->a[VIEW].st;
        NEED("view_layout", v->st_uid == 0 && v->st_gid == 0 && (v->st_mode & 07777) == 0700 &&
            v->st_dev == b->st_dev && r->a[VIEW].mount_id == r->a[BASE].mount_id);
    }
    return 0;
}

static int no_subgroups(const struct runtime *r)
{
    int fd = open_at(r->a[GROUP].fd, ".", DF, RESOLVE_BENEATH);
    if (fd < 0) return -1;
    DIR *dir = fdopendir(fd);
    if (!dir) { close(fd); return -1; }
    int error = 0; size_t count = 0;
    for (;;) {
        errno = 0; struct dirent *entry = readdir(dir);
        if (!entry) { error = errno; break; }
        if (++count > 128) { error = EOVERFLOW; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        struct stat st;
        if (fstatat(fd, entry->d_name, &st, AT_SYMLINK_NOFOLLOW) < 0) { error = errno; break; }
        if (!S_ISREG(st.st_mode)) { error = ESTALE; break; }
    }
    if (closedir(dir) < 0 && !error) error = errno;
    if (error) { errno = error; return -1; }
    return 0;
}

static int group_guard(struct runtime *r)
{
    NEED("safe_group", r->group_made && !load(&r->shared->unsafe));
    struct stat ns;
    TRY("role_namespace", stat("/proc/self/ns/mnt", &ns));
    NEED("role_namespace_stable", ns.st_dev == r->namespace.st_dev && ns.st_ino == r->namespace.st_ino);
    int fd = protected_walk(true);
    if (fd < 0) return -1;
    struct anchor root = { .fd = fd };
    int rc = snapshot(&root), saved = errno; close(fd);
    if (rc < 0) { errno = saved; return -1; }
    NEED("cgroup_root_stable", same(&root.st, &r->a[CGROOT].st) && root.mount_id == r->a[CGROOT].mount_id);
    struct statfs fs;
    TRY("cgroup_filesystem", fstatfs(r->a[CGROOT].fd, &fs));
    NEED("cgroup2", fs.f_type == CGROUP2_SUPER_MAGIC);
    for (int i = GROUP; i <= TYPE; ++i) {
        const char *name = i == GROUP ? r->group_name : anchor_names[i];
        if (current(&r->a[i], r->a[i == GROUP ? CGROOT : GROUP].fd, name) < 0) return -1;
        const struct stat *st = &r->a[i].st;
        NEED("cgroup_root_control", st->st_uid == 0 && st->st_gid == 0 && !(st->st_mode & 0022) &&
            (i == GROUP ? S_ISDIR(st->st_mode) && (st->st_mode & 07777) == 0700 : S_ISREG(st->st_mode)) &&
            st->st_dev == r->a[CGROOT].st.st_dev && r->a[i].mount_id == r->a[CGROOT].mount_id);
    }
    char type[64];
    TRY("cgroup_type", text(r->a[TYPE].fd, type, sizeof(type)));
    NEED("cgroup_domain", !strcmp(type, "domain\n"));
    if (no_subgroups(r) < 0 || process_group(getpid(), r->group_name, false) < 0) return -1;
    return 0;
}

static int populated(struct runtime *r)
{
    char buffer[256];
    if (text(r->a[EVENTS].fd, buffer, sizeof(buffer)) < 0) return -1;
    if (!strcmp(buffer, "populated 0\nfrozen 0\n")) return 0;
    if (!strcmp(buffer, "populated 1\nfrozen 0\n")) return 1;
    errno = EPROTO; return -1;
}

static int prepare(struct runtime *r)
{
    if (instance(r, false) < 0) return -1;
    TRY("cgroup_parent", r->a[CGROOT].fd = protected_walk(true));
    if (snapshot(&r->a[CGROOT]) < 0) return -1;
    struct statfs fs;
    TRY("cgroup_preflight", fstatfs(r->a[CGROOT].fd, &fs)); NEED("cgroup2_required", fs.f_type == CGROUP2_SUPER_MAGIC);
    TRY("exclusive_view", mkdirat(r->a[VIEWS].fd, r->launch->launch_id, 0700)); r->view_made = true;
    if (anchored(&r->a[VIEW], r->a[VIEWS].fd, r->launch->launch_id, DF) < 0) return -1;
    NEED("exclusive_view_owner", r->a[VIEW].st.st_uid == 0 && r->a[VIEW].st.st_gid == 0 &&
        (r->a[VIEW].st.st_mode & 07777) == 0700 && r->a[VIEW].mount_id == r->a[BASE].mount_id);
    TRY("exclusive_cgroup", mkdirat(r->a[CGROOT].fd, r->group_name, 0700)); r->group_made = true;
    if (anchored(&r->a[GROUP], r->a[CGROOT].fd, r->group_name, DF) < 0) return -1;
    TRY("group_owner", fchown(r->a[GROUP].fd, 0, 0)); TRY("group_mode", fchmod(r->a[GROUP].fd, 0700));
    if (snapshot(&r->a[GROUP]) < 0) return -1;
    for (int i = PROCS; i <= TYPE; ++i)
        if (anchored(&r->a[i], r->a[GROUP].fd, anchor_names[i], i == PROCS ? O_RDWR : i == KILLCTL ? O_WRONLY : O_RDONLY) < 0) return -1;
    if (group_guard(r) < 0) return -1;
    char members[32];
    TRY("initial_members", text(r->a[PROCS].fd, members, sizeof(members)));
    NEED("initial_empty", !members[0] && populated(r) == 0);
    return 0;
}

static int drop_identity(const struct runtime *r, pid_t parent)
{
    /* NPROC counts the same UID and keeps ordinary guest forks below the PID registry budget. */
    struct rlimit64 process_limit = { .rlim_cur = 1024, .rlim_max = 1024 }, actual_limit = {0};
    TRY("guest_nproc_limit", syscall(SYS_prlimit64, 0, RLIMIT_NPROC, &process_limit, NULL));
    TRY("drop_dumpable", prctl(PR_SET_DUMPABLE, 0, 0, 0, 0));
    TRY("drop_keepcaps", prctl(PR_SET_KEEPCAPS, 0, 0, 0, 0));
    TRY("drop_ambient", prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL, 0, 0, 0));
    TRY("drop_securebits", prctl(PR_SET_SECUREBITS, SECBIT_NOROOT | SECBIT_NOROOT_LOCKED, 0, 0, 0));
    for (int cap = 0; cap <= r->cap_last; ++cap) TRY("drop_bounding", prctl(PR_CAPBSET_DROP, cap, 0, 0, 0));
    gid_t groups[32];
    for (uint32_t i = 0; i < r->launch->group_count; ++i) groups[i] = (gid_t)r->launch->groups[i];
    TRY("drop_groups", setgroups(r->launch->group_count, groups));
    TRY("drop_gid", setresgid(r->launch->gid, r->launch->gid, r->launch->gid));
    TRY("drop_uid", setresuid(r->launch->uid, r->launch->uid, r->launch->uid));
    struct __user_cap_header_struct h = { .version = _LINUX_CAPABILITY_VERSION_3 };
    struct __user_cap_data_struct caps[2] = {{0}};
    TRY("clear_capabilities", syscall(SYS_capset, &h, caps));
    TRY("no_new_privileges", prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    TRY("redrop_dumpable", prctl(PR_SET_DUMPABLE, 0, 0, 0, 0));
    TRY("parent_death", prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0));
    NEED("parent_alive", getppid() == parent);
    uid_t uid[3]; gid_t gid[3], actual[32];
    TRY("verify_uids", getresuid(&uid[0], &uid[1], &uid[2]));
    TRY("verify_gids", getresgid(&gid[0], &gid[1], &gid[2]));
    for (size_t i = 0; i < 3; ++i) NEED("verify_ids", uid[i] == r->launch->uid && gid[i] == r->launch->gid);
    int count = getgroups(32, actual);
    NEED("verify_group_count", count >= 0 && (uint32_t)count == r->launch->group_count);
    for (int i = 0; i < count; ++i) {
        bool found = false;
        for (uint32_t j = 0; j < r->launch->group_count; ++j) if (actual[i] == groups[j]) found = true;
        NEED("verify_groups", found);
    }
    TRY("verify_caps", syscall(SYS_capget, &h, caps));
    for (size_t i = 0; i < 2; ++i)
        NEED("verify_zero_caps", !caps[i].effective && !caps[i].permitted && !caps[i].inheritable);
    for (int cap = 0; cap <= r->cap_last; ++cap)
        NEED("verify_bounding_ambient", prctl(PR_CAPBSET_READ, cap, 0, 0, 0) == 0 &&
            prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_IS_SET, cap, 0, 0) == 0);
    NEED("verify_nnp", prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) == 1);
    TRY("verify_guest_nproc", syscall(SYS_prlimit64, 0, RLIMIT_NPROC, NULL, &actual_limit));
    NEED("guest_nproc_hard_limit", actual_limit.rlim_cur == 1024 && actual_limit.rlim_max == 1024);
    return 0;
}

static int guest_credentials(const struct runtime *r, pid_t parent)
{
    /* Only called after chroot. The source opener must continue using drop_identity above. */
    struct rlimit64 process_limit = { .rlim_cur = 1024, .rlim_max = 1024 }, actual_limit = {0};
    NEED("capability_last", r->cap_last >= CAP_SETFCAP && r->cap_last < 64);
    TRY("guest_nproc_limit", syscall(SYS_prlimit64, 0, RLIMIT_NPROC, &process_limit, NULL));
    /* RLIMIT_NPROC is not a root fork sandbox; the managed cgroup/registry still bounds tracking. */
    TRY("drop_dumpable", prctl(PR_SET_DUMPABLE, 0, 0, 0, 0));
    TRY("drop_keepcaps", prctl(PR_SET_KEEPCAPS, 0, 0, 0, 0));
    TRY("drop_ambient", prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL, 0, 0, 0));
    TRY("drop_securebits", prctl(PR_SET_SECUREBITS, AJIU_GUEST_SECUREBITS, 0, 0, 0));
    /* Android kernels may require AID_INET for ordinary TCP. Never add an unfrozen App group. */
    gid_t network_group = 3003;
    bool inet = guest_inet_group(r);
    TRY("drop_groups", setgroups(inet ? 1U : 0U, inet ? &network_group : NULL));
    TRY("drop_gid", setresgid(0, 0, 0));
    TRY("drop_uid", setresuid(0, 0, 0));
    for (int cap = 0; cap <= r->cap_last; ++cap)
        if (!(AJIU_GUEST_CAPS & (UINT64_C(1) << cap)))
            TRY("drop_bounding", prctl(PR_CAPBSET_DROP, cap, 0, 0, 0));
    struct __user_cap_header_struct h = { .version = _LINUX_CAPABILITY_VERSION_3 };
    struct __user_cap_data_struct caps[2] = {{0}};
    caps[0].effective = caps[0].permitted = (uint32_t)AJIU_GUEST_CAPS;
    caps[1].effective = caps[1].permitted = (uint32_t)(AJIU_GUEST_CAPS >> 32);
    TRY("clear_capabilities", syscall(SYS_capset, &h, caps));
    TRY("no_new_privileges", prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    TRY("redrop_dumpable", prctl(PR_SET_DUMPABLE, 0, 0, 0, 0));
    TRY("parent_death", prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0));
    NEED("parent_alive", getppid() == parent);
    uid_t uid[3]; gid_t gid[3];
    TRY("verify_uids", getresuid(&uid[0], &uid[1], &uid[2]));
    TRY("verify_gids", getresgid(&gid[0], &gid[1], &gid[2]));
    for (size_t i = 0; i < 3; ++i) NEED("verify_ids", !uid[i] && !gid[i]);
    gid_t actual_group[1];
    int group_count = getgroups(1, actual_group);
    NEED("verify_group_count", group_count == (inet ? 1 : 0) && (!inet || actual_group[0] == network_group));
    TRY("verify_caps", syscall(SYS_capget, &h, caps));
    uint64_t observed[5] = {
        ((uint64_t)caps[1].effective << 32) | caps[0].effective,
        ((uint64_t)caps[1].permitted << 32) | caps[0].permitted,
        ((uint64_t)caps[1].inheritable << 32) | caps[0].inheritable, 0, 0
    };
    for (int cap = 0; cap <= r->cap_last; ++cap) {
        int bounding = prctl(PR_CAPBSET_READ, cap, 0, 0, 0);
        int ambient = prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_IS_SET, cap, 0, 0);
        NEED("verify_bounding_ambient", bounding >= 0 && ambient == 0);
        if (bounding) observed[3] |= UINT64_C(1) << cap;
    }
    NEED("verify_caps", ajiu_guest_initial_caps(observed));
    NEED("drop_securebits", prctl(PR_GET_SECUREBITS, 0, 0, 0, 0) == AJIU_GUEST_SECUREBITS);
    NEED("verify_nnp", prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) == 1);
    TRY("verify_guest_nproc", syscall(SYS_prlimit64, 0, RLIMIT_NPROC, NULL, &actual_limit));
    NEED("guest_nproc_hard_limit", actual_limit.rlim_cur == 1024 && actual_limit.rlim_max == 1024);
    return 0;
}

static int send_packet(int socket, uint32_t type, const void *body, uint32_t length, unsigned int timeout)
{
    if (length > AJIU_MAX_DATA_BYTES || (length && !body)) { errno = EINVAL; return -1; }
    struct packet packet = { .magic = IPC_MAGIC, .type = type, .length = length };
    if (length) memcpy(packet.body, body, length);
    int64_t start = now_ms();
    if (start < 0) return -1;
    for (;;) {
        ssize_t n = send(socket, &packet, 12 + length, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (n == (ssize_t)(12 + length)) return 0;
        if (n >= 0) { errno = EIO; return -1; }
        if (errno != EAGAIN && errno != EINTR) return -1;
        int64_t now = now_ms();
        if (now < 0) return -1;
        if (now >= start + timeout) { errno = ETIMEDOUT; return -1; }
        struct pollfd p = { .fd = socket, .events = POLLOUT };
        if (poll(&p, 1, (int)(start + timeout - now)) < 0 && errno != EINTR) return -1;
    }
}

static int receive_packet(int socket, struct packet *packet)
{
    ssize_t n = recv(socket, packet, sizeof(*packet), MSG_DONTWAIT | MSG_TRUNC);
    if (n < 0 && (errno == EAGAIN || errno == EINTR)) return 0;
    if (n < 0) return -1;
    if (!n) { errno = EPIPE; return -1; }
    if (n < 12 || n > (ssize_t)sizeof(*packet) || packet->magic != IPC_MAGIC ||
        packet->length > AJIU_MAX_DATA_BYTES || n != (ssize_t)(12 + packet->length)) { errno = EPROTO; return -1; }
    return 1;
}

static int wait_pidfd(int fd, unsigned int timeout)
{
    struct pollfd p = { .fd = fd, .events = POLLIN };
    int rc;
    do rc = poll(&p, 1, (int)timeout); while (rc < 0 && errno == EINTR);
    if (rc == 1 && p.revents == POLLIN) return 0;
    errno = rc == 0 ? ETIMEDOUT : ECHILD;
    return -1;
}

static int open_mount_source(const struct ajiu_launch_mount *m)
{
    int fd = open_at(AT_FDCWD, m->source_path, m->kind == AJIU_MOUNT_DIRECTORY ? DF : O_RDONLY, 0);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) < 0) { int saved = errno; close(fd); errno = saved; return -1; }
    if ((uint64_t)st.st_dev != m->source_dev || (uint64_t)st.st_ino != m->source_ino ||
        (m->kind == AJIU_MOUNT_DIRECTORY ? !S_ISDIR(st.st_mode) : !S_ISREG(st.st_mode))) {
        close(fd); errno = ESTALE; return -1;
    }
    return fd;
}

static void open_sources_child(struct runtime *r, int socket, pid_t parent)
{
    int keep[] = { socket };
    (void)alarm(2);
    for (int fd = 0; fd < 3; ++fd) close(fd);
    if (close_except(keep, 1) < 0 || signals(true) < 0 || drop_identity(r, parent) < 0) _exit(3);
    struct identity opener;
    if (identity(getpid(), &opener) < 0 || !app_identity(r, &opener)) _exit(3);
    int fds[32];
    for (uint32_t i = 0; i < r->launch->mountc; ++i) {
        fds[i] = open_mount_source(&r->launch->mounts[i]);
        if (fds[i] < 0) _exit(3);
    }
    uint32_t count = r->launch->mountc;
    union { struct cmsghdr align; unsigned char bytes[CMSG_SPACE(sizeof(fds))]; } control = {0};
    struct iovec iov = { .iov_base = &count, .iov_len = sizeof(count) };
    struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = control.bytes,
        .msg_controllen = CMSG_SPACE(count * sizeof(int)) };
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
    c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(count * sizeof(int));
    memcpy(CMSG_DATA(c), fds, count * sizeof(int));
    if (sendmsg(socket, &msg, MSG_NOSIGNAL) != (ssize_t)sizeof(count)) _exit(3);
    _exit(0);
}

static int source_paths_match(const struct runtime *r)
{
    char prefix[96];
    int n = snprintf(prefix, sizeof(prefix), "/data/user/%u/%s/files/", r->launch->uid / 100000, r->package);
    NEED("source_package_prefix", n > 0 && (size_t)n < sizeof(prefix));
    for (uint32_t i = 0; i < r->launch->mountc; ++i) {
        const struct ajiu_launch_mount *m = &r->launch->mounts[i];
        if (!strncmp(m->source_path, "/data/user/", 11))
            NEED("source_package_identity", !strncmp(m->source_path, prefix, (size_t)n));
    }
    return 0;
}

static int open_sources(struct runtime *r)
{
    if (source_paths_match(r) < 0 || !r->launch->mountc) return r->launch->mountc ? -1 : 0;
    int sockets[2];
    TRY("source_channel", socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, sockets));
    int pass = 1;
    if (setsockopt(sockets[0], SOL_SOCKET, SO_PASSCRED, &pass, sizeof(pass)) < 0) { close(sockets[0]); close(sockets[1]); return -1; }
    pid_t parent = getpid(), pid = fork();
    if (!pid) { close(sockets[0]); open_sources_child(r, sockets[1], parent); }
    close(sockets[1]);
    if (pid < 0) { close(sockets[0]); return -1; }
    int pidfd = (int)syscall(SYS_pidfd_open, pid, 0), error = 0, status = 0;
    uint64_t start = 0; pid_t ppid;
    if (pidfd < 0 || proc_start(pid, &start, &ppid) < 0 || ppid != parent) error = ESTALE;
    struct pollfd pf[2] = { { sockets[0], POLLIN, 0 }, { pidfd, POLLIN, 0 } };
    if (!error && poll(pf, 2, 2000) <= 0) error = ETIMEDOUT;
    uint32_t count = 0;
    union { struct cmsghdr align; unsigned char bytes[CMSG_SPACE(32 * sizeof(int)) + CMSG_SPACE(sizeof(struct ucred))]; } control = {0};
    struct iovec iov = { .iov_base = &count, .iov_len = sizeof(count) };
    struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes) };
    int received[32], receivedc = 0, rights = 0, credentials = 0;
    if (!error) {
        ssize_t n = recvmsg(sockets[0], &msg, MSG_CMSG_CLOEXEC | MSG_DONTWAIT);
        if (n != (ssize_t)sizeof(count) || count != r->launch->mountc || (msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC))) error = EPROTO;
        for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
            if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS && c->cmsg_len >= CMSG_LEN(0)) {
                ++rights; size_t bytes = c->cmsg_len - CMSG_LEN(0);
                if (bytes != r->launch->mountc * sizeof(int)) error = EPROTO;
                for (size_t i = 0; i < bytes / sizeof(int); ++i) {
                    int fd; memcpy(&fd, (unsigned char *)CMSG_DATA(c) + i * sizeof(int), sizeof(fd));
                    if (receivedc < 32) received[receivedc++] = fd; else { close(fd); error = EPROTO; }
                }
            } else if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_CREDENTIALS && c->cmsg_len == CMSG_LEN(sizeof(struct ucred))) {
                struct ucred cred; memcpy(&cred, CMSG_DATA(c), sizeof(cred)); ++credentials;
                if (cred.pid != pid || cred.uid != r->launch->uid || cred.gid != r->launch->gid) error = ESTALE;
            } else error = EPROTO;
        }
        if (rights != 1 || credentials != 1 || (uint32_t)receivedc != r->launch->mountc) error = EPROTO;
    }
    for (int i = 0; i < receivedc; ++i) {
        struct stat st;
        const struct ajiu_launch_mount *m = &r->launch->mounts[i];
        if (fstat(received[i], &st) < 0 || (uint64_t)st.st_dev != m->source_dev || (uint64_t)st.st_ino != m->source_ino ||
            (m->kind == AJIU_MOUNT_DIRECTORY ? !S_ISDIR(st.st_mode) : !S_ISREG(st.st_mode))) error = ESTALE;
    }
    if (pidfd >= 0 && wait_pidfd(pidfd, 2500) < 0) {
        error = ETIMEDOUT;
        store(&r->shared->unsafe, 1);
    }
    pid_t reaped = waitpid(pid, &status, WNOHANG);
    if (reaped != pid) { store(&r->shared->unsafe, 1); error = ECHILD; }
    else if (!WIFEXITED(status) || WEXITSTATUS(status)) error = ECHILD;
    if (pidfd >= 0) close(pidfd);
    close(sockets[0]);
    if (error) {
        for (int i = 0; i < receivedc; ++i) close(received[i]);
        errno = error; return -1;
    }
    for (int i = 0; i < receivedc; ++i) r->source[i] = received[i];
    return 0;
}

static int track(struct runtime *r, pid_t pid, uint64_t expected_start, bool bootstrap)
{
    struct registry *registry = &r->registry;
    for (size_t i = 0; i < registry->count; ++i) if (registry->tasks[i].pid == pid) {
        int dead = pidfd_dead(registry->tasks[i].fd);
        if (dead < 0) return -1;
        if (!dead) return expected_start && registry->tasks[i].start != expected_start ? -1 : 0;
        close(registry->tasks[i].fd); registry->tasks[i] = registry->tasks[--registry->count]; break;
    }
    int fd = (int)syscall(SYS_pidfd_open, pid, 0);
    if (fd < 0) return errno == ESRCH ? 0 : -1;
    struct identity id;
    bool ok = member_snapshot(r, pid, expected_start, bootstrap, &id) == 0;
    int dead = pidfd_dead(fd);
    if (dead > 0) { close(fd); return 0; }
    if (!ok || dead < 0) { close(fd); errno = ESTALE; return -1; }
    if (registry->count == MAX_TASKS) { close(fd); errno = EOVERFLOW; return -1; }
    if (registry->count == registry->capacity) {
        size_t capacity = registry->capacity ? registry->capacity * 2 : 32;
        struct task *tasks = realloc(registry->tasks, capacity * sizeof(*tasks));
        if (!tasks) { close(fd); return -1; }
        registry->tasks = tasks; registry->capacity = capacity;
    }
    registry->tasks[registry->count++] = (struct task){ .pid = pid, .fd = fd, .start = id.start };
    return 0;
}

static int members_guard(struct runtime *r)
{
    if (group_guard(r) < 0) goto unsafe;
    for (size_t i = 0; i < r->registry.count;) {
        struct task *task = &r->registry.tasks[i];
        int dead = pidfd_dead(task->fd);
        if (dead > 0) { close(task->fd); *task = r->registry.tasks[--r->registry.count]; continue; }
        if (dead < 0) goto unsafe;
        struct identity id;
        bool bootstrap = load(&r->shared->enrolled) && task->pid == r->shared->bootstrap && task->start == r->shared->start;
        bool ok = member_snapshot(r, task->pid, task->start, bootstrap, &id) == 0;
        dead = pidfd_dead(task->fd);
        if (dead > 0) { close(task->fd); *task = r->registry.tasks[--r->registry.count]; continue; }
        if (!ok || dead < 0) goto unsafe;
        ++i;
    }
    char *buffer = malloc(MAX_TASKS * 12U + 1);
    if (!buffer) goto unsafe;
    if (text(r->a[PROCS].fd, buffer, MAX_TASKS * 12U + 1) < 0) { free(buffer); goto unsafe; }
    size_t count = 0;
    for (char *p = buffer; *p;) {
        char *end; errno = 0;
        long pid = strtol(p, &end, 10);
        if (errno || p == end || *p < '1' || *p > '9' || pid <= 1 || pid > INT_MAX || *end != '\n' || ++count > MAX_TASKS) {
            free(buffer); goto unsafe;
        }
        bool bootstrap = load(&r->shared->enrolled) && (pid_t)pid == r->shared->bootstrap;
        if (track(r, (pid_t)pid, bootstrap ? r->shared->start : 0, bootstrap) < 0) { free(buffer); goto unsafe; }
        p = end + 1;
    }
    free(buffer);
    return 0;
unsafe:
    store(&r->shared->unsafe, 1); errno = ESTALE; return -1;
}

static int signal_task(struct runtime *r, const struct task *task, int sig)
{
    int dead = pidfd_dead(task->fd);
    if (dead > 0) return 0;
    struct identity id;
    bool bootstrap = load(&r->shared->enrolled) && task->pid == r->shared->bootstrap && task->start == r->shared->start;
    bool valid = dead == 0 && member_snapshot(r, task->pid, task->start, bootstrap, &id) == 0;
    dead = pidfd_dead(task->fd);
    if (dead > 0) return 0;
    if (!valid || dead < 0) { store(&r->shared->unsafe, 1); errno = ESTALE; return -1; }
    if (syscall(SYS_pidfd_send_signal, task->fd, sig, NULL, 0) < 0 && errno != ESRCH) return -1;
    return 0;
}

static int signal_members(struct runtime *r, int sig)
{
    if (members_guard(r) < 0) return -1;
    for (size_t i = 0; i < r->registry.count; ++i)
        if (signal_task(r, &r->registry.tasks[i], sig) < 0) return -1;
    return 0;
}

static int kill_group(struct runtime *r)
{
    if (members_guard(r) < 0) return -1;
    TRY("kill_unique_cgroup", exact_write(r->a[KILLCTL].fd, "1\n", 2));
    return 0;
}

static int resolve_target(int root, const char *path, bool directory)
{
    return open_at(root, path, directory ? DF : O_PATH, RESOLVE_IN_ROOT);
}

static int attach_tree(int source, int target, bool readonly, bool recursive, bool executable)
{
    int tree = (int)syscall(SYS_open_tree, source, "", OPEN_TREE_CLONE | OPEN_TREE_CLOEXEC |
        AT_EMPTY_PATH | (recursive ? AT_RECURSIVE : 0));
    if (tree < 0) return -1;
    struct mount_attr attr = { .attr_set = MOUNT_ATTR_NOSUID | MOUNT_ATTR_NODEV |
        (readonly ? MOUNT_ATTR_RDONLY : 0) | (executable ? 0 : MOUNT_ATTR_NOEXEC),
        .attr_clr = (executable ? MOUNT_ATTR_NOEXEC : 0) | (readonly ? 0 : MOUNT_ATTR_RDONLY) };
    int rc = (int)syscall(SYS_mount_setattr, tree, "", AT_EMPTY_PATH | AT_RECURSIVE, &attr, sizeof(attr));
    if (!rc) rc = (int)syscall(SYS_move_mount, tree, "", target, "", MOVE_MOUNT_F_EMPTY_PATH | MOVE_MOUNT_T_EMPTY_PATH);
    int saved = errno; close(tree); errno = saved; return rc;
}

static int new_filesystem(int root, const char *target_name, const char *kind, const char *mode,
                           const char *size, const char *gid)
{
    int target = resolve_target(root, target_name, true);
    if (target < 0) return -1;
    int fs = (int)syscall(SYS_fsopen, kind, FSOPEN_CLOEXEC), tree = -1, rc = -1;
    if (fs < 0) goto done;
    bool devpts = !strcmp(kind, "devpts"), proc = !strcmp(kind, "proc");
    const char *keys[5] = { "mode", "size", "nr_inodes", NULL, NULL };
    const char *values[5] = { mode, size, "4096", NULL, NULL };
    size_t options = 3;
    if (devpts) {
        keys[0] = "newinstance"; values[0] = NULL; keys[1] = "mode"; values[1] = "0600";
        keys[2] = "ptmxmode"; values[2] = "0666"; keys[3] = "gid"; values[3] = gid; options = 4;
    } else if (proc) options = 0;
    for (size_t i = 0; i < options; ++i)
        if (syscall(SYS_fsconfig, fs, values[i] ? FSCONFIG_SET_STRING : FSCONFIG_SET_FLAG, keys[i], values[i], 0) < 0) goto done;
    if (syscall(SYS_fsconfig, fs, FSCONFIG_CMD_CREATE, NULL, NULL, 0) < 0) goto done;
    unsigned int attrs = MOUNT_ATTR_NOSUID | MOUNT_ATTR_NOEXEC |
        (devpts || !strcmp(target_name, "dev") ? 0 : MOUNT_ATTR_NODEV) | (proc ? MOUNT_ATTR_RDONLY : 0);
    tree = (int)syscall(SYS_fsmount, fs, FSMOUNT_CLOEXEC, attrs);
    if (tree < 0 || syscall(SYS_move_mount, tree, "", target, "", MOVE_MOUNT_F_EMPTY_PATH | MOVE_MOUNT_T_EMPTY_PATH) < 0) goto done;
    rc = 0;
done: {
        int saved = errno; close(target); if (tree >= 0) close(tree); if (fs >= 0) close(fs); errno = saved;
    }
    return rc;
}

static int mount_file_worker(struct runtime *r, int view)
{
    if (r->worker_fd < 0) return 0;
    if (protected_static_elf(r->worker_fd) < 0) return -1;
    int run = resolve_target(view, "run", true);
    if (run < 0) return -1;
    int target = openat(run, "ajiu-file-worker", O_CREAT | O_EXCL | O_WRONLY | OF, 0555);
    close(run);
    if (target < 0) return -1;
    int rc = fchmod(target, 0555);
    if (!rc) rc = attach_tree(r->worker_fd, target, true, false, true);
    int saved = errno; close(target); errno = saved; return rc;
}

static int mount_namespace(struct runtime *r, int *view)
{
    TRY("unshare_mount", unshare(CLONE_NEWNS));
    TRY("private_mounts", mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL));
    struct stat ns;
    TRY("private_namespace", stat("/proc/self/ns/mnt", &ns));
    NEED("new_mount_namespace", ns.st_dev != r->namespace.st_dev || ns.st_ino != r->namespace.st_ino);
    struct anchor old[ANCHORS]; memcpy(old, r->a, sizeof(old));
    for (int i = 0; i < ANCHORS; ++i) r->a[i] = (struct anchor){ .fd = -1 };
    if (instance(r, true) < 0) return -1;
    for (int i = LOCAL; i <= VIEW; ++i) {
        NEED("namespace_rootfs_identity", same(&old[i].st, &r->a[i].st));
        if (old[i].fd >= 0) close(old[i].fd);
    }
    TRY("namespace_cgroup_root", r->a[CGROOT].fd = protected_walk(true));
    if (snapshot(&r->a[CGROOT]) < 0 || anchored(&r->a[GROUP], r->a[CGROOT].fd, r->group_name, DF) < 0) return -1;
    for (int i = PROCS; i <= TYPE; ++i)
        if (anchored(&r->a[i], r->a[GROUP].fd, anchor_names[i], i == PROCS ? O_RDWR : i == KILLCTL ? O_WRONLY : O_RDONLY) < 0) return -1;
    for (int i = CGROOT; i <= TYPE; ++i) {
        NEED("namespace_cgroup_identity", same(&old[i].st, &r->a[i].st));
        if (old[i].fd >= 0) close(old[i].fd);
    }
    r->namespace = ns;
    if (group_guard(r) < 0) return -1;
    TRY("clone_writable_root", attach_tree(r->a[ROOT].fd, r->a[VIEW].fd, false, true, true));
    TRY("mounted_view", *view = resolve_target(r->a[VIEWS].fd, r->launch->launch_id, true));
    struct statfs fs;
    TRY("writable_os_flags", fstatfs(*view, &fs));
    NEED("writable_executable_os", (fs.f_flags & (ST_RDONLY | ST_NOSUID | ST_NODEV | ST_NOEXEC)) ==
        (ST_NOSUID | ST_NODEV));
    /* OPEN_TREE_CLONE requires source FDs opened in the caller's mount namespace. */
    if (open_sources(r) < 0 || reopen_worker(r) < 0) return -1;
    for (int pass = 0; pass < 2; ++pass) for (uint32_t i = 0; i < r->launch->mountc; ++i) {
        const struct ajiu_launch_mount *m = &r->launch->mounts[i];
        bool home = !strcmp(m->target_path, "/root");
        if ((pass == 0) != home) continue;
        struct stat source;
        TRY("source_fd_identity", fstat(r->source[i], &source));
        NEED("frozen_source", (uint64_t)source.st_dev == m->source_dev && (uint64_t)source.st_ino == m->source_ino &&
            (m->kind == AJIU_MOUNT_DIRECTORY ? S_ISDIR(source.st_mode) : S_ISREG(source.st_mode)));
        int target = resolve_target(*view, m->target_path, m->kind == AJIU_MOUNT_DIRECTORY);
        if (target < 0) return -1;
        struct stat target_st;
        int rc = fstat(target, &target_st);
        if (!rc && (m->kind == AJIU_MOUNT_DIRECTORY ? !S_ISDIR(target_st.st_mode) : !S_ISREG(target_st.st_mode))) { errno = EPROTO; rc = -1; }
        if (!rc) rc = attach_tree(r->source[i], target, m->read_only != 0, m->kind == AJIU_MOUNT_DIRECTORY, true);
        int saved = errno; close(target); if (rc < 0) { errno = saved; return -1; }
    }
    for (uint32_t i = 0; i < r->launch->mountc; ++i) {
        close(r->source[i]); r->source[i] = -1;
    }
    stage = "private_tmpfs";
    if (new_filesystem(*view, "tmp", "tmpfs", "1777", "256M", NULL) < 0 ||
        new_filesystem(*view, "run", "tmpfs", "0755", "16M", NULL) < 0 ||
        new_filesystem(*view, "dev", "tmpfs", "0755", "1M", NULL) < 0) return -1;
    if (mount_file_worker(r, *view) < 0) return -1;
    int dev = resolve_target(*view, "dev", true);
    if (dev < 0) return -1;
    const char *const devices[] = { "null", "zero", "random", "urandom", "tty" };
    const unsigned int majors[] = { 1, 1, 1, 1, 5 }, minors[] = { 3, 5, 8, 9, 0 };
    for (size_t i = 0; i < 5; ++i) {
        if (mknodat(dev, devices[i], S_IFCHR | 0666, makedev(majors[i], minors[i])) < 0 ||
            fchmodat(dev, devices[i], 0666, 0) < 0) { int saved = errno; close(dev); errno = saved; return -1; }
    }
    if (mkdirat(dev, "pts", 0755) < 0 || symlinkat("pts/ptmx", dev, "ptmx") < 0) { int saved = errno; close(dev); errno = saved; return -1; }
    close(dev);
    char gid[16]; (void)snprintf(gid, sizeof(gid), "%u", r->launch->gid);
    if (new_filesystem(*view, "dev/pts", "devpts", NULL, NULL, gid) < 0 ||
        new_filesystem(*view, "proc", "proc", NULL, NULL, NULL) < 0) return -1;
    return 0;
}

static int prepare_pty(const struct runtime *r, int view, struct controller_io *io)
{
    int pts = resolve_target(view, "dev/pts", true);
    if (pts < 0) return -1;
    io->master = open_at(pts, "ptmx", O_RDWR | O_NOCTTY, RESOLVE_BENEATH);
    close(pts);
    if (io->master < 0) return -1;
    int unlock = 0;
    TRY("pty_unlock", ioctl(io->master, TIOCSPTLCK, &unlock));
    TRY("pty_slave", io->slave = ioctl(io->master, TIOCGPTPEER, O_RDWR | O_NOCTTY | O_CLOEXEC));
    TRY("pty_slave_uid", fchown(io->slave, r->launch->uid, r->launch->gid));
    TRY("pty_slave_mode", fchmod(io->slave, 0600));
    struct stat st;
    TRY("pty_slave_stat", fstat(io->slave, &st));
    NEED("pty_slave_identity", S_ISCHR(st.st_mode) && st.st_uid == r->launch->uid && st.st_gid == r->launch->gid &&
        (st.st_mode & 07777) == 0600);
    struct winsize size = { .ws_col = 80, .ws_row = 24 };
    TRY("pty_initial_size", ioctl(io->master, TIOCSWINSZ, &size));
    struct termios t = { .c_iflag = ICRNL, .c_oflag = OPOST | ONLCR, .c_cflag = CS8 | CREAD | CLOCAL,
        .c_lflag = ICANON | ISIG | ECHO | ECHOE | ECHOK };
    t.c_cc[VINTR] = 3; t.c_cc[VEOF] = 4; t.c_cc[VQUIT] = 28; t.c_cc[VSUSP] = 26;
    t.c_cc[VERASE] = 127; t.c_cc[VKILL] = 21; t.c_cc[VMIN] = 1;
    TRY("pty_input_speed", cfsetispeed(&t, B38400)); TRY("pty_output_speed", cfsetospeed(&t, B38400));
    TRY("pty_termios", tcsetattr(io->slave, TCSAFLUSH, &t));
    io->input = io->master; io->output = io->master; io->error = -1;
    return 0;
}

static int prepare_io(const struct runtime *r, int view, struct controller_io *io)
{
    memset(io, 0xff, sizeof(*io));
    if (r->launch->io_mode == AJIU_IO_PTY) return prepare_pty(r, view, io);
    if (pipe2(io->in, O_CLOEXEC) < 0 || pipe2(io->out, O_CLOEXEC) < 0 || pipe2(io->err, O_CLOEXEC) < 0) return -1;
    io->input = io->in[1]; io->output = io->out[0]; io->error = io->err[0];
    if (nonblock(io->input) < 0 || nonblock(io->output) < 0 || nonblock(io->error) < 0) return -1;
    return 0;
}

static int byte_wait(int fd, unsigned char expected, int peer, int64_t deadline)
{
    for (;;) {
        int64_t now = now_ms();
        if (now < 0) return -1;
        if (now >= deadline || interrupted) { errno = ETIMEDOUT; return -1; }
        struct pollfd p[2] = { { fd, POLLIN | POLLHUP, 0 }, { peer, POLLIN, 0 } };
        int rc = poll(p, 2, (int)(deadline - now));
        if (rc < 0 && errno == EINTR) continue;
        if (rc < 0) return -1;
        if (p[1].revents || !rc) { errno = EPIPE; return -1; }
        if (p[0].revents) {
            unsigned char value;
            if (read(fd, &value, 1) == 1 && value == expected) return 0;
            errno = EPROTO; return -1;
        }
    }
}

static void child_failure(int report)
{
    struct child_result result = { IPC_MAGIC, 1, 0, (uint32_t)(errno > 0 && errno < 4096 ? errno : EIO) };
    (void)exact_write(report, &result, sizeof(result));
    _exit(127);
}

static void guest(struct runtime *r, int view, struct controller_io *io, int gate, int report, pid_t parent)
{
    (void)alarm(30);
    int keep[] = { view, gate, report };
    if (close_except(keep, 3) < 0) child_failure(report);
    if (signals(true) < 0 || prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0) < 0 || getppid() != parent) child_failure(report);
    if (r->launch->io_mode == AJIU_IO_PTY) {
        if (setsid() < 0 || ioctl(STDIN_FILENO, TIOCSCTTY, 0) < 0 || tcsetpgrp(STDIN_FILENO, getpid()) < 0) child_failure(report);
    } else if (setpgid(0, 0) < 0) child_failure(report);
    if (byte_wait(gate, 'M', -1, now_ms() + 15000) < 0) child_failure(report);
    if (fchdir(view) < 0 || chroot(".") < 0 || chdir("/") < 0 || close(view) < 0) child_failure(report);
    if (guest_credentials(r, parent) < 0 || chdir(r->launch->cwd) < 0) child_failure(report);
    struct child_result ready = { IPC_MAGIC, 0, 0, 0 };
    if (exact_write(report, &ready, sizeof(ready)) < 0 || byte_wait(gate, 'G', -1, now_ms() + r->launch->lease_ms) < 0) child_failure(report);
    if (close(gate) < 0) child_failure(report);
    (void)alarm(0);
    char *argv[AJIU_LAUNCH_MAX_ARGS + 4];
    argv[0] = (char *)"sh"; argv[1] = (char *)"-c"; argv[2] = (char *)"exec \"$0\" \"$@\"";
    for (uint32_t i = 0; i < r->launch->argc; ++i) argv[i + 3] = r->launch->argv[i];
    argv[r->launch->argc + 3] = NULL;
    int final[] = { report };
    if (close_except(final, 1) < 0) child_failure(report);
    (void)io;
    execve("/bin/sh", argv, r->launch->env);
    child_failure(report);
}

static int empty_directory(int fd)
{
    int copy = open_at(fd, ".", DF, RESOLVE_BENEATH);
    if (copy < 0) return -1;
    DIR *dir = fdopendir(copy);
    if (!dir) { close(copy); return -1; }
    int error = 0; size_t count = 0;
    for (;;) {
        errno = 0; struct dirent *entry = readdir(dir);
        if (!entry) { error = errno; break; }
        if (++count > 2 || (strcmp(entry->d_name, ".") && strcmp(entry->d_name, ".."))) { error = ESTALE; break; }
    }
    if (closedir(dir) < 0 && !error) error = errno;
    if (error) { errno = error; return -1; }
    return 0;
}

static int remove_resources(struct runtime *r)
{
    NEED("cleanup_identity", !load(&r->shared->unsafe));
    if (r->group_made) {
        if (members_guard(r) < 0) return -1;
        NEED("cleanup_empty_group", populated(r) == 0 && r->registry.count == 0);
    }
    if (r->view_made) {
        int local = protected_walk(false);
        if (local < 0) return -1;
        int rc = current(&r->a[BASE], local, r->base_name), saved = errno; close(local);
        if (rc < 0) { errno = saved; return -1; }
        for (int i = ROOT; i <= VIEWS; ++i)
            if (current(&r->a[i], r->a[BASE].fd, anchor_names[i]) < 0) return -1;
        if (current(&r->a[VIEW], r->a[VIEWS].fd, r->launch->launch_id) < 0 || empty_directory(r->a[VIEW].fd) < 0) return -1;
        TRY("remove_empty_view", unlinkat(r->a[VIEWS].fd, r->launch->launch_id, AT_REMOVEDIR));
        r->view_made = false;
    }
    if (r->group_made) {
        if (group_guard(r) < 0 || populated(r) != 0) return -1;
        TRY("remove_empty_cgroup", unlinkat(r->a[CGROOT].fd, r->group_name, AT_REMOVEDIR));
        r->group_made = false;
    }
    return 0;
}

static void close_runtime(struct runtime *r)
{
    if (r->native_dir >= 0) { close(r->native_dir); r->native_dir = -1; }
    if (r->worker_fd >= 0) { close(r->worker_fd); r->worker_fd = -1; }
    if (r->app_fd >= 0) { close(r->app_fd); r->app_fd = -1; }
    for (size_t i = 0; i < r->registry.count; ++i) close(r->registry.tasks[i].fd);
    free(r->registry.tasks); r->registry.tasks = NULL; r->registry.count = r->registry.capacity = 0;
    for (int i = 0; i < ANCHORS; ++i) if (r->a[i].fd >= 0) { close(r->a[i].fd); r->a[i].fd = -1; }
    for (size_t i = 0; i < AJIU_LAUNCH_MAX_MOUNTS; ++i) if (r->source[i] >= 0) { close(r->source[i]); r->source[i] = -1; }
}

static int restore_namespace(struct runtime *r, const struct runtime *original, int view)
{
    struct anchor mounted = { .fd = view };
    if (snapshot(&mounted) < 0 || current(&mounted, r->a[VIEWS].fd, r->launch->launch_id) < 0 ||
        current(&r->a[BASE], r->a[LOCAL].fd, r->base_name) < 0 ||
        current(&r->a[VIEWS], r->a[BASE].fd, "views") < 0) return -1;
    if (view >= 0) close(view);
    char path[PATH_MAX];
    int n = snprintf(path, sizeof(path), "/data/local/%s/views/%s", r->base_name, r->launch->launch_id);
    NEED("view_unmount_name", n > 0 && (size_t)n < sizeof(path));
    TRY("unmount_private_view", umount2(path, MNT_DETACH));
    close_runtime(r);
    for (int i = 0; i < ANCHORS; ++i) r->a[i] = (struct anchor){ .fd = -1 };
    if (instance(r, true) < 0) return -1;
    for (int i = LOCAL; i <= VIEW; ++i)
        NEED("unmounted_view_identity", same(&r->a[i].st, &original->a[i].st));
    TRY("cleanup_cgroup_parent", r->a[CGROOT].fd = protected_walk(true));
    if (snapshot(&r->a[CGROOT]) < 0 || anchored(&r->a[GROUP], r->a[CGROOT].fd, r->group_name, DF) < 0) return -1;
    for (int i = PROCS; i <= TYPE; ++i)
        if (anchored(&r->a[i], r->a[GROUP].fd, anchor_names[i], i == PROCS ? O_RDWR : i == KILLCTL ? O_WRONLY : O_RDONLY) < 0) return -1;
    for (int i = CGROOT; i <= TYPE; ++i)
        NEED("cleanup_cgroup_identity", same(&r->a[i].st, &original->a[i].st));
    if (group_guard(r) < 0 || empty_directory(r->a[VIEW].fd) < 0) return -1;
    store(&r->shared->namespace_closed, 1);
    return 0;
}

static int flush_input(struct controller_io *io, unsigned char *pending, size_t *used, size_t *offset, bool eof, bool pty)
{
    if (*offset < *used && io->input >= 0) {
        ssize_t n = write(io->input, pending + *offset, *used - *offset);
        if (n > 0) *offset += (size_t)n;
        else if (n < 0 && errno != EAGAIN && errno != EINTR) {
            if (errno != EPIPE && !(pty && errno == EIO)) return -1;
            if (!pty) close(io->input);
            io->input = -1; *offset = *used;
        }
    }
    if (*offset == *used) {
        *offset = *used = 0;
        if (eof && !pty && io->input >= 0) { close(io->input); io->input = -1; }
    }
    return 0;
}

static int output_step(int socket, int *fd, uint32_t type, bool pty, bool discard)
{
    if (*fd < 0) return 0;
    unsigned char bytes[16384];
    ssize_t n = read(*fd, bytes, sizeof(bytes));
    if (n > 0) return discard ? 0 : send_packet(socket, type, bytes, (uint32_t)n, FRAME_WAIT);
    if (n < 0 && (errno == EAGAIN || errno == EINTR)) return 0;
    if (n < 0 && !(pty && errno == EIO)) return -1;
    close(*fd); *fd = -1;
    return 0;
}

static void controller(struct runtime *r, int socket, pid_t parent, int parentfd, uint64_t parent_start)
{
    struct runtime original = *r;
    struct controller_io io;
    memset(&io, 0xff, sizeof(io));
    int view = -1, gate[2] = {-1, -1}, report[2] = {-1, -1}, guestfd = -1;
    pid_t pid = -1;
    bool prepared = false, running = false, stopped = false, killed = false, exited = false, no_children = false;
    bool input_eof = false, peer_dead = false, quiet = false, exit_sent = false, migration_resolved = false;
    int error = 0, result_status = 0;
    int64_t start = now_ms(), stop_at = 0, last_parent = start;
    unsigned char pending[AJIU_MAX_FRAME_BYTES]; size_t pending_used = 0, pending_offset = 0;
    int nullfd = open_at(AT_FDCWD, "/dev/null", O_RDWR, 0);
    struct stat nullstat;
    if (nullfd < 0 || fstat(nullfd, &nullstat) < 0 || !S_ISCHR(nullstat.st_mode) || nullstat.st_rdev != makedev(1, 3) ||
        dup2(nullfd, STDIN_FILENO) < 0 || dup2(nullfd, STDOUT_FILENO) < 0 || dup2(nullfd, STDERR_FILENO) < 0) _exit(5);
    if (nullfd > 2) close(nullfd);
    if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) < 0 || protected_role(r, parent, parentfd, parent_start) != 0 ||
        mount_namespace(r, &view) < 0 || prepare_io(r, view, &io) < 0 ||
        pipe2(gate, O_CLOEXEC | O_NONBLOCK) < 0 || pipe2(report, O_CLOEXEC | O_NONBLOCK) < 0) error = errno ? errno : EIO;
    pid_t controller_pid = getpid();
    if (!error) {
        pid = fork();
        if (!pid) {
            int input = r->launch->io_mode == AJIU_IO_PTY ? io.slave : io.in[0];
            int output = r->launch->io_mode == AJIU_IO_PTY ? io.slave : io.out[1];
            int err = r->launch->io_mode == AJIU_IO_PTY ? io.slave : io.err[1];
            if (dup2(input, STDIN_FILENO) < 0 || dup2(output, STDOUT_FILENO) < 0 || dup2(err, STDERR_FILENO) < 0) child_failure(report[1]);
            guest(r, view, &io, gate[0], report[1], controller_pid);
        }
        if (pid < 0) error = errno;
    }
    if (pid > 0) {
        close(gate[0]); gate[0] = -1; close(report[1]); report[1] = -1;
        int child_ends[] = { io.slave, io.in[0], io.out[1], io.err[1] };
        for (size_t i = 0; i < 4; ++i) if (child_ends[i] >= 0) close(child_ends[i]);
        io.slave = io.in[0] = io.out[1] = io.err[1] = -1;
        guestfd = (int)syscall(SYS_pidfd_open, pid, 0);
        struct enrollment enroll = { .pid = pid }; pid_t ppid;
        if (guestfd < 0 || proc_start(pid, &enroll.start, &ppid) < 0 || ppid != getpid() ||
            send_packet(socket, MSG_ENROLL, &enroll, sizeof(enroll), FRAME_WAIT) < 0) error = errno ? errno : ESTALE;
    }
    if (error) {
        uint32_t code = (uint32_t)(error > 0 && error < 4096 ? error : EIO);
        (void)send_packet(socket, MSG_FAILURE, &code, sizeof(code), FRAME_WAIT);
        stopped = true; stop_at = now_ms();
    }
    struct packet packet;
    for (;;) {
        int64_t now = now_ms();
        if (now < 0) { now = start + r->launch->lease_ms + CLEANUP_WAIT; error = EIO; }
        int parent_state = protected_role(r, parent, parentfd, parent_start);
        if (parent_state != 0 || interrupted || now - last_parent >= r->launch->lease_ms ||
            (!prepared && now - start >= 15000) || (running && r->launch->command_timeout_ms &&
            now - start >= r->launch->command_timeout_ms)) {
            if (!stopped) { stopped = true; stop_at = now; }
            if (parent_state != 0) peer_dead = true;
        }
        if (pending_offset && pending_used) {
            memmove(pending, pending + pending_offset, pending_used - pending_offset);
            pending_used -= pending_offset; pending_offset = 0;
        }
        bool can_receive = pending_used <= sizeof(pending) - AJIU_MAX_DATA_BYTES;
        if (!peer_dead && can_receive) {
            int rc = receive_packet(socket, &packet);
            if (rc < 0) { peer_dead = true; if (!stopped) { stopped = true; stop_at = now; } }
            else if (rc > 0) {
                last_parent = now;
                if (packet.type == AJIU_HEARTBEAT && !packet.length) { }
                else if (packet.type == MSG_GATED && !packet.length && !prepared && !migration_resolved) {
                    migration_resolved = true;
                    if (!stopped && exact_write(gate[1], "M", 1) < 0) error = errno;
                } else if (packet.type == MSG_ENROLL_ABORTED && !packet.length && !migration_resolved) {
                    migration_resolved = true;
                    if (!stopped) { stopped = true; stop_at = now; }
                } else if (packet.type == AJIU_GO && !packet.length && prepared && !running && !stopped) {
                    if (exact_write(gate[1], "G", 1) < 0) error = errno;
                    else { running = true; start = now; store(&r->shared->executing, 1); close(gate[1]); gate[1] = -1; }
                } else if (packet.type == AJIU_STDIN && running && !stopped && !input_eof) {
                    memcpy(pending + pending_used, packet.body, packet.length); pending_used += packet.length;
                } else if (packet.type == AJIU_STDIN_EOF && !packet.length && running && !stopped && !input_eof) {
                    input_eof = true;
                    if (r->launch->io_mode == AJIU_IO_PTY) pending[pending_used++] = 4;
                } else if (packet.type == AJIU_RESIZE && packet.length == 4 && running && !stopped && io.master >= 0) {
                    struct winsize size = { .ws_col = (unsigned short)(((unsigned int)packet.body[0] << 8) | packet.body[1]),
                        .ws_row = (unsigned short)(((unsigned int)packet.body[2] << 8) | packet.body[3]) };
                    if (!size.ws_col || !size.ws_row || size.ws_col > 4096 || size.ws_row > 4096 || ioctl(io.master, TIOCSWINSZ, &size) < 0) error = EPROTO;
                } else if (packet.type == AJIU_STOP && !packet.length) {
                    if (!stopped) { stopped = true; stop_at = now; }
                } else if (packet.type == MSG_FINISH && !packet.length && quiet) break;
                else error = EPROTO;
            }
        }
        if (!prepared && report[0] >= 0) {
            struct child_result result;
            ssize_t n = read(report[0], &result, sizeof(result));
            if (n == (ssize_t)sizeof(result) && result.magic == IPC_MAGIC && result.kind == 0 && !result.error && !result.status) {
                struct identity id;
                if (!load(&r->shared->enrolled) ||
                    member_snapshot(r, pid, r->shared->start, true, &id) < 0 ||
                    !guest_initial_identity(r, &id)) error = ESTALE;
                else {
                    store(&r->shared->dropped, 1); prepared = true;
                    if (send_packet(socket, MSG_PREPARED, NULL, 0, FRAME_WAIT) < 0) error = errno;
                }
            } else if (n > 0 || n == 0 || (n < 0 && errno != EAGAIN && errno != EINTR)) error = EPROTO;
        } else if (report[0] >= 0) {
            struct child_result result; ssize_t n = read(report[0], &result, sizeof(result));
            if (!n) { close(report[0]); report[0] = -1; }
            else if (n > 0 || (n < 0 && errno != EAGAIN && errno != EINTR)) error = EPROTO;
        }
        if (error && !stopped) {
            uint32_t code = (uint32_t)(error > 0 && error < 4096 ? error : EIO);
            (void)send_packet(socket, MSG_FAILURE, &code, sizeof(code), FRAME_WAIT);
            stopped = true; stop_at = now;
        }
        if (flush_input(&io, pending, &pending_used, &pending_offset, input_eof, r->launch->io_mode == AJIU_IO_PTY) < 0) error = errno;
        bool discard = peer_dead || (stopped && error);
        if (output_step(socket, &io.output, AJIU_STDOUT, r->launch->io_mode == AJIU_IO_PTY, discard) < 0 ||
            output_step(socket, &io.error, AJIU_STDERR, false, discard) < 0) {
            peer_dead = true; if (!stopped) { stopped = true; stop_at = now; }
        }
        if (r->launch->io_mode == AJIU_IO_PTY && io.output < 0) io.master = io.input = -1;
        if (!migration_resolved && pidfd_dead(parentfd) == 1) migration_resolved = true;
        for (size_t i = 0; (pid < 0 || migration_resolved) && i < MAX_TASKS + 1; ++i) {
            int status; pid_t got = waitpid(-1, &status, WNOHANG);
            if (got > 0) {
                if (got == pid) {
                    exited = true; result_status = status;
                    if (!stopped) { stopped = true; stop_at = now; }
                }
                continue;
            }
            if (got < 0 && errno == ECHILD) no_children = true;
            else if (got < 0 && errno != EINTR) error = errno;
            break;
        }
        if (pid < 0) no_children = true;
        if (stopped) {
            if (gate[1] >= 0) { close(gate[1]); gate[1] = -1; }
            if (!killed && now - stop_at >= (exited || error ? 0 : 500)) {
                if (kill_group(r) < 0) error = errno;
                else killed = true;
            }
            int pop = r->group_made && !load(&r->shared->unsafe) ? populated(r) : -1;
            bool io_done = io.output < 0 && io.error < 0;
            if (exited && !exit_sent && (io_done || error || peer_dead)) {
                unsigned char exit_body[8] = {0};
                exit_body[3] = WIFSIGNALED(result_status) ? 1 : 0;
                exit_body[7] = (unsigned char)(WIFSIGNALED(result_status) ? WTERMSIG(result_status) :
                    WIFEXITED(result_status) ? WEXITSTATUS(result_status) : 127);
                if (!peer_dead && send_packet(socket, MSG_EXITED, exit_body, sizeof(exit_body), FRAME_WAIT) < 0) peer_dead = true;
                exit_sent = true;
            }
            if (!quiet && no_children && pop == 0 && (io_done || error || peer_dead)) {
                int io_fds[] = { io.input, io.output, io.error, io.master, io.slave };
                for (size_t i = 0; i < 5; ++i) if (io_fds[i] >= 0) {
                    bool duplicate = false;
                    for (size_t j = 0; j < i; ++j) if (io_fds[i] == io_fds[j]) duplicate = true;
                    if (!duplicate) close(io_fds[i]);
                }
                io.input = io.output = io.error = io.master = io.slave = -1;
                if (restore_namespace(r, &original, view) < 0) { error = errno; store(&r->shared->unsafe, 1); }
                else {
                    view = -1; quiet = true;
                    if (!peer_dead && send_packet(socket, MSG_QUIET, NULL, 0, FRAME_WAIT) < 0) peer_dead = true;
                }
            }
            if (quiet && peer_dead) {
                if (protected_role(r, parent, parentfd, parent_start) == 1) (void)remove_resources(r);
                break;
            }
            if (now - stop_at >= CLEANUP_WAIT) break;
        }
        struct pollfd pf[5] = { { can_receive ? socket : -1, POLLIN, 0 }, { parentfd, POLLIN, 0 },
            { io.output, POLLIN, 0 }, { io.error, POLLIN, 0 }, { pending_used ? io.input : -1, POLLOUT, 0 } };
        if (poll(pf, 5, 20) < 0 && errno != EINTR) { error = errno; if (!stopped) { stopped = true; stop_at = now; } }
    }
    close_runtime(r);
    close(socket); close(parentfd);
    _exit(quiet && !load(&r->shared->unsafe) ? 0 : 5);
}

static int output_frame(bool *usable, uint16_t type, const unsigned char *body, uint32_t length)
{
    if (!*usable) { errno = EPIPE; return -1; }
    if (ajiu_write_frame(STDOUT_FILENO, type, body, length, FRAME_WAIT) == 0) return 0;
    *usable = false;
    return -1;
}

static void public_error(bool *usable, const char *code, const char *where, int error)
{
    char message[192];
    if (error <= 0 || error >= 4096) error = EIO;
    int n = snprintf(message, sizeof(message), "code=%s;stage=%s;errno=%d", code, where, error);
    if (n > 0 && (size_t)n < sizeof(message))
        (void)output_frame(usable, AJIU_ERROR, (const unsigned char *)message, (uint32_t)n);
    char diagnostic[224];
    int diagnostic_size = snprintf(diagnostic, sizeof(diagnostic), "ajiu_runtime: code=%s;stage=%s;errno=%d\n", code, where, error);
    if (diagnostic_size > 0 && (size_t)diagnostic_size < sizeof(diagnostic)) {
        ssize_t diagnostic_written = write(STDERR_FILENO, diagnostic, (size_t)diagnostic_size);
        (void)diagnostic_written;
    }
}

static int enroll_bootstrap(struct runtime *r, const struct enrollment *enroll, pid_t controller_pid)
{
    NEED("single_bootstrap", !load(&r->shared->enrolled) && !load(&r->shared->guest_ns_ready) &&
        enroll->pid > 1 && enroll->start && !r->registry.count);
    int fd = (int)syscall(SYS_pidfd_open, enroll->pid, 0);
    if (fd < 0) return -1;
    int error = 0;
    if (group_guard(r) < 0) { error = errno; goto unsafe; }
    if (!r->registry.capacity) {
        struct task *tasks = realloc(r->registry.tasks, 32 * sizeof(*tasks));
        if (!tasks) { error = errno; goto failed; }
        r->registry.tasks = tasks; r->registry.capacity = 32;
    }
    char value[32];
    int n = snprintf(value, sizeof(value), "%ld\n", (long)enroll->pid);
    if (n <= 0 || (size_t)n >= sizeof(value)) { error = EOVERFLOW; goto failed; }
    struct identity id;
    struct stat guest_ns, controller_ns, again_ns;
    stage = "bootstrap_identity";
    bool valid = pidfd_dead(fd) == 0 && identity(enroll->pid, &id) == 0 &&
        id.start == enroll->start && id.parent == controller_pid && process_group(enroll->pid, r->group_name, false) == 0 &&
        process_namespace(enroll->pid, &guest_ns) == 0 && process_namespace(controller_pid, &controller_ns) == 0 &&
        guest_ns.st_dev == controller_ns.st_dev && guest_ns.st_ino == controller_ns.st_ino &&
        (guest_ns.st_dev != r->namespace.st_dev || guest_ns.st_ino != r->namespace.st_ino);
    for (size_t i = 0; valid && i < 4; ++i) valid = id.uid[i] == 0 && id.gid[i] == 0;
    if (!valid || pidfd_dead(fd) != 0) goto unsafe;
    stage = "bootstrap_join_group";
    if (exact_write(r->a[PROCS].fd, value, (size_t)n) < 0) { error = errno; goto unsafe; }
    stage = "bootstrap_join_identity";
    valid = pidfd_dead(fd) == 0 && identity(enroll->pid, &id) == 0 &&
        id.start == enroll->start && id.parent == controller_pid && process_group(enroll->pid, r->group_name, true) == 0 &&
        process_namespace(enroll->pid, &again_ns) == 0 &&
        again_ns.st_dev == guest_ns.st_dev && again_ns.st_ino == guest_ns.st_ino;
    for (size_t i = 0; valid && i < 4; ++i) valid = id.uid[i] == 0 && id.gid[i] == 0;
    if (!valid || pidfd_dead(fd) != 0) goto unsafe;
    r->registry.tasks[r->registry.count++] = (struct task){ .pid = enroll->pid, .fd = fd, .start = enroll->start };
    fd = -1;
    r->shared->guest_ns_dev = guest_ns.st_dev; r->shared->guest_ns_ino = guest_ns.st_ino;
    store(&r->shared->guest_ns_ready, 1);
    r->shared->bootstrap = enroll->pid; r->shared->start = enroll->start;
    store(&r->shared->enrolled, 1);
    if (members_guard(r) < 0) return -1;
    if (r->registry.count != 1) goto unsafe;
    return 0;
unsafe:
    store(&r->shared->unsafe, 1);
    if (!error) error = ESTALE;
failed:
    if (fd >= 0) close(fd);
    errno = error ? error : EIO;
    return -1;
}

static int connect_control(struct runtime *r)
{
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    const char *prefix = "ajiu-chroot-control-";
    size_t prefix_length = strlen(prefix);
    memcpy(address.sun_path + 1, prefix, prefix_length);
    static const char hex[] = "0123456789abcdef";
    for (size_t i = 0; i < 32; ++i) {
        address.sun_path[1 + prefix_length + i * 2] = hex[r->launch->token[i] >> 4];
        address.sun_path[2 + prefix_length + i * 2] = hex[r->launch->token[i] & 15];
    }
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return -1;
    socklen_t size = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + prefix_length + 64);
    int rc = connect(fd, (const struct sockaddr *)&address, size), error = 0;
    if (rc < 0) {
        if (errno != EINPROGRESS) error = errno;
        else {
            struct pollfd p = { .fd = fd, .events = POLLOUT };
            int64_t deadline = now_ms() + 5000;
            for (;;) {
                int64_t now = now_ms();
                if (now < 0 || now >= deadline || interrupted) { error = ETIMEDOUT; break; }
                rc = poll(&p, 1, (int)(deadline - now));
                if (rc < 0 && errno == EINTR) continue;
                if (rc <= 0) { error = rc < 0 ? errno : ETIMEDOUT; break; }
                socklen_t n = sizeof(error);
                if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &n) < 0 || n != sizeof(error)) error = EIO;
                break;
            }
        }
    }
    struct ucred peer; socklen_t n = sizeof(peer);
    if (!error && (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &n) < 0 || n != sizeof(peer) ||
        peer.pid <= 1 || peer.uid != r->launch->uid || peer.gid != r->launch->gid)) error = EPERM;
    if (!error) {
        r->app_fd = (int)syscall(SYS_pidfd_open, peer.pid, 0);
        struct identity app;
        bool valid = r->app_fd >= 0 && identity(peer.pid, &app) == 0 && pidfd_dead(r->app_fd) == 0 &&
            app.groupc == r->launch->group_count && !app.caps[0] && !app.caps[1] && !app.caps[2] && !app.caps[4];
        for (size_t i = 0; valid && i < 4; ++i) valid = app.uid[i] == r->launch->uid && app.gid[i] == r->launch->gid;
        for (uint32_t i = 0; valid && i < app.groupc; ++i) {
            bool found = false;
            for (uint32_t j = 0; j < r->launch->group_count; ++j) if (app.groups[i] == r->launch->groups[j]) found = true;
            if (!found) valid = false;
        }
        if (!valid) error = ESTALE;
    }
    if (!error && ajiu_write_frame(fd, AJIU_READY, r->launch->token, 32, FRAME_WAIT) < 0) error = errno;
    if (error) { close(fd); errno = error; return -1; }
    return fd;
}

static bool read_control(int control, struct ajiu_launch_session *session, bool ready,
                         struct ajiu_frame_header *header, unsigned char command[4],
                         bool *stopped, int64_t now, int64_t *stop_at, int *error)
{
    int rc = ajiu_read_frame(control, header, command, 4, FRAME_WAIT);
    if (rc == 0) {
        *stopped = true; *stop_at = now;
        (void)shutdown(control, SHUT_RDWR);
        return false;
    }
    bool allowed = rc == 1 && (header->type == AJIU_GO || header->type == AJIU_HEARTBEAT ||
        header->type == AJIU_STOP || header->type == AJIU_SIGNAL || header->type == AJIU_RESIZE);
    if (!allowed || (header->type == AJIU_GO && !ready) ||
        ajiu_launch_session_accept(session, header, command, NULL) < 0) {
        *stopped = true; *stop_at = now; *error = errno ? errno : EPROTO;
        (void)shutdown(control, SHUT_RDWR);
        return false;
    }
    return true;
}

static int supervisor(struct runtime *r, struct ajiu_launch_session *session, bool *output_ok, unsigned char *body, int control)
{
    int sockets[2] = {-1, -1}, controllerfd = -1, parentfd = -1;
    int error = 0, controller_status = 0;
    bool ready = false, stopped = false, killed = false, controller_exited = false;
    bool no_children = false, got_exit = false, quiet = false, input_ok = true, controller_ok = true;
    bool enrollment_closed = false, migration_acknowledged = false;
    int64_t start = now_ms(), lease_at = start, command_at = 0, stop_at = 0, ping_at = start;
    pid_t parent = getpid(), parent_ppid, controller_pid = -1;
    uint64_t parent_start = 0, controller_start = 0;
    stage = "control_channel";
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, sockets) < 0 ||
        (parentfd = (int)syscall(SYS_pidfd_open, parent, 0)) < 0 || proc_start(parent, &parent_start, &parent_ppid) < 0) goto setup_failed;
    controller_pid = fork();
    if (!controller_pid) {
        close(sockets[0]); close(control);
        if (r->app_fd >= 0) { close(r->app_fd); r->app_fd = -1; }
        controller(r, sockets[1], parent, parentfd, parent_start);
    }
    if (controller_pid < 0) goto setup_failed;
    close(sockets[1]); sockets[1] = -1; close(parentfd); parentfd = -1;
    controllerfd = (int)syscall(SYS_pidfd_open, controller_pid, 0);
    pid_t ppid;
    if (controllerfd < 0 || proc_start(controller_pid, &controller_start, &ppid) < 0 || ppid != parent) {
        error = ESTALE; stopped = true; stop_at = now_ms(); controller_ok = false;
    }
    struct packet packet;
    for (;;) {
        int64_t now = now_ms();
        if (now < 0) { now = start + r->launch->lease_ms + CLEANUP_WAIT; if (!error) error = EIO; }
        if (interrupted || pidfd_dead(r->app_fd) != 0 || now - lease_at >= r->launch->lease_ms || (!ready && now - start >= 15000) ||
            (command_at && r->launch->command_timeout_ms && now - command_at >= r->launch->command_timeout_ms)) {
            if (!stopped) { stopped = true; stop_at = now; }
            if (!error) error = interrupted ? ECANCELED : ETIMEDOUT;
        }
        if (controller_ok && !controller_exited) {
            int state = protected_role(r, controller_pid, controllerfd, controller_start);
            if (state < 0) {
                controller_ok = false; store(&r->shared->unsafe, 1);
                if (!error) error = ESTALE;
                if (!stopped) { stopped = true; stop_at = now; }
            } else if (state > 0 && !stopped) { stopped = true; stop_at = now; }
        }
        if (!stopped) {
            struct pollfd input = { .fd = control, .events = POLLIN | POLLHUP };
            int rc = poll(&input, 1, 0);
            if (rc < 0 && errno != EINTR) { error = errno; stopped = true; stop_at = now; }
            if (rc > 0 && input.revents) {
                struct ajiu_frame_header header;
                unsigned char command[4];
                if (read_control(control, session, ready, &header, command, &stopped, now, &stop_at, &error)) {
                    lease_at = now_ms();
                    if (header.type == AJIU_STOP) { stopped = true; stop_at = now; }
                    else if (header.type == AJIU_SIGNAL) {
                        if (signal_members(r, command[0]) < 0) error = errno;
                    } else {
                        if (header.type == AJIU_RESIZE && r->launch->io_mode != AJIU_IO_PTY) error = EPROTO;
                        else if (send_packet(sockets[0], header.type, command, header.length, FRAME_WAIT) < 0) error = errno;
                        if (!error && header.type == AJIU_GO) command_at = now;
                    }
                }
            }
        }
        for (size_t drain = 0; controller_ok && drain < 4; ++drain) {
            int rc = receive_packet(sockets[0], &packet);
            if (rc < 0) {
                controller_ok = false;
                if (!stopped) { stopped = true; stop_at = now; }
                break;
            }
            if (!rc) break;
            if (packet.type == MSG_ENROLL && packet.length == sizeof(struct enrollment) && !ready && !stopped && !enrollment_closed) {
                enrollment_closed = true;
                struct enrollment enroll; memcpy(&enroll, packet.body, sizeof(enroll));
                if (enroll_bootstrap(r, &enroll, controller_pid) < 0 || send_packet(sockets[0], MSG_GATED, NULL, 0, FRAME_WAIT) < 0) error = errno;
                else migration_acknowledged = true;
            } else if (packet.type == MSG_PREPARED && !packet.length && !ready && !stopped && load(&r->shared->dropped)) {
                if (members_guard(r) < 0 || output_frame(output_ok, AJIU_READY, r->launch->token, 32) < 0) error = errno;
                else ready = true;
            } else if ((packet.type == AJIU_STDOUT || packet.type == AJIU_STDERR) && session->go_seen) {
                if (*output_ok && output_frame(output_ok, (uint16_t)packet.type, packet.body, packet.length) < 0) error = errno;
            } else if (packet.type == MSG_EXITED && packet.length == 8 && !got_exit) {
                if (packet.body[0] || packet.body[1] || packet.body[2] || packet.body[3] > 1 ||
                    packet.body[4] || packet.body[5] || packet.body[6]) error = EPROTO;
                else {
                    got_exit = true;
                    if (*output_ok && output_frame(output_ok, AJIU_EXIT, packet.body, packet.length) < 0) error = errno;
                }
                if (!stopped) { stopped = true; stop_at = now; }
            } else if (packet.type == MSG_QUIET && !packet.length && stopped && !quiet && load(&r->shared->namespace_closed)) {
                quiet = true;
                if (send_packet(sockets[0], MSG_FINISH, NULL, 0, FRAME_WAIT) < 0) controller_ok = false;
            } else if (packet.type == MSG_FAILURE && packet.length == 4) {
                uint32_t code; memcpy(&code, packet.body, sizeof(code));
                if (!error) error = code > 0 && code < 4096 ? (int)code : EIO;
            } else error = EPROTO;
            if (error && !stopped) { stopped = true; stop_at = now; }
        }
        if (!stopped && input_ok) {
            struct pollfd input = { .fd = STDIN_FILENO, .events = POLLIN | POLLHUP };
            int rc = poll(&input, 1, 0);
            if (rc < 0 && errno != EINTR) { error = errno; stopped = true; stop_at = now; }
            if (rc > 0 && input.revents) {
                struct ajiu_frame_header header;
                unsigned int timeout = FRAME_WAIT;
                int64_t remaining = lease_at + r->launch->lease_ms - now;
                if (remaining > 0 && remaining < timeout) timeout = (unsigned int)remaining;
                rc = ajiu_read_frame(STDIN_FILENO, &header, body, AJIU_MAX_FRAME_BYTES, timeout);
                if (rc != 1 || (header.type != AJIU_STDIN && header.type != AJIU_STDIN_EOF) ||
                    ajiu_launch_session_accept(session, &header, body, NULL) < 0) {
                    input_ok = false; stopped = true; stop_at = now; error = rc == 0 ? EPIPE : errno ? errno : EPROTO;
                } else if (send_packet(sockets[0], header.type, body, header.length, FRAME_WAIT) < 0) error = errno;
            }
        }
        if (!*output_ok && !stopped) { stopped = true; stop_at = now; error = EPIPE; }
        if (error && !stopped) { stopped = true; stop_at = now; }
        if (!stopped && controller_ok && now - ping_at >= 500) {
            if (send_packet(sockets[0], AJIU_HEARTBEAT, NULL, 0, FRAME_WAIT) < 0) { error = errno; stopped = true; stop_at = now; }
            ping_at = now;
        }
        if (!stopped && load(&r->shared->dropped) && members_guard(r) < 0) {
            error = errno; stopped = true; stop_at = now;
        }
        if (stopped) {
            enrollment_closed = true;
            if (!migration_acknowledged && controller_ok &&
                send_packet(sockets[0], MSG_ENROLL_ABORTED, NULL, 0, FRAME_WAIT) == 0) migration_acknowledged = true;
            session->phase = AJIU_PHASE_STOPPING;
            if (!killed && stop_at && now - stop_at < 500) {
                if (controller_ok) (void)send_packet(sockets[0], AJIU_STOP, NULL, 0, 50);
                if (!load(&r->shared->unsafe)) (void)signal_members(r, SIGTERM);
            }
            if (!killed && now - stop_at >= 500) {
                if (kill_group(r) < 0) { if (!error) error = errno; }
                else killed = true;
            }
        }
        for (size_t i = 0; enrollment_closed && i < MAX_TASKS + 2; ++i) {
            int status; pid_t got = waitpid(-1, &status, WNOHANG);
            if (got > 0) {
                if (got == controller_pid) { controller_exited = true; controller_status = status; }
                else if (load(&r->shared->enrolled) && got == r->shared->bootstrap && !got_exit) {
                    unsigned char exit_body[8] = {0};
                    exit_body[3] = WIFSIGNALED(status) ? 1 : 0;
                    exit_body[7] = (unsigned char)(WIFSIGNALED(status) ? WTERMSIG(status) : WIFEXITED(status) ? WEXITSTATUS(status) : 127);
                    if (*output_ok) (void)output_frame(output_ok, AJIU_EXIT, exit_body, sizeof(exit_body));
                    got_exit = true;
                }
                continue;
            }
            if (got < 0 && errno == ECHILD) no_children = true;
            else if (got < 0 && errno != EINTR && !error) error = errno;
            break;
        }
        if (controller_exited && !stopped) { stopped = true; stop_at = now; }
        if (stopped && controller_exited && no_children && !load(&r->shared->unsafe)) {
            if (members_guard(r) < 0) { if (!error) error = errno; }
            else if (populated(r) == 0 && r->registry.count == 0) break;
        }
        if (stopped && now - stop_at >= CLEANUP_WAIT + 1500) break;
        struct pollfd pf[5] = { { !stopped && input_ok ? STDIN_FILENO : -1, POLLIN | POLLHUP, 0 },
            { !stopped ? control : -1, POLLIN | POLLHUP, 0 }, { !stopped ? r->app_fd : -1, POLLIN, 0 },
            { controller_ok ? sockets[0] : -1, POLLIN, 0 }, { controller_exited ? -1 : controllerfd, POLLIN, 0 } };
        if (poll(pf, 5, 20) < 0 && errno != EINTR) { if (!error) error = errno; if (!stopped) { stopped = true; stop_at = now; } }
    }
    if (!controller_exited || !no_children || load(&r->shared->unsafe) || members_guard(r) < 0 ||
        populated(r) != 0 || r->registry.count || !load(&r->shared->namespace_closed) ||
        !WIFEXITED(controller_status) || WEXITSTATUS(controller_status)) {
        store(&r->shared->unsafe, 1);
        if (!error) error = ESTALE;
    }
    if (error) public_error(output_ok, "runtime_failed", "supervision", error);
    if (controllerfd >= 0) close(controllerfd);
    if (sockets[0] >= 0) close(sockets[0]);
    errno = error;
    return error ? -1 : 0;
setup_failed:
    error = errno ? errno : EIO;
    if (parentfd >= 0) close(parentfd);
    if (sockets[0] >= 0) close(sockets[0]);
    if (sockets[1] >= 0) close(sockets[1]);
    errno = error;
    return -1;
}

int main(int argc, char **argv)
{
    if (argc == 2 && !strcmp(argv[1], "--help")) {
        static const char help[] = "--help | --runtime; AJCRv1 one-shot frozen LAUNCH; APK static arm64 helper only\n";
        return write(STDOUT_FILENO, help, sizeof(help) - 1) == (ssize_t)sizeof(help) - 1 ? 0 : 3;
    }
    if (argc != 2 || strcmp(argv[1], "--runtime")) {
        static const char invalid[] = "ajiu_runtime: invalid_arguments\n";
        ssize_t diagnostic_written = write(STDERR_FILENO, invalid, sizeof(invalid) - 1);
        (void)diagnostic_written;
        return 2;
    }
    bool output_ok = true;
    if (signals(false) < 0 || nonblock(STDIN_FILENO) < 0 || nonblock(STDOUT_FILENO) < 0 || nonblock(STDERR_FILENO) < 0) return 3;
    (void)umask(0077);
    struct runtime r = { .native_dir = -1, .worker_fd = -1, .app_fd = -1 };
    for (int i = 0; i < ANCHORS; ++i) r.a[i].fd = -1;
    for (size_t i = 0; i < AJIU_LAUNCH_MAX_MOUNTS; ++i) r.source[i] = -1;
    if (preflight(&r) < 0) {
        public_error(&output_ok, "capability_rejected", stage, errno);
        unsigned char closed = 0;
        (void)output_frame(&output_ok, AJIU_CLEANUP, &closed, 1);
        return 3;
    }
    int keep[] = { r.native_dir };
    if (close_except(keep, 1) < 0) { public_error(&output_ok, "descriptor_rejected", "inherited_fds", errno); return 3; }
    unsigned char *body = malloc(AJIU_MAX_FRAME_BYTES);
    if (!body) { public_error(&output_ok, "allocation_failed", "launch_buffer", ENOMEM); return 3; }
    struct ajiu_launch launch;
    struct ajiu_launch_session session;
    struct ajiu_frame_header header;
    ajiu_launch_session_init(&session);
    int rc = ajiu_read_frame(STDIN_FILENO, &header, body, AJIU_MAX_FRAME_BYTES, 10000);
    if (rc != 1 || ajiu_launch_session_accept(&session, &header, body, &launch) < 0) {
        public_error(&output_ok, "launch_rejected", "frozen_payload", rc == 0 ? EPIPE : errno);
        unsigned char closed = 0;
        (void)output_frame(&output_ok, AJIU_CLEANUP, &closed, 1);
        free(body); return 3;
    }
    r.launch = &launch;
    (void)snprintf(r.base_name, sizeof(r.base_name), "ajiu-chroot-rootfs-%s", launch.instance_id);
    (void)snprintf(r.group_name, sizeof(r.group_name), "ajiu_task_%s", launch.launch_id);
    r.shared = mmap(NULL, sizeof(*r.shared), PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (r.shared == MAP_FAILED) { public_error(&output_ok, "allocation_failed", "control_state", errno); ajiu_launch_free(&launch); free(body); return 3; }
    bool supervised = false;
    int control = -1;
    int result = 0;
    if (!strcmp(launch.argv[0], "/run/ajiu-file-worker")) {
        stage = "file_worker_resource";
        if (launch.argc != 1 || launch.io_mode != AJIU_IO_STDIO) { errno = EPROTO; result = -1; }
        if (!result) {
            r.worker_fd = open_at(r.native_dir, "libajiu_file_worker.so", O_RDONLY, RESOLVE_BENEATH);
            if (r.worker_fd < 0 || protected_static_elf(r.worker_fd) < 0) result = -1;
        }
    }
    if (!result) {
        stage = "app_control_authentication";
        control = connect_control(&r);
        if (control < 0) result = -1;
    }
    if (!result) result = prepare(&r);
    if (!result) { supervised = true; result = supervisor(&r, &session, &output_ok, body, control); }
    if (control >= 0) close(control);
    if (result < 0 && !supervised) public_error(&output_ok, "prepare_failed", stage, errno);
    bool unknown = load(&r.shared->unsafe) || remove_resources(&r) < 0;
    if (unknown) public_error(&output_ok, "cleanup_unknown", "resource_proof", errno);
    unsigned char cleanup = unknown ? 1 : 0;
    (void)output_frame(&output_ok, AJIU_CLEANUP, &cleanup, 1);
    close_runtime(&r);
    (void)munmap(r.shared, sizeof(*r.shared));
    ajiu_launch_free(&launch); free(body);
    return unknown ? 5 : result < 0 ? 3 : 0;
}
