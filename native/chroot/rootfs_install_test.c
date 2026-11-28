#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/openat2.h>
#include <linux/stat.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/vfs.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

struct test_node {
    dev_t dev;
    ino_t ino;
    uid_t uid;
    gid_t gid;
    uint64_t mount_id;
    nlink_t link_override;
    bool proc_ref;
    int ref_error;
    struct stat ref, next_ref;
    bool replace_ref_on_follow;
    unsigned int ref_reads;
    size_t stat_reads, stat_fault_at;
    int stat_error, directory_error, open_error;
    bool stat_read_error, change_stat_start, stat_start_changed, malform_stat, stat_malformed;
};
struct test_seam {
    int root, proc, boot, base, local, rootfs_root, views;
    struct test_node nodes[8192];
    size_t count, root_open_calls, openat2_calls, statx_calls, unlink_calls, rejected_calls;
    uid_t uid, euid;
    gid_t gid, egid;
    bool replace_payload_on_named_stat;
    bool named_mount_changed;
    dev_t named_dev; ino_t named_ino;
    uint64_t named_mount, named_fd_mounts[1024];
    size_t named_mount_reads, sync_calls, receipt_syncs, base_syncs, local_syncs;
    int fail_sync_call;
    bool fail_receipt_write, unsafe_unlink, enforce_receipt, replace_view_on_reopen, fail_local_sync;
    unsigned int view_reopens, base_change_after_manifest;
    bool proc_wrong_fs, boot_parent_submount;
    size_t boot_opens, boot_mount_rejections;
    const char *fail_unlink;
    size_t process_scans, reference_checks, lock_calls;
    int64_t clock_offset;
    bool no_ptrace, deny_proc_task, churn_proc, proc_submount, change_base_on_scan;
    bool lock_busy, unsafe_lock;
    bool fixed_clock;
    int64_t clock_origin, scan_clock_step;
    size_t proc_padding, padding_left, padding_emitted, stat_faults, directory_faults;
    DIR *padding_stream;
};
static struct test_seam seam;
static int checks;
static struct {
    const unsigned char *bytes;
    size_t size, offset, polls, reads;
    bool invalid;
} archive_input;
static ssize_t synthetic_read(int fd, void *bytes, size_t size);
static int synthetic_poll(struct pollfd *fds, nfds_t count, int timeout);
static int synthetic_open(const char *path, int flags, ...);
static int synthetic_openat(int parent, const char *name, int flags, ...);
static int synthetic_fstat(int fd, struct stat *st);
static int synthetic_fstatat(int fd, const char *path, struct stat *st, int flags);
static long synthetic_syscall(long number, ...);
static int synthetic_unlinkat(int fd, const char *path, int flags);
static int synthetic_fstatfs(int fd, struct statfs *fs);
static int synthetic_fsync(int fd);
static int synthetic_fchown(int fd, uid_t uid, gid_t gid);
static ssize_t synthetic_write(int fd, const void *bytes, size_t size);
static ssize_t synthetic_readlinkat(int fd, const char *path, char *bytes, size_t size);
static int synthetic_flock(int fd, int operation);
static int synthetic_clock_gettime(clockid_t clock, struct timespec *ts);
static DIR *synthetic_fdopendir(int fd);
static struct dirent *synthetic_readdir(DIR *dir);
static ssize_t synthetic_pread(int fd, void *bytes, size_t size, off_t offset);
static pid_t synthetic_getpid(void);
static uid_t synthetic_getuid(void) { return seam.uid; }
static uid_t synthetic_geteuid(void) { return seam.euid; }
static gid_t synthetic_getgid(void) { return seam.gid; }
static gid_t synthetic_getegid(void) { return seam.egid; }

/* Only production calls use these seams. Focused CLI tests fork and return the
 * actual main exit status; absolute paths/privileged queries stay synthetic. */
#define main rootfs_install_production_main_not_called
#define open synthetic_open
#define openat synthetic_openat
#define fstat synthetic_fstat
#define fstatat synthetic_fstatat
#define syscall synthetic_syscall
#define unlinkat synthetic_unlinkat
#define getuid synthetic_getuid
#define geteuid synthetic_geteuid
#define getgid synthetic_getgid
#define getegid synthetic_getegid
#define read synthetic_read
#define poll synthetic_poll
#define fstatfs synthetic_fstatfs
#define fsync synthetic_fsync
#define fchown synthetic_fchown
#define write synthetic_write
#define readlinkat synthetic_readlinkat
#define flock synthetic_flock
#define clock_gettime synthetic_clock_gettime
#define getpid synthetic_getpid
#define fdopendir synthetic_fdopendir
#define readdir synthetic_readdir
#define pread synthetic_pread
#include "rootfs_install.c"
#undef main
#undef open
#undef openat
#undef fstat
#undef fstatat
#undef syscall
#undef unlinkat
#undef getuid
#undef geteuid
#undef getgid
#undef getegid
#undef read
#undef poll
#undef fstatfs
#undef fsync
#undef fchown
#undef write
#undef readlinkat
#undef flock
#undef clock_gettime
#undef getpid
#undef fdopendir
#undef readdir
#undef pread

#define CHECK(condition) do { \
    ++checks; \
    if (!(condition)) { \
        fprintf(stderr, "Installer check %s line %d: %s (errno=%d)\n", test_name, __LINE__, #condition, errno); \
        result = -1; goto done; \
    } \
} while (0)
#define CURRENT_MOUNT UINT64_C(9001)
#define OLD_MOUNT UINT64_C(41)
#define PROC_MOUNT UINT64_C(9017)

static ssize_t synthetic_read(int fd, void *bytes, size_t size)
{
    if (!archive_input.bytes) return read(fd, bytes, size);
    if (fd != STDIN_FILENO || archive_input.offset > archive_input.size) {
        archive_input.invalid = true; errno = EBADF; return -1;
    }
    ++archive_input.reads;
    size_t left = archive_input.size - archive_input.offset;
    if (size > left) size = left;
    if (size) memcpy(bytes, archive_input.bytes + archive_input.offset, size);
    archive_input.offset += size;
    return (ssize_t)size;
}

static int synthetic_poll(struct pollfd *fds, nfds_t count, int timeout)
{
    if (!fds && !count && timeout == 20) { seam.clock_offset += 1000; return 0; }
    if (!archive_input.bytes) return poll(fds, count, timeout);
    if (!fds || count != 1 || fds[0].fd != STDIN_FILENO ||
        fds[0].events != (POLLIN | POLLHUP) || timeout <= 0) {
        archive_input.invalid = true; errno = EINVAL; return -1;
    }
    ++archive_input.polls;
    fds[0].revents = archive_input.offset < archive_input.size ? POLLIN : POLLHUP;
    return 1;
}

static struct test_node *node_for(const struct stat *st)
{
    for (size_t i = 0; i < seam.count; ++i)
        if (seam.nodes[i].dev == st->st_dev && seam.nodes[i].ino == st->st_ino) return &seam.nodes[i];
    return NULL;
}

static struct test_node *node_fd(int fd)
{
    struct stat st;
    return fstat(fd, &st) == 0 ? node_for(&st) : NULL;
}

static int remember(const struct stat *st)
{
    if (node_for(st)) return 0;
    if (seam.count == sizeof(seam.nodes) / sizeof(*seam.nodes)) { errno = EOVERFLOW; return -1; }
    seam.nodes[seam.count++] = (struct test_node){ .dev = st->st_dev, .ino = st->st_ino, .mount_id = CURRENT_MOUNT };
    return 0;
}

static int synthetic_open(const char *path, int flags, ...)
{
    if (strcmp(path, "/") || flags != DIR_FLAGS || seam.root < 0) {
        ++seam.rejected_calls; errno = EPERM; return -1;
    }
    ++seam.root_open_calls;
    int fd = fcntl(seam.root, F_DUPFD_CLOEXEC, 3);
    if (fd >= 0 && (size_t)fd < sizeof(seam.named_fd_mounts) / sizeof(*seam.named_fd_mounts)) seam.named_fd_mounts[fd] = 0;
    return fd;
}

static void remember_named_open(int parent, const char *name, int fd)
{
    if (fd < 0 || (size_t)fd >= sizeof(seam.named_fd_mounts) / sizeof(*seam.named_fd_mounts)) return;
    seam.named_fd_mounts[fd] = 0;
    if (!seam.named_mount_changed) return;
    struct stat st;
    if (fstat(fd, &st) < 0) return;
    if (strcmp(name, ".") && st.st_dev == seam.named_dev && st.st_ino == seam.named_ino)
        seam.named_fd_mounts[fd] = seam.named_mount;
    else if (parent >= 0 && (size_t)parent < sizeof(seam.named_fd_mounts) / sizeof(*seam.named_fd_mounts))
        seam.named_fd_mounts[fd] = seam.named_fd_mounts[parent];
}

static int synthetic_openat(int parent, const char *name, int flags, ...)
{
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list args; va_start(args, flags); mode = (mode_t)va_arg(args, int); va_end(args); }
    struct test_node *parent_node = node_fd(parent);
    if (parent_node && parent_node->mount_id == PROC_MOUNT && !(flags & O_NOFOLLOW)) {
        struct stat link;
        if (fstatat(parent, name, &link, AT_SYMLINK_NOFOLLOW) < 0) return -1;
        struct test_node *target = node_for(&link);
        if (!target || !target->proc_ref) { errno = EPERM; return -1; }
        ++seam.reference_checks;
        if (target->ref_error) { errno = target->ref_error; return -1; }
        int protected[] = { seam.base, seam.rootfs_root, seam.views };
        for (size_t i = 0; i < sizeof(protected) / sizeof(*protected); ++i) {
            struct stat st;
            if (protected[i] >= 0 && fstat(protected[i], &st) == 0 && same_object(&target->ref, &st)) {
                errno = EBUSY; return -1;
            }
        }
        for (int candidate = 3; candidate < 1024; ++candidate) {
            struct stat st;
            if (fcntl(candidate, F_GETFD) < 0 || fstat(candidate, &st) < 0 || !same_object(&st, &target->ref)) continue;
            return fcntl(candidate, F_DUPFD_CLOEXEC, 3);
        }
        errno = ENOENT; return -1;
    }
    if (seam.replace_view_on_reopen && (flags & O_DIRECTORY) && !strcmp(name, "abcdef0123456789abcdef01") &&
        ++seam.view_reopens == 2) {
        if (renameat(parent, name, seam.root, "saved-view") < 0 || mkdirat(parent, name, 0700) < 0) return -1;
        struct stat st;
        if (fstatat(parent, name, &st, AT_SYMLINK_NOFOLLOW) < 0 || remember(&st) < 0) return -1;
    }
    int fd = openat(parent, name, flags, mode);
    if (fd >= 0 && (flags & O_CREAT) && !strcmp(name, REMOVE_RECEIPT)) {
        struct stat st;
        if (fstat(fd, &st) < 0 || remember(&st) < 0) { close(fd); return -1; }
    }
    remember_named_open(parent, name, fd);
    return fd;
}

static int synthetic_fstat(int fd, struct stat *st)
{
    if (fstat(fd, st) < 0) return -1;
    struct test_node *node = node_for(st);
    if (!node) { ++seam.rejected_calls; errno = EPERM; return -1; }
    st->st_uid = node->uid; st->st_gid = node->gid;
    if (node->link_override) st->st_nlink = node->link_override;
    return 0;
}

static int synthetic_fstatat(int fd, const char *path, struct stat *st, int flags)
{
    if (!node_fd(fd)) { ++seam.rejected_calls; errno = EPERM; return -1; }
    if (seam.replace_payload_on_named_stat && !strcmp(path, "payload")) {
        seam.replace_payload_on_named_stat = false;
        if (renameat(fd, "payload", fd, "saved-payload") < 0) return -1;
        int replacement = openat(fd, "payload", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        struct stat created;
        if (replacement < 0) return -1;
        int rc = write(replacement, "race-foreign", 12) == 12 && fstat(replacement, &created) == 0 && remember(&created) == 0 ? 0 : -1;
        close(replacement);
        if (rc < 0) return -1;
    }
    if (!flags && node_fd(fd)->mount_id == PROC_MOUNT) {
        struct stat link;
        if (fstatat(fd, path, &link, AT_SYMLINK_NOFOLLOW) < 0) return -1;
        struct test_node *ref = node_for(&link);
        if (!ref || !ref->proc_ref) { ++seam.rejected_calls; errno = EPERM; return -1; }
        ++seam.reference_checks;
        if (ref->ref_error) { errno = ref->ref_error; return -1; }
        *st = ref->ref;
        errno = ENOENT;
        if (ref->replace_ref_on_follow && ++ref->ref_reads == 1) ref->ref = ref->next_ref;
        return 0;
    }
    if (fstatat(fd, path, st, flags) < 0) return -1;
    struct test_node *node = node_for(st);
    if (!node) { ++seam.rejected_calls; errno = EPERM; return -1; }
    st->st_uid = node->uid; st->st_gid = node->gid;
    if (node->link_override) st->st_nlink = node->link_override;
    return 0;
}

/* Explicit synthetic IN_ROOT: canonical relative components, real openat with O_NOFOLLOW. */
static int synthetic_openat2(int fd, const char *path, const struct open_how *how, size_t size)
{
    ++seam.openat2_calls;
    const uint64_t resolve = RESOLVE_IN_ROOT | RESOLVE_NO_MAGICLINKS | RESOLVE_NO_SYMLINKS;
    if (seam.proc >= 0 && node_fd(fd) == node_fd(seam.proc) && !strcmp(path, "sys/kernel/random/boot_id")) {
        const uint64_t proc_resolve = RESOLVE_BENEATH | RESOLVE_NO_MAGICLINKS | RESOLVE_NO_SYMLINKS | RESOLVE_NO_XDEV;
        if (strcmp(path, "sys/kernel/random/boot_id") || size != sizeof(*how) || how->resolve != proc_resolve ||
            how->flags != (O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW) || how->mode || seam.boot < 0) {
            ++seam.rejected_calls; errno = EINVAL; return -1;
        }
        ++seam.boot_opens;
        /* Both sources are modeled procfs; a bind to random/uuid still crosses
         * a mount boundary. The parent's proc mount differs from / on purpose. */
        if (seam.boot_parent_submount || node_fd(seam.boot)->mount_id != node_fd(seam.proc)->mount_id) {
            ++seam.boot_mount_rejections; errno = EXDEV; return -1;
        }
        int boot = fcntl(seam.boot, F_DUPFD_CLOEXEC, 3);
        remember_named_open(fd, path, boot);
        return boot;
    }
    if (node_fd(fd) && node_fd(fd)->mount_id == PROC_MOUNT) {
        const uint64_t proc_resolve = RESOLVE_BENEATH | RESOLVE_NO_MAGICLINKS | RESOLVE_NO_SYMLINKS | RESOLVE_NO_XDEV;
        if (!path || strchr(path, '/') || size != sizeof(*how) || how->resolve != proc_resolve || how->mode ||
            !(how->flags & O_NOFOLLOW) || !(how->flags & O_CLOEXEC)) {
            ++seam.rejected_calls; errno = EINVAL; return -1;
        }
        if (!strcmp(path, ".") && node_fd(fd) == node_fd(seam.proc)) {
            ++seam.process_scans;
            seam.clock_offset += seam.scan_clock_step;
            if (seam.change_base_on_scan) node_fd(seam.base)->uid = 10000;
        }
        if (seam.churn_proc && node_fd(fd) == node_fd(seam.proc) && !strcmp(path, "5151") && seam.process_scans == 1) {
            errno = ENOENT; return -1;
        }
        if (seam.deny_proc_task && !strcmp(path, "task")) { errno = EACCES; return -1; }
        if (seam.proc_submount && !strcmp(path, "task")) { errno = EXDEV; return -1; }
        struct test_node *parent = node_fd(fd);
        bool stat_fault = !strcmp(path, "stat") && ++parent->stat_reads >= parent->stat_fault_at && parent->stat_fault_at;
        if (stat_fault && !parent->stat_read_error && !parent->change_stat_start && !parent->malform_stat) {
            ++seam.stat_faults; errno = parent->stat_error; return -1;
        }
        int result = openat(fd, path, (int)how->flags);
        if (result >= 0 && (!node_fd(result) || node_fd(result)->mount_id != PROC_MOUNT)) {
            close(result); errno = EXDEV; return -1;
        }
        if (result >= 0 && node_fd(result)->open_error) {
            int saved = node_fd(result)->open_error; close(result); errno = saved; return -1;
        }
        if (result >= 0 && stat_fault) {
            node_fd(result)->stat_error = parent->stat_error;
            node_fd(result)->stat_start_changed = parent->change_stat_start;
            node_fd(result)->stat_malformed = parent->malform_stat;
        }
        remember_named_open(fd, path, result);
        return result;
    }
    const uint64_t allowed = O_PATH | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW;
    if (!node_fd(fd) || !path || strlen(path) > 4096 || !safe_path(path) || size != sizeof(*how) ||
        how->resolve != resolve || (how->flags & ~allowed) || how->mode) {
        ++seam.rejected_calls; errno = EINVAL; return -1;
    }
    char copy[4097];
    memcpy(copy, path, strlen(path) + 1);
    int current = fcntl(fd, F_DUPFD_CLOEXEC, 3);
    if (current < 0) return -1;
    if ((size_t)current < sizeof(seam.named_fd_mounts) / sizeof(*seam.named_fd_mounts)) {
        seam.named_fd_mounts[current] = (size_t)fd < sizeof(seam.named_fd_mounts) / sizeof(*seam.named_fd_mounts) ? seam.named_fd_mounts[fd] : 0;
    }
    char *save;
    for (char *part = strtok_r(copy, "/", &save); part; part = strtok_r(NULL, "/", &save)) {
        bool last = !save || !*save;
        int next = openat(current, part, last ? (int)how->flags : DIR_FLAGS);
        remember_named_open(current, part, next);
        int saved = errno;
        close(current);
        if (next < 0) { errno = saved; return -1; }
        if (!node_fd(next)) { close(next); ++seam.rejected_calls; errno = EPERM; return -1; }
        current = next;
    }
    return current;
}

static long synthetic_syscall(long number, ...)
{
    va_list args;
    va_start(args, number);
    long result = -1;
    if (number == SYS_capget) {
        struct __user_cap_header_struct *header = va_arg(args, struct __user_cap_header_struct *);
        struct __user_cap_data_struct *caps = va_arg(args, struct __user_cap_data_struct *);
        if (header->version != _LINUX_CAPABILITY_VERSION_3 || header->pid) ++seam.rejected_calls;
        else { memset(caps, 0, sizeof(*caps) * 2); caps[0].effective = seam.no_ptrace ? 0 : (UINT32_C(1) << CAP_SYS_PTRACE); result = 0; }
    } else if (number == SYS_openat2) {
        int fd = va_arg(args, int);
        const char *path = va_arg(args, const char *);
        const struct open_how *how = va_arg(args, const struct open_how *);
        size_t size = va_arg(args, size_t);
        result = synthetic_openat2(fd, path, how, size);
    } else if (number == SYS_statx) {
        int fd = va_arg(args, int);
        const char *path = va_arg(args, const char *);
        int flags = va_arg(args, int);
        unsigned int mask = va_arg(args, unsigned int);
        struct statx *sx = va_arg(args, struct statx *);
        struct test_node *node = node_fd(fd);
        ++seam.statx_calls;
        if (!node || strcmp(path, "") || flags != (AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW) || mask != STATX_MNT_ID) {
            ++seam.rejected_calls; errno = EINVAL;
        } else {
            memset(sx, 0, sizeof(*sx));
            sx->stx_mask = STATX_MNT_ID; sx->stx_mnt_id = node->mount_id;
            if (fd >= 0 && (size_t)fd < sizeof(seam.named_fd_mounts) / sizeof(*seam.named_fd_mounts) && seam.named_fd_mounts[fd]) {
                sx->stx_mnt_id = seam.named_fd_mounts[fd]; ++seam.named_mount_reads;
            }
            result = 0;
        }
    } else { ++seam.rejected_calls; errno = ENOSYS; }
    va_end(args);
    return result;
}

static int synthetic_unlinkat(int fd, const char *path, int flags)
{
    struct stat parent;
    if (fstat(fd, &parent) < 0 || !S_ISDIR(parent.st_mode) || !node_for(&parent) ||
        !safe_path(path) || strchr(path, '/') || (flags != 0 && flags != AT_REMOVEDIR)) {
        ++seam.rejected_calls; errno = EPERM; return -1;
    }
    ++seam.unlink_calls;
    if (seam.enforce_receipt && strcmp(path, REMOVE_RECEIPT) && strncmp(path, PREFIX, strlen(PREFIX))) {
        struct stat receipt;
        if (fstatat(seam.base, REMOVE_RECEIPT, &receipt, AT_SYMLINK_NOFOLLOW) < 0 ||
            !S_ISREG(receipt.st_mode) || !seam.receipt_syncs || !seam.base_syncs) seam.unsafe_unlink = true;
    }
    if (seam.fail_unlink && !strcmp(path, seam.fail_unlink)) { errno = EIO; return -1; }
    if (seam.named_mount_changed) {
        struct stat named;
        if (fstatat(fd, path, &named, AT_SYMLINK_NOFOLLOW) == 0 &&
            named.st_dev == seam.named_dev && named.st_ino == seam.named_ino) { errno = EBUSY; return -1; }
    }
    int rc = unlinkat(fd, path, flags);
    if (!rc && seam.base_change_after_manifest && !strcmp(path, "manifest")) {
        struct test_node *base = node_fd(seam.base);
        if (!base) { errno = ESTALE; return -1; }
        if (seam.base_change_after_manifest == 1) base->uid = 10000;
        else if (seam.base_change_after_manifest == 2) base->gid = 10000;
        else if (fchmod(seam.base, 0755) < 0) return -1;
    }
    return rc;
}

static int synthetic_fstatfs(int fd, struct statfs *fs)
{
    struct test_node *held = node_fd(fd);
    if (!held || seam.proc < 0 || (held != node_fd(seam.proc) && held != node_fd(seam.boot))) {
        ++seam.rejected_calls; errno = EPERM; return -1;
    }
    memset(fs, 0, sizeof(*fs));
    fs->f_type = held == node_fd(seam.proc) && seam.proc_wrong_fs ? TMPFS_MAGIC : PROC_SUPER_MAGIC;
    return 0;
}

static int synthetic_fsync(int fd)
{
    ++seam.sync_calls;
    if ((int)seam.sync_calls == seam.fail_sync_call) { errno = EIO; return -1; }
    struct stat st, receipt;
    if (fstat(fd, &st) < 0) return -1;
    if (seam.local >= 0 && fstat(seam.local, &receipt) == 0 && same_object(&st, &receipt)) {
        ++seam.local_syncs;
        if (seam.fail_local_sync) { errno = EIO; return -1; }
    }
    if (seam.base >= 0 && fstatat(seam.base, REMOVE_RECEIPT, &receipt, AT_SYMLINK_NOFOLLOW) == 0 && same_object(&st, &receipt))
        ++seam.receipt_syncs;
    if (fd == seam.base || (seam.base >= 0 && fstat(seam.base, &receipt) == 0 && same_object(&st, &receipt))) ++seam.base_syncs;
    return fsync(fd);
}

static int synthetic_fchown(int fd, uid_t uid, gid_t gid)
{
    struct test_node *node = node_fd(fd);
    if (!node || uid || gid) { ++seam.rejected_calls; errno = EPERM; return -1; }
    node->uid = uid; node->gid = gid;
    return 0;
}

static ssize_t synthetic_write(int fd, const void *bytes, size_t size)
{
    if (seam.fail_receipt_write) { errno = EIO; return -1; }
    return write(fd, bytes, size);
}

static pid_t synthetic_getpid(void) { return 4242; }

static ssize_t synthetic_readlinkat(int fd, const char *path, char *bytes, size_t size)
{
    if (!node_fd(fd) || node_fd(fd)->mount_id != PROC_MOUNT || strcmp(path, "self")) {
        ++seam.rejected_calls; errno = EPERM; return -1;
    }
    const char pid[] = "4242"; int n = (int)sizeof(pid) - 1;
    if (n <= 0 || (size_t)n > size) { errno = EOVERFLOW; return -1; }
    memcpy(bytes, pid, (size_t)n); return n;
}

static int synthetic_flock(int fd, int operation)
{
    if (!node_fd(fd) || node_fd(fd) != node_fd(seam.base) || operation != (LOCK_EX | LOCK_NB)) {
        ++seam.rejected_calls; errno = EPERM; return -1;
    }
    ++seam.lock_calls;
    if (!seam.receipt_syncs || !seam.base_syncs) seam.unsafe_lock = true;
    if (seam.lock_busy) { errno = EWOULDBLOCK; return -1; }
    return flock(fd, operation);
}

static DIR *synthetic_fdopendir(int fd)
{
    bool pad = seam.proc_padding && node_fd(fd) == node_fd(seam.proc);
    DIR *dir = fdopendir(fd);
    if (dir && pad) { seam.padding_stream = dir; seam.padding_left = seam.proc_padding; }
    return dir;
}

static struct dirent *synthetic_readdir(DIR *dir)
{
    if (dir == seam.padding_stream && seam.padding_left) {
        static struct dirent padding = { .d_name = "." };
        --seam.padding_left; ++seam.padding_emitted;
        return &padding;
    }
    struct test_node *node = node_fd(dirfd(dir));
    if (node && node->directory_error) {
        ++seam.directory_faults; errno = node->directory_error; return NULL;
    }
    return readdir(dir);
}

static ssize_t synthetic_pread(int fd, void *bytes, size_t size, off_t offset)
{
    struct test_node *node = node_fd(fd);
    if (node && node->mount_id == PROC_MOUNT && node->stat_error) {
        ++seam.stat_faults; errno = node->stat_error; return -1;
    }
    if (node && node->stat_malformed) {
        const char malformed[] = "4242 (fixture)\n";
        size_t n = sizeof(malformed) - 1;
        if (size < n) n = size;
        memcpy(bytes, malformed, n);
        return (ssize_t)n;
    }
    ssize_t n = pread(fd, bytes, size, offset);
    if (node && node->stat_start_changed && n >= 5) memcpy((char *)bytes + n - 5, "4243\n", 5);
    return n;
}

static int synthetic_clock_gettime(clockid_t clock, struct timespec *ts)
{
    if (seam.fixed_clock) {
        int64_t now = seam.clock_origin + seam.clock_offset;
        ts->tv_sec = (time_t)(now / 1000); ts->tv_nsec = (long)(now % 1000) * 1000000;
        return 0;
    }
    int rc = clock_gettime(clock, ts);
    if (!rc) {
        ts->tv_sec += seam.clock_offset / 1000;
        ts->tv_nsec += (seam.clock_offset % 1000) * 1000000;
        if (ts->tv_nsec >= 1000000000) { ++ts->tv_sec; ts->tv_nsec -= 1000000000; }
    }
    return rc;
}

struct fixture {
    char temp[4096];
    int sandbox, data, local, base, root, views, proc_process, proc_thread, churn_process, proc_self;
    struct stat base_stat, root_stat, views_stat, entries[2];
    uint64_t header[6], records[2][5];
    struct install loaded;
};

static int mkdir_open(int parent, const char *name, mode_t mode)
{
    if (mkdirat(parent, name, mode) < 0) return -1;
    int fd = openat(parent, name, DIR_FLAGS);
    struct stat st;
    if (fd < 0) return -1;
    if (fchmod(fd, mode) < 0 || fstat(fd, &st) < 0 || remember(&st) < 0) { int saved = errno; close(fd); errno = saved; return -1; }
    return fd;
}

static int make_file(int parent, const char *name, const char *content, struct stat *st)
{
    int fd = openat(parent, name, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    size_t size = strlen(content);
    int rc = write(fd, content, size) == (ssize_t)size && fstat(fd, st) == 0 && remember(st) == 0 ? 0 : -1;
    int saved = errno; close(fd); errno = saved; return rc;
}

static int write_all(int fd, const void *bytes, size_t size)
{
    const unsigned char *p = bytes;
    while (size) {
        ssize_t n = write(fd, p, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { if (!n) errno = EIO; return -1; }
        p += n; size -= (size_t)n;
    }
    return 0;
}

static int manifest_write(struct fixture *f)
{
    int fd = openat(f->base, "manifest", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    int rc = write_all(fd, f->header, sizeof(f->header));
    const char *const paths[] = { "etc", "etc/payload" };
    for (size_t i = 0; !rc && i < 2; ++i)
        rc = write_all(fd, f->records[i], sizeof(f->records[i])) < 0 ||
             write_all(fd, paths[i], strlen(paths[i])) < 0 ? -1 : 0;
    struct stat st;
    if (!rc && (fchmod(fd, 0400) < 0 || fstat(fd, &st) < 0 || remember(&st) < 0)) rc = -1;
    int saved = errno; close(fd); errno = saved; return rc;
}

static int fixture_open(struct fixture *f)
{
    memset(f, 0, sizeof(*f));
    f->sandbox = f->data = f->local = f->base = f->root = f->views = f->proc_process = f->proc_thread =
        f->churn_process = f->proc_self = -1;
    f->loaded = (struct install){ .local = -1, .base = -1, .root = -1, .views = -1, .manifest = -1, .receipt = -1 };
    memset(&seam, 0, sizeof(seam)); seam.root = seam.proc = seam.boot = seam.base = seam.local = -1;
    seam.rootfs_root = seam.views = -1;
    const char *temp_parent = getenv("TMPDIR");
    if (!temp_parent || !*temp_parent) temp_parent = "/tmp";
    int length = snprintf(f->temp, sizeof(f->temp), "%s/ajiu-rootfs-install-test-XXXXXX", temp_parent);
    if (length < 0 || (size_t)length >= sizeof(f->temp)) { *f->temp = 0; errno = ENAMETOOLONG; return -1; }
    if (!mkdtemp(f->temp)) return -1;
    f->sandbox = open(f->temp, DIR_FLAGS);
    struct stat st;
    if (f->sandbox < 0 || fstat(f->sandbox, &st) < 0 || remember(&st) < 0) return -1;
    seam.root = f->sandbox;
    f->data = mkdir_open(f->sandbox, "data", 0700);
    f->local = f->data < 0 ? -1 : mkdir_open(f->data, "local", 0700);
    seam.local = f->local;
    (void)snprintf(f->loaded.name, sizeof(f->loaded.name), "%s%s", PREFIX, "0123456789abcdef01234567");
    f->base = f->local < 0 ? -1 : mkdir_open(f->local, f->loaded.name, 0700);
    seam.base = f->base;
    f->root = f->base < 0 ? -1 : mkdir_open(f->base, "root", 0755);
    f->views = f->base < 0 ? -1 : mkdir_open(f->base, "views", 0700);
    seam.rootfs_root = f->root; seam.views = f->views;
    if (f->root < 0 || f->views < 0 || fstat(f->base, &f->base_stat) < 0 ||
        fstat(f->root, &f->root_stat) < 0 || fstat(f->views, &f->views_stat) < 0) return -1;
    int etc = mkdir_open(f->root, "etc", 0755);
    if (etc < 0) return -1;
    int rc = fstat(etc, &f->entries[0]) < 0 || make_file(etc, "payload", "owned fixture payload", &f->entries[1]) < 0 ? -1 : 0;
    close(etc);
    if (rc < 0) return -1;
    f->header[0] = UINT64_C(0x414a494d00000001);
    f->header[1] = f->root_stat.st_dev; f->header[2] = f->root_stat.st_ino;
    f->header[3] = f->views_stat.st_dev; f->header[4] = f->views_stat.st_ino; f->header[5] = OLD_MOUNT;
    const char *const paths[] = { "etc", "etc/payload" };
    for (size_t i = 0; i < 2; ++i) {
        f->records[i][0] = i ? 2 : 1; f->records[i][1] = strlen(paths[i]);
        f->records[i][2] = f->entries[i].st_dev; f->records[i][3] = f->entries[i].st_ino; f->records[i][4] = OLD_MOUNT;
    }
    f->loaded.entries = calloc(MAX_ENTRIES, sizeof(*f->loaded.entries));
    f->loaded.index = calloc(HASH_SIZE, sizeof(*f->loaded.index));
    if (!f->loaded.entries || !f->loaded.index) return -1;
    timed_out = 0; deadline = monotonic_ms() + 5000;
    return 0;
}

static int remove_owned_contents(int fd, unsigned int depth)
{
    if (depth > 16) { errno = EOVERFLOW; return -1; }
    int copy = openat(fd, ".", DIR_FLAGS);
    if (copy < 0) return -1;
    DIR *dir = fdopendir(copy);
    if (!dir) { close(copy); return -1; }
    int error = 0;
    for (;;) {
        errno = 0; struct dirent *entry = readdir(dir);
        if (!entry) { error = errno; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        struct stat st;
        if (fstatat(fd, entry->d_name, &st, AT_SYMLINK_NOFOLLOW) < 0) { error = errno; break; }
        if (S_ISDIR(st.st_mode)) {
            int child = openat(fd, entry->d_name, DIR_FLAGS);
            if (child < 0) { error = errno; break; }
            int rc = remove_owned_contents(child, depth + 1), saved = errno;
            close(child);
            if (rc < 0) { error = saved; break; }
        }
        if (unlinkat(fd, entry->d_name, S_ISDIR(st.st_mode) ? AT_REMOVEDIR : 0) < 0) { error = errno; break; }
    }
    if (closedir(dir) < 0 && !error) error = errno;
    if (error) { errno = error; return -1; }
    return 0;
}

static int fixture_close(struct fixture *f)
{
    for (uint32_t i = 0; i < f->loaded.count; ++i) free(f->loaded.entries[i].path);
    free(f->loaded.entries); free(f->loaded.index);
    int fds[] = { f->loaded.local, f->loaded.base, f->loaded.root, f->loaded.views, f->loaded.manifest, f->loaded.receipt,
                  f->data, f->local, f->base, f->root, f->views, f->proc_process, f->proc_thread,
                  f->churn_process, f->proc_self, seam.boot, seam.proc };
    for (size_t i = 0; i < sizeof(fds) / sizeof(*fds); ++i) if (fds[i] >= 0) close(fds[i]);
    int rc = 0;
    if (f->sandbox >= 0) {
        struct stat path, held;
        if (lstat(f->temp, &path) < 0 || fstat(f->sandbox, &held) < 0 || !same_object(&path, &held) ||
            !S_ISDIR(path.st_mode) || remove_owned_contents(f->sandbox, 0) < 0) rc = -1;
        close(f->sandbox);
        if (!rc && rmdir(f->temp) < 0) rc = -1;
    } else if (*f->temp && rmdir(f->temp) < 0 && errno != ENOENT) rc = -1;
    seam.root = seam.proc = seam.boot = seam.base = seam.local = seam.rootfs_root = seam.views = -1;
    return rc;
}

enum test_case {
    DIFFERENT_MOUNT, SAME_MOUNT, ZERO_RECORDED_MOUNT, MIXED_RECORDED_MOUNT, WRONG_RECORD_DEV,
    WRONG_ROOT_DEV, WRONG_ROOT_INO, WRONG_VIEWS_DEV, WRONG_VIEWS_INO, ROOT_SUBMOUNT,
    VIEWS_SUBMOUNT, ENTRY_SUBMOUNT, NONEMPTY_VIEWS_LOAD, NONEMPTY_VIEWS_ROLLBACK,
    FOREIGN_BASE, FOREIGN_ROOT, FOREIGN_VIEWS, FOREIGN_ENTRY, SYMLINK_PARENT,
    BAD_BASE_OWNER, BAD_MANIFEST_OWNER, BAD_MANIFEST_MODE, BAD_VIEWS_OWNER, BAD_VIEWS_MODE,
    BAD_ROOT_MODE, SYMLINK_ROOT, SYMLINK_VIEWS, NOT_ROOT, TEST_CASE_COUNT
};
static const char *const test_names[TEST_CASE_COUNT] = {
    "different_recorded_mount", "same_recorded_mount", "zero_recorded_mount", "mixed_recorded_mount", "wrong_record_dev",
    "wrong_root_dev", "wrong_root_ino", "wrong_views_dev", "wrong_views_ino", "root_actual_submount",
    "views_actual_submount", "entry_actual_submount", "nonempty_views_at_load", "nonempty_views_before_rollback",
    "foreign_base", "foreign_root", "foreign_views", "foreign_entry", "symlink_parent",
    "bad_base_owner", "bad_manifest_owner", "bad_manifest_mode", "bad_views_owner", "bad_views_mode",
    "bad_root_mode", "symlink_root", "symlink_views", "not_root"
};

static int entry_present(struct fixture *f)
{
    struct stat st;
    return fstatat(f->root, "etc/payload", &st, AT_SYMLINK_NOFOLLOW) == 0 && same_object(&st, &f->entries[1]);
}

static int run_case(enum test_case test)
{
    const char *test_name = test_names[test];
    struct fixture f;
    int result = 0, foreign_parent = -1, etc = -1;
    struct stat foreign = {0};
    CHECK(fixture_open(&f) == 0);
    switch (test) {
    case SAME_MOUNT: f.header[5] = f.records[0][4] = f.records[1][4] = CURRENT_MOUNT; break;
    case ZERO_RECORDED_MOUNT: f.header[5] = 0; break;
    case MIXED_RECORDED_MOUNT: f.records[1][4] = OLD_MOUNT + 1; break;
    case WRONG_RECORD_DEV: ++f.records[1][2]; break;
    case WRONG_ROOT_DEV: ++f.header[1]; break;
    case WRONG_ROOT_INO: ++f.header[2]; break;
    case WRONG_VIEWS_DEV: ++f.header[3]; break;
    case WRONG_VIEWS_INO: ++f.header[4]; break;
    case ROOT_SUBMOUNT: node_for(&f.root_stat)->mount_id = CURRENT_MOUNT + 1; break;
    case VIEWS_SUBMOUNT: node_for(&f.views_stat)->mount_id = CURRENT_MOUNT + 1; break;
    case ENTRY_SUBMOUNT: node_for(&f.entries[1])->mount_id = CURRENT_MOUNT + 1; break;
    case NONEMPTY_VIEWS_LOAD: CHECK(mkdirat(f.views, "active-launch", 0700) == 0); break;
    case BAD_BASE_OWNER: node_for(&f.base_stat)->uid = 10000; break;
    case BAD_VIEWS_OWNER: node_for(&f.views_stat)->uid = 10000; break;
    case BAD_VIEWS_MODE: CHECK(fchmod(f.views, 0755) == 0); break;
    case BAD_ROOT_MODE: CHECK(fchmod(f.root, 0777) == 0); break;
    case SYMLINK_ROOT:
        CHECK(renameat(f.base, "root", f.base, "saved-root") == 0);
        CHECK(symlinkat("saved-root", f.base, "root") == 0); break;
    case SYMLINK_VIEWS:
        CHECK(renameat(f.base, "views", f.base, "saved-views") == 0);
        CHECK(symlinkat("saved-views", f.base, "views") == 0); break;
    case NOT_ROOT: seam.euid = 10000; break;
    default: break;
    }
    CHECK(manifest_write(&f) == 0);
    if (test == BAD_MANIFEST_OWNER || test == BAD_MANIFEST_MODE) {
        struct stat st;
        CHECK(fstatat(f.base, "manifest", &st, AT_SYMLINK_NOFOLLOW) == 0);
        if (test == BAD_MANIFEST_OWNER) node_for(&st)->uid = 10000;
        else CHECK(fchmodat(f.base, "manifest", 0600, 0) == 0);
    }
    int loaded = load_existing(&f.loaded, f.base_stat.st_dev, f.base_stat.st_ino);
    bool load_rejected = (test >= ZERO_RECORDED_MOUNT && test <= VIEWS_SUBMOUNT) ||
        test == NONEMPTY_VIEWS_LOAD || test >= BAD_BASE_OWNER;
    if (load_rejected) {
        CHECK(loaded == -1);
        CHECK(seam.unlink_calls == 0);
        CHECK(entry_present(&f));
        CHECK(faccessat(f.base, "manifest", F_OK, AT_SYMLINK_NOFOLLOW) == 0);
        goto done;
    }
    CHECK(loaded == 0);
    CHECK(f.loaded.count == 2 && f.loaded.mount_id == CURRENT_MOUNT);
    CHECK(f.loaded.entries[0].mount_id == CURRENT_MOUNT && f.loaded.entries[1].mount_id == CURRENT_MOUNT);
    CHECK(f.loaded.entries[1].dev == f.entries[1].st_dev && f.loaded.entries[1].ino == f.entries[1].st_ino);
    CHECK(seam.root_open_calls == 1 && seam.openat2_calls > 0 && seam.statx_calls > 0);
    switch (test) {
    case NONEMPTY_VIEWS_ROLLBACK: CHECK(mkdirat(f.views, "active-launch", 0700) == 0); break;
    case FOREIGN_BASE:
        CHECK(renameat(f.local, f.loaded.name, f.local, "saved-base") == 0);
        foreign_parent = mkdir_open(f.local, f.loaded.name, 0700); CHECK(foreign_parent >= 0);
        CHECK(fstat(foreign_parent, &foreign) == 0); break;
    case FOREIGN_ROOT:
        CHECK(renameat(f.base, "root", f.base, "saved-root") == 0);
        foreign_parent = mkdir_open(f.base, "root", 0755); CHECK(foreign_parent >= 0);
        CHECK(fstat(foreign_parent, &foreign) == 0); break;
    case FOREIGN_VIEWS:
        CHECK(renameat(f.base, "views", f.base, "saved-views") == 0);
        foreign_parent = mkdir_open(f.base, "views", 0700); CHECK(foreign_parent >= 0);
        CHECK(fstat(foreign_parent, &foreign) == 0); break;
    case FOREIGN_ENTRY:
        etc = openat(f.root, "etc", DIR_FLAGS); CHECK(etc >= 0);
        CHECK(renameat(etc, "payload", etc, "saved-payload") == 0);
        CHECK(make_file(etc, "payload", "foreign must survive", &foreign) == 0); break;
    case SYMLINK_PARENT:
        CHECK(renameat(f.root, "etc", f.root, "saved-etc") == 0);
        foreign_parent = mkdir_open(f.sandbox, "foreign", 0700); CHECK(foreign_parent >= 0);
        CHECK(make_file(foreign_parent, "payload", "foreign symlink target", &foreign) == 0);
        char target[sizeof(f.temp) + sizeof("/foreign")]; CHECK(snprintf(target, sizeof(target), "%s/foreign", f.temp) > 0);
        CHECK(symlinkat(target, f.root, "etc") == 0); break;
    default: break;
    }
    int rolled_back = rollback(&f.loaded);
    if (test == DIFFERENT_MOUNT || test == SAME_MOUNT) {
        CHECK(rolled_back == 0);
        CHECK(seam.unlink_calls == 6);
        struct stat removed;
        CHECK(fstatat(f.local, f.loaded.name, &removed, AT_SYMLINK_NOFOLLOW) == -1 && errno == ENOENT);
        CHECK(seam.rejected_calls == 0);
    } else {
        CHECK(rolled_back == -1);
        CHECK(seam.unlink_calls == 0);
        if (test != SYMLINK_PARENT && test != FOREIGN_ENTRY) CHECK(entry_present(&f));
        if (test == FOREIGN_ENTRY || test == SYMLINK_PARENT) {
            struct stat kept;
            CHECK(fstatat(test == FOREIGN_ENTRY ? etc : foreign_parent, "payload", &kept, AT_SYMLINK_NOFOLLOW) == 0);
            CHECK(same_object(&kept, &foreign));
        } else if (test == FOREIGN_BASE || test == FOREIGN_ROOT || test == FOREIGN_VIEWS) {
            struct stat kept;
            int parent = test == FOREIGN_BASE ? f.local : f.base;
            const char *name = test == FOREIGN_BASE ? f.loaded.name : test == FOREIGN_ROOT ? "root" : "views";
            CHECK(fstatat(parent, name, &kept, AT_SYMLINK_NOFOLLOW) == 0 && same_object(&kept, &foreign));
        }
        CHECK(faccessat(f.base, "manifest", F_OK, AT_SYMLINK_NOFOLLOW) == 0);
    }
done:
    if (foreign_parent >= 0) close(foreign_parent);
    if (etc >= 0) close(etc);
    if (fixture_close(&f) < 0) { fprintf(stderr, "Owned temporary fixture cleanup failed: %s (errno=%d)\n", test_name, errno); result = -1; }
    return result;
}

static void archive_put32(unsigned char *bytes, uint32_t value)
{
    bytes[0] = (unsigned char)(value >> 24); bytes[1] = (unsigned char)(value >> 16);
    bytes[2] = (unsigned char)(value >> 8); bytes[3] = (unsigned char)value;
}

static int run_archive_permission_case(const char *test_name, unsigned char kind, const char *path,
                                      uint32_t mode, uint32_t uid, uint32_t gid, bool accepted)
{
    /* Feed the existing static parse_stream in validate mode. No filesystem ownership or mode is changed. */
    int result = 0;
    struct install parsed = { .local = -1, .base = -1, .root = -1, .views = -1, .manifest = -1, .validate = true };
    unsigned char stream[8192] = {0};
    size_t path_size = strlen(path), data_size = kind == 2 ? 2U : 0U;
    CHECK(path_size > 0 && path_size <= 4096 && 8 + 24 + path_size + data_size + 24 <= sizeof(stream));
    memcpy(stream, "AJIF\0\1\0\0", 8);
    unsigned char *record = stream + 8;
    record[0] = kind;
    record[2] = (unsigned char)(path_size >> 8); record[3] = (unsigned char)path_size;
    archive_put32(record + 4, mode); archive_put32(record + 8, uid); archive_put32(record + 12, gid);
    archive_put32(record + 20, (uint32_t)data_size);
    memcpy(record + 24, path, path_size);
    if (data_size) memcpy(record + 24 + path_size, "ok", data_size);
    size_t end = 8 + 24 + path_size + data_size;
    stream[end] = 255;
    parsed.entries = calloc(MAX_ENTRIES, sizeof(*parsed.entries));
    parsed.index = calloc(HASH_SIZE, sizeof(*parsed.index));
    CHECK(parsed.entries != NULL && parsed.index != NULL);
    memset(&archive_input, 0, sizeof(archive_input));
    archive_input.bytes = stream; archive_input.size = end + 24;
    timed_out = 0; deadline = monotonic_ms() + 5000;
    errno = 0;
    int rc = parse_stream(&parsed);
    CHECK(!archive_input.invalid && archive_input.polls > 0 && archive_input.reads > 0);
    if (accepted) {
        CHECK(rc == 0);
        CHECK(archive_input.offset == archive_input.size);
        CHECK(parsed.count == 1 && parsed.entries[0].kind == kind && !strcmp(parsed.entries[0].path, path));
        CHECK(!parsed.entries[0].created && parsed.bytes == data_size && parsed.names == path_size + 1);
    } else CHECK(rc == -1 && errno == EPROTO);
    CHECK(parsed.validate && parsed.root == -1 && parsed.manifest == -1);
    CHECK(!parsed.base_created && !parsed.root_created && !parsed.views_created);
    CHECK(!parsed.base_known && !parsed.root_known && !parsed.views_known);
done:
    for (uint32_t i = 0; i < parsed.count; ++i) free(parsed.entries[i].path);
    free(parsed.entries); free(parsed.index);
    memset(&archive_input, 0, sizeof(archive_input));
    return result;
}

static int test_archive_permission_controls(void)
{
    if (run_archive_permission_case("ordinary_file", 2, "usr/bin/plain", 0755, 0, 0, true) < 0 ||
        run_archive_permission_case("ordinary_directory", 1, "var/cache", 01777, 0, 42, true) < 0 ||
        run_archive_permission_case("type_bits_not_permissions", 2, "bad-mode", S_IFREG | 0755, 0, 0, false) < 0 ||
        run_archive_permission_case("out_of_range_mode", 2, "bad-mode", 010000, 0, 0, false) < 0 ||
        run_archive_permission_case("out_of_range_uid", 2, "bad-owner", 0755, 65536, 0, false) < 0 ||
        run_archive_permission_case("out_of_range_gid", 2, "bad-owner", 0755, 0, 65536, false) < 0 ||
        run_archive_permission_case("absolute_path", 2, "/escape", 0755, 0, 0, false) < 0 ||
        run_archive_permission_case("parent_path", 2, "../escape", 0755, 0, 0, false) < 0 ||
        run_archive_permission_case("unknown_special_node", 5, "special", 0755, 0, 0, false) < 0) return -1;
    return 0;
}

static int test_default_allow_root_guest_archive_permissions(void)
{
    if (run_archive_permission_case("defaultAllowRootGuestSetuidRegular", 2, "usr/bin/package-helper", 04755, 0, 0, true) < 0 ||
        run_archive_permission_case("defaultAllowRootGuestSetgidRegular", 2, "usr/bin/package-helper", 02755, 0, 42, true) < 0 ||
        run_archive_permission_case("defaultAllowRootGuestCombinedSetidRegular", 2, "usr/bin/package-helper", 06750, 0, 42, true) < 0 ||
        run_archive_permission_case("defaultAllowRootGuestSetgidStickyDirectory", 1, "var/cache/package-shared", 03775, 0, 42, true) < 0) return -1;
    return 0;
}

enum mutable_case { V2_MODIFIED_TREE, V2_OUTSIDE_SYMLINK, V2_ROOT_SUBMOUNT, V2_ENTRY_SUBMOUNT,
    V2_FOREIGN_PARENT, V2_FOREIGN_BASE, V2_FOREIGN_ROOT, V2_FOREIGN_VIEWS, V2_FOREIGN_MANIFEST, V2_ACTIVE_VIEW, V2_NAMED_RACE,
    V2_BAD_MAGIC, V2_BAD_ROOT_HEADER, V2_BAD_RECORD, V2_CASES };

static int run_mutable_case(enum mutable_case scenario)
{
    /* New AJIM v2 fixtures only. The original 28 v1 immutable cases above are not reinterpreted. */
    const char *test_name = "v2_mutable_remove";
    struct fixture f;
    int result = 0, etc = -1, external = -1, foreign = -1;
    struct stat external_payload = {0};
    CHECK(fixture_open(&f) == 0);
    f.header[0] = AJIU_MANIFEST_V2;
    if (scenario == V2_BAD_MAGIC) f.header[0] = UINT64_C(0x414a494d00000003);
    if (scenario == V2_BAD_ROOT_HEADER) ++f.header[2];
    if (scenario == V2_BAD_RECORD) f.records[1][0] = 7;
    CHECK(manifest_write(&f) == 0);
    int loaded = load_existing(&f.loaded, f.base_stat.st_dev, f.base_stat.st_ino);
    if (scenario == V2_BAD_MAGIC || scenario == V2_BAD_ROOT_HEADER || scenario == V2_BAD_RECORD) {
        CHECK(loaded == -1 && seam.unlink_calls == 0 && entry_present(&f)); goto done;
    }
    CHECK(loaded == 0 && f.loaded.mutable_tree);
    etc = openat(f.root, "etc", DIR_FLAGS); CHECK(etc >= 0);
    switch (scenario) {
    case V2_MODIFIED_TREE: {
        CHECK(unlinkat(etc, "payload", 0) == 0);
        struct stat replacement, added;
        CHECK(make_file(etc, "payload", "apt replacement", &replacement) == 0);
        CHECK(make_file(etc, "new-package", "apt addition", &added) == 0);
        int var = mkdir_open(f.root, "var", 0755); CHECK(var >= 0 && close(var) == 0);
        break;
    }
    case V2_OUTSIDE_SYMLINK: {
        external = mkdir_open(f.sandbox, "external", 0700); CHECK(external >= 0);
        CHECK(make_file(external, "payload", "outside untouched", &external_payload) == 0);
        CHECK(unlinkat(etc, "payload", 0) == 0);
        char target[128]; CHECK(snprintf(target, sizeof(target), "%s/external", f.temp) > 0);
        CHECK(symlinkat(target, f.root, "outside-link") == 0);
        int link = openat(f.root, "outside-link", O_PATH | O_NOFOLLOW | O_CLOEXEC); struct stat st;
        CHECK(link >= 0 && fstat(link, &st) == 0 && remember(&st) == 0 && close(link) == 0);
        break;
    }
    case V2_ROOT_SUBMOUNT: node_for(&f.root_stat)->mount_id = CURRENT_MOUNT + 1; break;
    case V2_ENTRY_SUBMOUNT: node_for(&f.entries[1])->mount_id = CURRENT_MOUNT + 1; break;
    case V2_FOREIGN_PARENT:
        CHECK(renameat(f.data, "local", f.data, "saved-local") == 0);
        foreign = mkdir_open(f.data, "local", 0700); CHECK(foreign >= 0); break;
    case V2_ACTIVE_VIEW: CHECK(mkdirat(f.views, "active-launch", 0700) == 0); break;
    case V2_FOREIGN_BASE:
        CHECK(renameat(f.local, f.loaded.name, f.local, "saved-base") == 0);
        foreign = mkdir_open(f.local, f.loaded.name, 0700); CHECK(foreign >= 0); break;
    case V2_FOREIGN_ROOT:
        CHECK(renameat(f.base, "root", f.base, "saved-root") == 0);
        foreign = mkdir_open(f.base, "root", 0755); CHECK(foreign >= 0); break;
    case V2_FOREIGN_VIEWS:
        CHECK(renameat(f.base, "views", f.base, "saved-views") == 0);
        foreign = mkdir_open(f.base, "views", 0700); CHECK(foreign >= 0); break;
    case V2_FOREIGN_MANIFEST: {
        CHECK(renameat(f.base, "manifest", f.base, "saved-manifest") == 0);
        struct stat st; CHECK(make_file(f.base, "manifest", "foreign ledger", &st) == 0);
        CHECK(fchmodat(f.base, "manifest", 0400, 0) == 0); break;
    }
    case V2_NAMED_RACE: seam.replace_payload_on_named_stat = true; break;
    default: break;
    }
    size_t before = seam.unlink_calls;
    int removed = rollback(&f.loaded);
    if (scenario == V2_MODIFIED_TREE || scenario == V2_OUTSIDE_SYMLINK) {
        struct stat gone;
        CHECK(removed == 0 && fstatat(f.local, f.loaded.name, &gone, AT_SYMLINK_NOFOLLOW) < 0 && errno == ENOENT);
        if (scenario == V2_OUTSIDE_SYMLINK) {
            struct stat kept; CHECK(fstatat(external, "payload", &kept, AT_SYMLINK_NOFOLLOW) == 0 && same_object(&kept, &external_payload));
        }
    } else {
        CHECK(removed == -1 && seam.unlink_calls == before);
        CHECK(faccessat(f.base, scenario == V2_FOREIGN_MANIFEST ? "saved-manifest" : "manifest", F_OK, AT_SYMLINK_NOFOLLOW) == 0);
        if (scenario == V2_NAMED_RACE) {
            int replacement = openat(etc, "payload", O_RDONLY | O_NOFOLLOW | O_CLOEXEC); char bytes[16];
            CHECK(replacement >= 0 && read(replacement, bytes, sizeof(bytes)) == 12 && !memcmp(bytes, "race-foreign", 12) && close(replacement) == 0);
        }
    }
done:
    if (foreign >= 0) close(foreign);
    if (external >= 0) close(external);
    if (etc >= 0) close(etc);
    if (fixture_close(&f) < 0) { fprintf(stderr, "v2 fixture cleanup failed errno=%d\n", errno); result = -1; }
    return result;
}

static int test_mutable_remove_v2(void)
{
    for (int scenario = 0; scenario < V2_CASES; ++scenario)
        if (run_mutable_case((enum mutable_case)scenario) < 0) return -1;
    const char *test_name = "v2_mutable_budget"; int result = 0;
    struct mutable_tree tree = {0}; struct stat st = { .st_mode = S_IFREG | 0600 };
    tree.count = MAX_ENTRIES; CHECK(mutable_remember(&tree, "bounded", &st, CURRENT_MOUNT) == -1 && errno == EOVERFLOW);
    tree.count = 0; tree.names = MAX_NAMES;
    CHECK(mutable_remember(&tree, "bounded", &st, CURRENT_MOUNT) == -1 && errno == EOVERFLOW);
    struct install install = {0}; tree.names = 0;
    CHECK(mutable_scan(&install, &tree, -1, "", MUTABLE_MAX_DEPTH + 1) == -1 && errno == EOVERFLOW);
done:
    return result;
}

static int test_review_regression_named_anchor_mount(void)
{
    const char *const names[] = { "root", "views", "manifest", "base" };
    for (size_t scenario = 0; scenario < sizeof(names) / sizeof(*names); ++scenario) {
        const char *test_name = names[scenario];
        struct fixture f; int result = 0;
        CHECK(fixture_open(&f) == 0);
        f.header[0] = AJIU_MANIFEST_V2; CHECK(manifest_write(&f) == 0);
        CHECK(load_existing(&f.loaded, f.base_stat.st_dev, f.base_stat.st_ino) == 0 && f.loaded.mutable_tree);
        int held = scenario == 0 ? f.loaded.root : scenario == 1 ? f.loaded.views : scenario == 2 ? f.loaded.manifest : f.loaded.base;
        struct stat original; CHECK(fstat(held, &original) == 0);
        /* Same dev/inode/type, but a named re-open now sees a different synthetic mount.
         * Held FDs retain CURRENT_MOUNT, exactly as a same-inode bind over the name would. */
        seam.named_mount_changed = true; seam.named_dev = original.st_dev; seam.named_ino = original.st_ino;
        seam.named_mount = CURRENT_MOUNT + 701;
        uint64_t id; CHECK(mount_identity(held, &id) == 0 && id == CURRENT_MOUNT);
        size_t before = seam.unlink_calls;
        int rc = rollback(&f.loaded);
        CHECK(rc == -1);
        CHECK(seam.unlink_calls == before);
        CHECK(seam.named_mount_reads > 0);
        CHECK(entry_present(&f));
        struct stat kept; CHECK(fstat(held, &kept) == 0 && same_object(&kept, &original));
        CHECK(faccessat(f.base, "manifest", F_OK, AT_SYMLINK_NOFOLLOW) == 0);
        CHECK(faccessat(f.base, "root", F_OK, AT_SYMLINK_NOFOLLOW) == 0 && faccessat(f.base, "views", F_OK, AT_SYMLINK_NOFOLLOW) == 0);
    done:
        if (fixture_close(&f) < 0) { fprintf(stderr, "Named anchor fixture cleanup failed: %s (errno=%d)\n", test_name, errno); result = -1; }
        if (result) return -1;
    }
    return 0;
}

struct cli_result {
    char json[2048];
    int status;
    struct { size_t unlinks, rejected, receipt_syncs, base_syncs, local_syncs, boot_opens, boot_mount_rejections;
        size_t process_scans, reference_checks, lock_calls, padding_emitted, stat_faults, directory_faults, process_stat_reads;
        int64_t deadline_ms; bool unsafe; } calls;
};

static int proc_mark(int fd)
{
    struct test_node *node = node_fd(fd);
    if (!node) return -1;
    node->mount_id = PROC_MOUNT;
    return 0;
}

static int proc_directory(int parent, const char *name)
{
    int fd = mkdir_open(parent, name, 0555);
    return fd < 0 || proc_mark(fd) < 0 ? -1 : fd;
}

static int proc_link(int parent, const char *name, const char *target_path, const struct stat *target)
{
    if (symlinkat(target_path, parent, name) < 0) return -1;
    struct stat link;
    if (fstatat(parent, name, &link, AT_SYMLINK_NOFOLLOW) < 0 || remember(&link) < 0) return -1;
    struct test_node *node = node_for(&link);
    node->mount_id = PROC_MOUNT; node->proc_ref = true; node->ref = *target;
    return 0;
}

static int proc_stat_file_flags(int parent, const char *name, uint64_t flags)
{
    char contents[128] = "4242 (fixture process) S";
    for (unsigned int field = 4; field < 22; ++field) {
        char value[24];
        if (field == 8) (void)snprintf(value, sizeof(value), " -1");
        else (void)snprintf(value, sizeof(value), " %" PRIu64, field == 9 ? flags : UINT64_C(0));
        strcat(contents, value);
    }
    strcat(contents, " 4242\n");
    struct stat st;
    if (make_file(parent, name, contents, &st) < 0) return -1;
    struct test_node *node = node_for(&st);
    node->mount_id = PROC_MOUNT;
    return 0;
}

static int proc_stat_file(int parent, const char *name)
{
    return proc_stat_file_flags(parent, name, 0);
}

static int proc_fixture(struct fixture *f)
{
    struct stat unrelated;
    if (fstat(f->sandbox, &unrelated) < 0 || proc_link(seam.proc, "self", "4242", &unrelated) < 0) return -1;
    int own_process = proc_directory(seam.proc, "4242");
    int own_tasks = own_process < 0 ? -1 : proc_directory(own_process, "task");
    int own_thread = own_tasks < 0 ? -1 : proc_directory(own_tasks, "4242");
    if (own_process < 0 || own_tasks < 0 || own_thread < 0 || proc_stat_file(own_process, "stat") < 0 ||
        proc_stat_file(own_thread, "stat") < 0 || proc_link(own_process, "root", ".", &unrelated) < 0 ||
        proc_link(own_process, "cwd", ".", &unrelated) < 0) return -1;
    int own_fds = proc_directory(own_process, "fd");
    if (own_fds < 0 || proc_link(own_fds, "0", ".", &unrelated) < 0) return -1;
    close(own_fds); close(own_thread); close(own_tasks);
    f->proc_self = own_process;
    f->proc_process = proc_directory(seam.proc, "3131");
    f->churn_process = proc_directory(seam.proc, "5151");
    if (f->proc_process < 0 || f->churn_process < 0 || proc_stat_file(f->proc_process, "stat") < 0 ||
        proc_stat_file(f->churn_process, "stat") < 0 ||
        proc_link(f->proc_process, "exe", ".", &unrelated) < 0 ||
        proc_link(f->churn_process, "exe", ".", &unrelated) < 0) return -1;
    int proc_maps = proc_directory(f->proc_process, "map_files");
    int churn_maps = proc_directory(f->churn_process, "map_files");
    if (proc_maps < 0 || churn_maps < 0 || proc_link(proc_maps, "1000-2000", ".", &unrelated) < 0 ||
        proc_link(churn_maps, "1000-2000", ".", &unrelated) < 0) return -1;
    close(proc_maps); close(churn_maps);
    int task_root = proc_directory(f->proc_process, "task");
    int churn_task_root = proc_directory(f->churn_process, "task");
    if (task_root < 0 || churn_task_root < 0) return -1;
    f->proc_thread = proc_directory(task_root, "3131");
    int churn_thread = proc_directory(churn_task_root, "5151");
    close(task_root); close(churn_task_root);
    if (f->proc_thread < 0 || churn_thread < 0 || proc_stat_file(f->proc_thread, "stat") < 0 ||
        proc_stat_file(churn_thread, "stat") < 0 || proc_link(churn_thread, "root", ".", &unrelated) < 0 ||
        proc_link(churn_thread, "cwd", ".", &unrelated) < 0) return -1;
    int churn_fds = proc_directory(churn_thread, "fd");
    if (churn_fds < 0 || proc_link(churn_fds, "0", ".", &unrelated) < 0) return -1;
    close(churn_fds); close(churn_thread);
    int kthread_process = proc_directory(seam.proc, "6161");
    int kthread_tasks = kthread_process < 0 ? -1 : proc_directory(kthread_process, "task");
    int kthread = kthread_tasks < 0 ? -1 : proc_directory(kthread_tasks, "6161");
    if (kthread_process < 0 || kthread_tasks < 0 || kthread < 0 ||
        proc_stat_file_flags(kthread_process, "stat", UINT64_C(0x00200000)) < 0 ||
        proc_stat_file_flags(kthread, "stat", UINT64_C(0x00200000)) < 0) return -1;
    close(kthread); close(kthread_tasks); close(kthread_process);
    if (proc_link(f->proc_thread, "root", "../../../..", &unrelated) < 0 ||
        proc_link(f->proc_thread, "cwd", "../../../../", &unrelated) < 0) return -1;
    int fds = proc_directory(f->proc_thread, "fd");
    if (fds < 0) return -1;
    int rc = proc_link(fds, "0", "../../../../..", &unrelated);
    close(fds);
    return rc;
}

static int proc_set_reference(int parent, const char *name, const struct stat *target)
{
    struct stat link;
    if (fstatat(parent, name, &link, AT_SYMLINK_NOFOLLOW) < 0) return -1;
    struct test_node *node = node_for(&link);
    if (!node || !node->proc_ref) { errno = ESTALE; return -1; }
    node->ref = *target;
    return 0;
}

static int recovery_boot(struct fixture *f, bool next)
{
    if (seam.proc < 0) {
        seam.proc = mkdir_open(f->sandbox, "proc", 0555);
        if (seam.proc < 0) return -1;
        node_fd(seam.proc)->mount_id = PROC_MOUNT;
        if (proc_fixture(f) < 0) return -1;
    }
    if (seam.boot < 0) {
        struct stat st;
        if (make_file(f->sandbox, "boot-id", "", &st) < 0) return -1;
        seam.boot = openat(f->sandbox, "boot-id", O_RDWR | O_NOFOLLOW | O_CLOEXEC);
        if (seam.boot < 0) return -1;
        node_fd(seam.boot)->mount_id = PROC_MOUNT;
    }
    const char *id = next ? "22222222-2222-4222-8222-222222222222\n" : "11111111-1111-4111-8111-111111111111\n";
    return pwrite(seam.boot, id, 37, 0) == 37 && ftruncate(seam.boot, 37) == 0 ? 0 : -1;
}

static int recovery_cli(struct fixture *f, const char *operation, bool wrong_identity, struct cli_result *out)
{
    int output[2], report[2];
    if (pipe(output) < 0) return -1;
    if (pipe(report) < 0) { close(output[0]); close(output[1]); return -1; }
    fflush(stdout);
    pid_t child = fork();
    if (child == 0) {
        close(output[0]); close(report[0]);
        if (dup2(output[1], STDOUT_FILENO) < 0) _exit(99);
        close(output[1]);
        char dev[32], ino[32];
        (void)snprintf(dev, sizeof(dev), "%ju", (uintmax_t)f->base_stat.st_dev);
        (void)snprintf(ino, sizeof(ino), "%ju", (uintmax_t)f->base_stat.st_ino + (wrong_identity ? 1U : 0U));
        char *args[] = { "installer-fixture", (char *)operation, f->loaded.name + strlen(PREFIX), dev, ino, NULL };
        seam.clock_origin = monotonic_ms(); seam.fixed_clock = true;
        int argc = !strcmp(operation, "--install") ? 3 : !strcmp(operation, "--validate") ? 2 : 5;
        int rc = rootfs_install_production_main_not_called(argc, args);
        out->calls.unlinks = seam.unlink_calls; out->calls.rejected = seam.rejected_calls;
        out->calls.receipt_syncs = seam.receipt_syncs; out->calls.base_syncs = seam.base_syncs;
        out->calls.local_syncs = seam.local_syncs;
        out->calls.boot_opens = seam.boot_opens; out->calls.boot_mount_rejections = seam.boot_mount_rejections;
        out->calls.process_scans = seam.process_scans; out->calls.reference_checks = seam.reference_checks;
        out->calls.lock_calls = seam.lock_calls;
        out->calls.padding_emitted = seam.padding_emitted;
        out->calls.stat_faults = seam.stat_faults; out->calls.directory_faults = seam.directory_faults;
        struct test_node *process = node_fd(f->proc_process);
        out->calls.process_stat_reads = process ? process->stat_reads : 0;
        out->calls.deadline_ms = deadline - seam.clock_origin;
        out->calls.unsafe = seam.unsafe_unlink || seam.unsafe_lock;
        if (write_all(report[1], &out->calls, sizeof(out->calls)) < 0 || fflush(stdout) != 0) _exit(98);
        close(report[1]); _exit(rc);
    }
    close(output[1]); close(report[1]);
    int error = 0, status = 0; size_t used = 0;
    if (child < 0) error = errno;
    while (!error && used < sizeof(out->json) - 1) {
        ssize_t n = read(output[0], out->json + used, sizeof(out->json) - 1 - used);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) { error = errno; break; }
        if (!n) break;
        used += (size_t)n;
    }
    out->json[used] = 0;
    if (child >= 0) {
        if (read(report[0], &out->calls, sizeof(out->calls)) != (ssize_t)sizeof(out->calls)) error = EIO;
        if (waitpid(child, &status, 0) != child || !WIFEXITED(status)) error = ECHILD;
        else out->status = WEXITSTATUS(status);
    }
    close(output[0]); close(report[0]);
    struct stat st;
    if (!error && fstatat(f->base, REMOVE_RECEIPT, &st, AT_SYMLINK_NOFOLLOW) == 0 && remember(&st) < 0) error = errno;
    return error ? (errno = error, -1) : 0;
}

static int test_removal_scan_budget(void)
{
    enum { ABOVE_OLD_LIMIT, ENTRY_LIMIT, AFTER_THIRTY_SECONDS, AFTER_NINETY_SECONDS,
        INSTALL_DEADLINE, VALIDATE_DEADLINE, BUDGET_CASES };
    const char *const names[] = { "scan_above_old_limit_two_passes", "scan_new_entry_limit",
        "remove_after_thirty_seconds", "remove_ninety_second_deadline", "install_thirty_second_deadline",
        "validate_thirty_second_deadline" };
    for (int scenario = 0; scenario < BUDGET_CASES; ++scenario) {
        const char *test_name = names[scenario];
        int result = 0; struct fixture f; struct cli_result out = {0};
        CHECK(fixture_open(&f) == 0); f.header[0] = AJIU_MANIFEST_V2;
        CHECK(manifest_write(&f) == 0 && recovery_boot(&f, false) == 0);
        seam.enforce_receipt = true;
        if (scenario == ABOVE_OLD_LIMIT) seam.proc_padding = 5U * 1024U * 1024U;
        if (scenario == ENTRY_LIMIT) seam.proc_padding = 8U * 1024U * 1024U;
        if (scenario == AFTER_THIRTY_SECONDS) seam.scan_clock_step = 35000;
        if (scenario == AFTER_NINETY_SECONDS) seam.scan_clock_step = 50000;
        unsigned char stream[32] = {0};
        memcpy(stream, "AJIF\0\1\0\0", 8); stream[8] = 255;
        if (scenario == VALIDATE_DEADLINE) {
            archive_input.bytes = stream; archive_input.size = sizeof(stream);
        }
        const char *operation = scenario == INSTALL_DEADLINE ? "--install" :
            scenario == VALIDATE_DEADLINE ? "--validate" : scenario == AFTER_THIRTY_SECONDS ? "--recover-remove" : "--remove";
        CHECK(recovery_cli(&f, operation, false, &out) == 0);
        if (scenario == ABOVE_OLD_LIMIT || scenario == AFTER_THIRTY_SECONDS) {
            if (out.status) fprintf(stderr, "%s returned %d: %s", test_name, out.status, out.json);
            CHECK(out.status == 0 && strstr(out.json, "\"passed\":true") && strstr(out.json, "\"cleanup_state\":\"removed\""));
            CHECK(out.calls.process_scans == 2 && out.calls.unlinks == 7 && out.calls.local_syncs == 1);
            CHECK(out.calls.padding_emitted == seam.proc_padding * 2 && out.calls.deadline_ms == 90000);
            CHECK(faccessat(f.local, f.loaded.name, F_OK, AT_SYMLINK_NOFOLLOW) < 0 && errno == ENOENT);
        } else if (scenario == INSTALL_DEADLINE || scenario == VALIDATE_DEADLINE) {
            CHECK(out.calls.deadline_ms == 30000 && !out.calls.unlinks && entry_present(&f));
            CHECK(scenario == INSTALL_DEADLINE ? out.status != 0 : out.status == 0);
        } else {
            CHECK(out.status == 5 && strstr(out.json, "\"passed\":false") && strstr(out.json, "cleanup_unknown"));
            char error[32];
            CHECK(snprintf(error, sizeof(error), "\"errno\":%d", scenario == AFTER_NINETY_SECONDS ? ETIMEDOUT : EOVERFLOW) > 0);
            CHECK(strstr(out.json, error) && !out.calls.unlinks && entry_present(&f));
            CHECK(faccessat(f.base, REMOVE_RECEIPT, F_OK, AT_SYMLINK_NOFOLLOW) == 0);
            CHECK(out.calls.deadline_ms == 90000);
            if (scenario == ENTRY_LIMIT) CHECK(out.calls.padding_emitted == 8U * 1024U * 1024U && out.calls.process_scans == 1);
            if (scenario == AFTER_NINETY_SECONDS) CHECK(out.calls.process_scans == 2);
        }
        CHECK(!out.calls.unsafe && !out.calls.rejected);
    done:
        memset(&archive_input, 0, sizeof(archive_input));
        if (fixture_close(&f) < 0) result = -1;
        if (result) return -1;
    }
    return 0;
}

static int test_removal_proc_churn(void)
{
    const int errors[] = { ENOENT, ESRCH, EACCES, EPERM };
    enum { INITIAL_STAT, FINAL_STAT, MISSING_REFERENCE, MISSING_DIRECTORY, DIRECTORY_TEARDOWN, CHURN_PHASES };
    for (unsigned int thread = 0; thread < 2; ++thread) {
        for (unsigned int phase = 0; phase < CHURN_PHASES; ++phase) {
            for (unsigned int read_error = 0; read_error < 2; ++read_error) {
                for (size_t e = 0; e < sizeof(errors) / sizeof(*errors); ++e) {
                    char name[96];
                    (void)snprintf(name, sizeof(name), "pinned_%s_phase_%u_%s_error_%d", thread ? "thread" : "process",
                        phase, read_error ? "read" : "open", errors[e]);
                    const char *test_name = name;
                    int result = 0, directory = -1; struct fixture f; struct cli_result out = {0};
                    CHECK(fixture_open(&f) == 0); f.header[0] = AJIU_MANIFEST_V2;
                    CHECK(manifest_write(&f) == 0 && recovery_boot(&f, false) == 0);
                    seam.enforce_receipt = true;
                    int target = thread ? f.proc_thread : f.proc_process;
                    struct test_node *node = node_fd(target); CHECK(node != NULL);
                    node->stat_fault_at = phase == INITIAL_STAT ? 1U : thread ? 2U : 3U;
                    node->stat_error = errors[e]; node->stat_read_error = read_error != 0;
                    if (phase == MISSING_REFERENCE) {
                        struct stat link;
                        CHECK(fstatat(target, thread ? "root" : "exe", &link, AT_SYMLINK_NOFOLLOW) == 0);
                        node_for(&link)->ref_error = ENOENT;
                    }
                    if (phase == MISSING_DIRECTORY || phase == DIRECTORY_TEARDOWN) {
                        directory = openat(target, thread ? "fd" : "map_files", DIR_FLAGS); CHECK(directory >= 0);
                        if (phase == MISSING_DIRECTORY) node_fd(directory)->open_error = ENOENT;
                        else node_fd(directory)->directory_error = ESRCH;
                    }
                    CHECK(recovery_cli(&f, thread ? "--recover-remove" : "--remove", false, &out) == 0);
                    CHECK(out.calls.stat_faults > 0);
                    if (errors[e] == ENOENT || errors[e] == ESRCH) {
                        if (out.status) fprintf(stderr, "%s returned %d: %s", test_name, out.status, out.json);
                        CHECK(out.status == 0 && strstr(out.json, "\"passed\":true") && strstr(out.json, "\"cleanup_state\":\"removed\""));
                        CHECK(out.calls.process_scans == 2 && out.calls.unlinks == 7 && out.calls.local_syncs == 1);
                        CHECK(faccessat(f.local, f.loaded.name, F_OK, AT_SYMLINK_NOFOLLOW) < 0 && errno == ENOENT);
                    } else {
                        CHECK(out.status == 5 && strstr(out.json, "\"passed\":false") && strstr(out.json, "cleanup_unknown"));
                        CHECK(!out.calls.unlinks && entry_present(&f));
                        CHECK(faccessat(f.base, REMOVE_RECEIPT, F_OK, AT_SYMLINK_NOFOLLOW) == 0);
                    }
                    CHECK(!out.calls.unsafe && !out.calls.rejected);
                done:
                    if (directory >= 0) close(directory);
                    if (fixture_close(&f) < 0) result = -1;
                    if (result) return -1;
                }
            }
        }
    }
    return 0;
}

static int test_removal_proc_guards(void)
{
    const char *const refs[] = { "root", "cwd", "exe", "0", "1000-2000" };
    const int errors[] = { 0, ENOENT, ESRCH, EACCES, EPERM, EBUSY };
    for (size_t ref = 0; ref < sizeof(refs) / sizeof(*refs); ++ref) {
        for (size_t e = 0; e < sizeof(errors) / sizeof(*errors); ++e) {
            char name[96]; (void)snprintf(name, sizeof(name), "active_reference_%s_error_%d", refs[ref], errors[e]);
            const char *test_name = name;
            int result = 0, directory = -1; struct fixture f; struct cli_result out = {0};
            CHECK(fixture_open(&f) == 0); f.header[0] = AJIU_MANIFEST_V2;
            CHECK(manifest_write(&f) == 0 && recovery_boot(&f, false) == 0);
            int target = ref < 2 ? f.proc_thread : f.proc_process;
            if (ref >= 3) {
                directory = openat(ref == 3 ? f.proc_thread : f.proc_process, ref == 3 ? "fd" : "map_files", DIR_FLAGS);
                CHECK(directory >= 0); target = directory;
            }
            struct stat link; CHECK(fstatat(target, refs[ref], &link, AT_SYMLINK_NOFOLLOW) == 0);
            struct test_node *node = node_for(&link); CHECK(node != NULL);
            node->ref_error = errors[e];
            if (!errors[e]) node->ref = f.entries[1];
            if (errors[e] == EBUSY) {
                node->ref_error = 0; node->replace_ref_on_follow = true; node->next_ref = f.entries[1];
            }
            bool allowed = ref >= 3 && (errors[e] == ENOENT || errors[e] == ESRCH);
            CHECK(recovery_cli(&f, "--remove", false, &out) == 0);
            if (allowed) CHECK(out.status == 0 && out.calls.process_scans == 2 && out.calls.unlinks == 7);
            else {
                CHECK(out.status == 5 && strstr(out.json, "cleanup_unknown") && !out.calls.unlinks && entry_present(&f));
                CHECK(faccessat(f.base, REMOVE_RECEIPT, F_OK, AT_SYMLINK_NOFOLLOW) == 0);
            }
            CHECK(!out.calls.unsafe && !out.calls.rejected);
        done:
            if (directory >= 0) close(directory);
            if (fixture_close(&f) < 0) result = -1;
            if (result) return -1;
        }
    }
    return 0;
}

static int test_removal_stat_guards(void)
{
    for (unsigned int thread = 0; thread < 2; ++thread) {
        for (unsigned int malformed = 0; malformed < 4; ++malformed) {
            const char *test_name = malformed ? "malformed_stat_refused" : "changed_start_time_refused";
            int result = 0, fd = -1; struct fixture f; struct cli_result out = {0};
            CHECK(fixture_open(&f) == 0); f.header[0] = AJIU_MANIFEST_V2;
            CHECK(manifest_write(&f) == 0 && recovery_boot(&f, false) == 0);
            int target = thread ? f.proc_thread : f.proc_process;
            if (malformed) {
                fd = openat(target, "stat", O_WRONLY | O_NOFOLLOW | O_CLOEXEC); CHECK(fd >= 0);
                const char *text = malformed == 1 ? "no stat fields\n" : malformed == 2 ? "4242 (fixture)\n" : "4242 (fixture) S bad\n";
                size_t length = strlen(text);
                CHECK(pwrite(fd, text, length, 0) == (ssize_t)length && ftruncate(fd, (off_t)length) == 0);
            } else {
                node_fd(target)->stat_fault_at = thread ? 2U : 3U; node_fd(target)->change_stat_start = true;
            }
            CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0);
            CHECK(out.status == 5 && strstr(out.json, "cleanup_unknown") && !out.calls.unlinks && entry_present(&f));
            CHECK(faccessat(f.base, REMOVE_RECEIPT, F_OK, AT_SYMLINK_NOFOLLOW) == 0);
            CHECK(!out.calls.unsafe && !out.calls.rejected);
        done:
            if (fd >= 0) close(fd);
            if (fixture_close(&f) < 0) result = -1;
            if (result) return -1;
        }
    }
    return 0;
}

static int test_removal_process_finish(void)
{
    enum { MISSING_OPEN, MISSING_READ, ESRCH_OPEN, ESRCH_READ, PERM_OPEN, PERM_READ,
        ACCESS_OPEN, ACCESS_READ, CHANGED_START, MALFORMED, PROCESS_FINISH_CASES };
    const char *const names[] = { "process_fourth_stat_missing_open", "process_fourth_stat_missing_read",
        "process_fourth_stat_esrch_open", "process_fourth_stat_esrch_read", "process_fourth_stat_perm_open",
        "process_fourth_stat_perm_read", "process_fourth_stat_access_open", "process_fourth_stat_access_read",
        "process_fourth_stat_changed_start", "process_fourth_stat_malformed" };
    for (unsigned int scenario = 0; scenario < PROCESS_FINISH_CASES; ++scenario) {
        const char *test_name = names[scenario];
        int result = 0; struct fixture f; struct cli_result out = {0};
        CHECK(fixture_open(&f) == 0); f.header[0] = AJIU_MANIFEST_V2;
        CHECK(manifest_write(&f) == 0 && recovery_boot(&f, false) == 0);
        seam.enforce_receipt = true;
        struct test_node *node = node_fd(f.proc_process); CHECK(node != NULL);
        node->stat_fault_at = 4;
        node->stat_read_error = scenario < CHANGED_START && (scenario % 2 != 0);
        node->stat_error = scenario <= MISSING_READ ? ENOENT : scenario <= ESRCH_READ ? ESRCH :
            scenario <= PERM_READ ? EPERM : scenario <= ACCESS_READ ? EACCES : 0;
        node->change_stat_start = scenario == CHANGED_START; node->malform_stat = scenario == MALFORMED;
        CHECK(recovery_cli(&f, "--remove", false, &out) == 0);
        if (scenario <= ESRCH_READ) {
            CHECK(out.status == 0 && strstr(out.json, "\"passed\":true") && strstr(out.json, "\"cleanup_state\":\"removed\""));
            CHECK(out.calls.process_stat_reads == 5 && out.calls.stat_faults == 2);
            CHECK(out.calls.process_scans == 2 && out.calls.unlinks == 7 && out.calls.local_syncs == 1);
            CHECK(faccessat(f.local, f.loaded.name, F_OK, AT_SYMLINK_NOFOLLOW) < 0 && errno == ENOENT);
        } else {
            CHECK(out.status == 5 && strstr(out.json, "\"passed\":false") && strstr(out.json, "cleanup_unknown"));
            int expected_error = scenario == CHANGED_START ? ESTALE : scenario == MALFORMED ? EPROTO : node->stat_error;
            char error[32]; CHECK(snprintf(error, sizeof(error), "\"errno\":%d", expected_error) > 0 && strstr(out.json, error));
            CHECK(out.calls.process_stat_reads == 4 && out.calls.process_scans == 1);
            CHECK(!out.calls.unlinks && entry_present(&f));
            CHECK(faccessat(f.base, REMOVE_RECEIPT, F_OK, AT_SYMLINK_NOFOLLOW) == 0);
        }
        CHECK(!out.calls.unsafe && !out.calls.rejected);
    done:
        if (fixture_close(&f) < 0) result = -1;
        if (result) return -1;
    }
    return 0;
}

enum recovery_case {
    REC_EMPTY, REC_VIEWS, REC_LEGACY, REC_SAME_BOOT_WITH_VIEW,
    REC_BAD_ID_0, REC_BAD_ID_1, REC_BAD_ID_2, REC_BAD_ID_3, REC_BAD_ID_4, REC_BAD_ID_5, REC_BAD_ID_6, REC_BAD_ID_7,
    REC_BAD_INSTANCE, REC_BAD_MAGIC, REC_BAD_BOOT, REC_RESERVED, REC_SHORT, REC_LONG,
    REC_OWNER, REC_GROUP, REC_MODE, REC_LINK, REC_SYMLINK, REC_MOUNT, REC_NAMED_MOUNT, REC_MANIFEST_REPLACED,
    REC_VIEW_BAD_NAME, REC_VIEW_SYMLINK, REC_VIEW_MOUNT, REC_VIEW_NAMED_MOUNT, REC_VIEW_NONEMPTY, REC_VIEW_OWNER, REC_VIEW_MODE, REC_VIEW_RACE,
    REC_ROOT_MOUNT, REC_EXTRA_BASE, REC_WRONG_ARGUMENT,
    REC_WRITE_FAIL, REC_FILE_SYNC_FAIL, REC_BASE_SYNC_FAIL, REC_ROOT_UNLINK_FAIL, REC_BASE_UNLINK_FAIL,
    REC_PARENT_SYNC_FAIL, REC_FINAL_BASE_OWNER, REC_FINAL_BASE_GROUP, REC_FINAL_BASE_MODE,
    REC_CASES
};

static int test_recovery_case(enum recovery_case scenario)
{
    char test_name_buffer[64];
    (void)snprintf(test_name_buffer, sizeof(test_name_buffer), "recovery_case_%d", scenario);
    const char *test_name = test_name_buffer;
    struct fixture f; struct cli_result out = {0};
    int result = 0, view = -1, receipt = -1;
    const char *operation = scenario == REC_SAME_BOOT_WITH_VIEW ? "--remove" : "--recover-remove";
    const char *view_name = "abcdef0123456789abcdef01";
    CHECK(fixture_open(&f) == 0);
    f.header[0] = scenario == REC_LEGACY ? AJIU_MANIFEST_V1 : AJIU_MANIFEST_V2;
    CHECK(manifest_write(&f) == 0 && recovery_boot(&f, false) == 0);
    if (scenario != REC_EMPTY) { view = mkdir_open(f.views, view_name, 0700); CHECK(view >= 0); }
    seam.enforce_receipt = true;
    if (scenario == REC_WRITE_FAIL) seam.fail_receipt_write = true;
    if (scenario == REC_FILE_SYNC_FAIL) seam.fail_sync_call = 1;
    if (scenario == REC_BASE_SYNC_FAIL) seam.fail_sync_call = 2;
    seam.lock_busy = true;
    CHECK(recovery_cli(&f, operation, false, &out) == 0);
    CHECK(out.calls.unlinks == 0 && !out.calls.unsafe && entry_present(&f));
    CHECK(out.calls.local_syncs == 0);
    CHECK(faccessat(f.base, REMOVE_RECEIPT, F_OK, AT_SYMLINK_NOFOLLOW) == 0);
    if (scenario >= REC_WRITE_FAIL && scenario <= REC_BASE_SYNC_FAIL) {
        CHECK(out.status == 5 && strstr(out.json, "\"passed\":false") && strstr(out.json, "cleanup_unknown"));
        CHECK(recovery_cli(&f, "--remove", false, &out) == 0 && out.status == 5 && !out.calls.unlinks);
        goto done;
    }
    CHECK(out.status == 5 && strstr(out.json, "\"passed\":false") && strstr(out.json, "\"stage\":\"recovery_busy\"") &&
        strstr(out.json, "\"cleanup_state\":\"cleanup_unknown\""));
    CHECK(out.calls.receipt_syncs == 1 && out.calls.base_syncs == 1 && !out.calls.rejected && out.calls.lock_calls > 1);
    char expected[128];
    CHECK(snprintf(expected, sizeof(expected), "\"device\":%ju,\"inode\":%ju", (uintmax_t)f.base_stat.st_dev,
        (uintmax_t)f.base_stat.st_ino) > 0 && strstr(out.json, expected));
    CHECK(snprintf(expected, sizeof(expected), "\"created_directory\":\"/data/local/%s\"", f.loaded.name) > 0 && strstr(out.json, expected));
    struct stat receipt_stat;
    CHECK(fstatat(f.base, REMOVE_RECEIPT, &receipt_stat, AT_SYMLINK_NOFOLLOW) == 0);
    CHECK((receipt_stat.st_mode & 07777) == 0600 && receipt_stat.st_nlink == 1 && receipt_stat.st_size == 136);
    receipt = openat(f.base, REMOVE_RECEIPT, O_RDWR | O_NOFOLLOW | O_CLOEXEC); CHECK(receipt >= 0);
    struct removal_receipt data;
    CHECK(pread(receipt, &data, sizeof(data), 0) == (ssize_t)sizeof(data));
    CHECK(recovery_cli(&f, "--remove", false, &out) == 0 && out.status == 5 && !out.calls.unlinks && entry_present(&f));
    CHECK(recovery_cli(&f, "--install", false, &out) == 0 && out.status != 0 && !out.calls.unlinks && entry_present(&f));
    seam.lock_busy = false;
    if (scenario != REC_SAME_BOOT_WITH_VIEW) CHECK(recovery_boot(&f, true) == 0);
    if (scenario >= REC_BAD_ID_0 && scenario <= REC_BAD_ID_7) ++data.identity[scenario - REC_BAD_ID_0];
    switch (scenario) {
    case REC_BAD_INSTANCE: data.instance[0] = 'f'; break;
    case REC_BAD_MAGIC: ++data.magic; break;
    case REC_BAD_BOOT: data.boot_id[0] = 'x'; break;
    case REC_RESERVED: data.reserved = 1; break;
    default: break;
    }
    CHECK(pwrite(receipt, &data, sizeof(data), 0) == (ssize_t)sizeof(data));
    switch (scenario) {
    case REC_SHORT: CHECK(ftruncate(receipt, sizeof(data) - 1) == 0); break;
    case REC_LONG: CHECK(ftruncate(receipt, sizeof(data) + 1) == 0); break;
    case REC_OWNER: node_for(&receipt_stat)->uid = 10000; break;
    case REC_GROUP: node_for(&receipt_stat)->gid = 10000; break;
    case REC_MODE: CHECK(fchmod(receipt, 0644) == 0); break;
    case REC_LINK: node_for(&receipt_stat)->link_override = 2; break;
    case REC_SYMLINK:
        CHECK(renameat(f.base, REMOVE_RECEIPT, f.sandbox, "saved-receipt") == 0);
        CHECK(symlinkat("/nonexistent-receipt", f.base, REMOVE_RECEIPT) == 0); break;
    case REC_MOUNT: node_for(&receipt_stat)->mount_id = CURRENT_MOUNT + 1; break;
    case REC_NAMED_MOUNT:
        seam.named_mount_changed = true; seam.named_dev = receipt_stat.st_dev; seam.named_ino = receipt_stat.st_ino;
        seam.named_mount = CURRENT_MOUNT + 1; break;
    case REC_MANIFEST_REPLACED:
        CHECK(renameat(f.base, "manifest", f.sandbox, "old-manifest") == 0); CHECK(manifest_write(&f) == 0); break;
    case REC_VIEW_BAD_NAME: CHECK(renameat(f.views, view_name, f.views, "bad-view") == 0); break;
    case REC_VIEW_SYMLINK:
        CHECK(renameat(f.views, view_name, f.sandbox, "saved-view") == 0);
        CHECK(symlinkat("/nonexistent-view", f.views, view_name) == 0); break;
    case REC_VIEW_MOUNT: node_fd(view)->mount_id = CURRENT_MOUNT + 1; break;
    case REC_VIEW_NAMED_MOUNT: {
        struct stat st; CHECK(fstat(view, &st) == 0);
        seam.named_mount_changed = true; seam.named_dev = st.st_dev; seam.named_ino = st.st_ino;
        seam.named_mount = CURRENT_MOUNT + 1; break;
    }
    case REC_VIEW_NONEMPTY: { struct stat st; CHECK(make_file(view, "keep", "unproven", &st) == 0); break; }
    case REC_VIEW_OWNER: node_fd(view)->uid = 10000; break;
    case REC_VIEW_MODE: CHECK(fchmod(view, 0755) == 0); break;
    case REC_VIEW_RACE: seam.replace_view_on_reopen = true; break;
    case REC_ROOT_MOUNT: node_for(&f.root_stat)->mount_id = CURRENT_MOUNT + 1; break;
    case REC_EXTRA_BASE: { struct stat st; CHECK(make_file(f.base, "unrecognized", "keep", &st) == 0); break; }
    case REC_ROOT_UNLINK_FAIL: seam.fail_unlink = "payload"; break;
    case REC_BASE_UNLINK_FAIL: seam.fail_unlink = f.loaded.name; break;
    case REC_PARENT_SYNC_FAIL: seam.fail_local_sync = true; break;
    case REC_FINAL_BASE_OWNER: seam.base_change_after_manifest = 1; break;
    case REC_FINAL_BASE_GROUP: seam.base_change_after_manifest = 2; break;
    case REC_FINAL_BASE_MODE: seam.base_change_after_manifest = 3; break;
    default: break;
    }
    CHECK(recovery_cli(&f, operation, scenario == REC_WRONG_ARGUMENT, &out) == 0);
    CHECK(!out.calls.unsafe);
    if (scenario == REC_EMPTY || scenario == REC_VIEWS || scenario == REC_LEGACY || scenario == REC_SAME_BOOT_WITH_VIEW) {
        CHECK(out.status == 0 && strstr(out.json, "\"cleanup_state\":\"removed\"") && strstr(out.json, "\"passed\":true"));
        CHECK(faccessat(f.local, f.loaded.name, F_OK, AT_SYMLINK_NOFOLLOW) < 0 && errno == ENOENT);
        CHECK(out.calls.unlinks == (scenario == REC_EMPTY ? 7U : 8U));
        CHECK(!out.calls.rejected && out.calls.local_syncs == 1);
    } else if (scenario == REC_PARENT_SYNC_FAIL) {
        CHECK(out.status == 5 && strstr(out.json, "\"passed\":false") && strstr(out.json, "cleanup_unknown"));
        CHECK(strstr(out.json, "\"stage\":\"recovery_parent_sync\"") && out.calls.local_syncs == 1);
        CHECK(faccessat(f.local, f.loaded.name, F_OK, AT_SYMLINK_NOFOLLOW) < 0 && errno == ENOENT);
        CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0 && out.status == 5 && !out.calls.unlinks);
        CHECK(strstr(out.json, "cleanup_unknown") && !strstr(out.json, "\"passed\":true"));
    } else {
        CHECK(out.status == 5 && strstr(out.json, "\"passed\":false") && strstr(out.json, "cleanup_unknown"));
        CHECK(faccessat(f.base, REMOVE_RECEIPT, F_OK, AT_SYMLINK_NOFOLLOW) == 0);
        bool finalized = scenario == REC_BASE_UNLINK_FAIL || scenario >= REC_FINAL_BASE_OWNER;
        if (!finalized) CHECK(entry_present(&f));
        if (scenario != REC_ROOT_UNLINK_FAIL && !finalized) CHECK(!out.calls.unlinks);
        CHECK(out.calls.local_syncs == 0);
        if (finalized) {
            CHECK(faccessat(f.base, "root", F_OK, AT_SYMLINK_NOFOLLOW) < 0 && errno == ENOENT);
            if (scenario == REC_BASE_UNLINK_FAIL) CHECK(out.calls.receipt_syncs >= 2 && out.calls.base_syncs >= 3);
            else {
                struct stat kept; CHECK(fstatat(f.base, REMOVE_RECEIPT, &kept, AT_SYMLINK_NOFOLLOW) == 0 &&
                    same_object(&kept, &receipt_stat));
                CHECK(out.calls.unlinks == 6 && out.calls.receipt_syncs == 1);
            }
            /* Missing root/manifest on a partial finalize must never count as a successful retry. */
            CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0 && out.status == 5 && !out.calls.unlinks);
            CHECK(strstr(out.json, "cleanup_unknown"));
        }
    }
done:
    if (receipt >= 0) close(receipt);
    if (view >= 0) close(view);
    if (fixture_close(&f) < 0) { fprintf(stderr, "Recovery fixture cleanup failed: %s errno=%d\n", test_name, errno); result = -1; }
    return result;
}

static int test_legacy_receipt_same_boot(void)
{
    const char *test_name = "legacy_receipt_same_boot_is_retryable";
    int result = 0, receipt_fd = -1; struct fixture f; struct cli_result out;
    CHECK(fixture_open(&f) == 0);
    f.header[0] = AJIU_MANIFEST_V2;
    CHECK(manifest_write(&f) == 0 && recovery_boot(&f, false) == 0);
    struct stat manifest;
    CHECK(fstatat(f.base, "manifest", &manifest, AT_SYMLINK_NOFOLLOW) == 0);
    struct removal_receipt receipt = { .magic = REMOVE_MAGIC, .identity = {
        f.base_stat.st_dev, f.base_stat.st_ino, f.root_stat.st_dev, f.root_stat.st_ino,
        f.views_stat.st_dev, f.views_stat.st_ino, manifest.st_dev, manifest.st_ino } };
    memcpy(receipt.instance, f.loaded.name + strlen(PREFIX), sizeof(receipt.instance));
    CHECK(pread(seam.boot, receipt.boot_id, 36, 0) == 36);
    receipt_fd = openat(f.base, REMOVE_RECEIPT, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    CHECK(receipt_fd >= 0 && fchmod(receipt_fd, 0600) == 0);
    CHECK(write_all(receipt_fd, &receipt, sizeof(receipt)) == 0 && fsync(receipt_fd) == 0 && fsync(f.base) == 0);
    struct stat receipt_stat;
    CHECK(fstat(receipt_fd, &receipt_stat) == 0 && remember(&receipt_stat) == 0);
    CHECK(close(receipt_fd) == 0); receipt_fd = -1;
    seam.enforce_receipt = true;
    CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0 && out.status == 0 &&
        strstr(out.json, "\"cleanup_state\":\"removed\"") && out.calls.unlinks == 7 && !out.calls.unsafe);
    CHECK(faccessat(f.local, f.loaded.name, F_OK, AT_SYMLINK_NOFOLLOW) < 0 && errno == ENOENT);
done:
    if (receipt_fd >= 0) close(receipt_fd);
    if (fixture_close(&f) < 0) { fprintf(stderr, "Legacy receipt fixture cleanup failed: %s errno=%d\n", test_name, errno); result = -1; }
    return result;
}

static int test_recovery_live_process(void)
{
    const char *test_name = "recovery_live_process_and_same_boot_retry";
    int result = 0, fds = -1; struct fixture f; struct cli_result out;
    CHECK(fixture_open(&f) == 0);
    f.header[0] = AJIU_MANIFEST_V2;
    CHECK(manifest_write(&f) == 0 && recovery_boot(&f, false) == 0);
    struct stat unrelated; CHECK(fstat(f.sandbox, &unrelated) == 0);
    CHECK(proc_set_reference(f.proc_self, "root", &f.root_stat) == 0);
    CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0 && out.status == 5 &&
        out.calls.unlinks == 0 && entry_present(&f));
    CHECK(proc_set_reference(f.proc_self, "root", &unrelated) == 0);
    CHECK(proc_set_reference(f.proc_thread, "root", &unrelated) == 0);
    CHECK(flock(f.base, LOCK_SH | LOCK_NB) == 0);
    CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0 && out.status == 5 &&
        strstr(out.json, "recovery_busy") && out.calls.unlinks == 0 && entry_present(&f));
    CHECK(flock(f.base, LOCK_UN) == 0);
    struct stat root_link;
    CHECK(fstatat(f.proc_thread, "root", &root_link, AT_SYMLINK_NOFOLLOW) == 0);
    node_for(&root_link)->replace_ref_on_follow = true;
    node_for(&root_link)->next_ref = f.root_stat;
    errno = ENOENT;
    CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0);
    CHECK(out.status == 5 && strstr(out.json, "recovery_busy") && !out.calls.unlinks);
    node_for(&root_link)->replace_ref_on_follow = false;
    node_for(&root_link)->ref_reads = 0;
    CHECK(proc_set_reference(f.proc_thread, "root", &f.root_stat) == 0);
    CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0);
    CHECK(out.status == 5 && strstr(out.json, "recovery_busy") && strstr(out.json, "cleanup_unknown"));
    CHECK(out.calls.unlinks == 0 && out.calls.process_scans == 1 && out.calls.reference_checks > 0);
    CHECK(entry_present(&f) && faccessat(f.base, REMOVE_RECEIPT, F_OK, AT_SYMLINK_NOFOLLOW) == 0);
    CHECK(proc_set_reference(f.proc_thread, "root", &unrelated) == 0);
    seam.no_ptrace = true;
    CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0 && out.status == 5 &&
        out.calls.unlinks == 0 && entry_present(&f));
    seam.no_ptrace = false; seam.deny_proc_task = true;
    CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0 && out.status == 5 &&
        out.calls.unlinks == 0 && entry_present(&f));
    seam.deny_proc_task = false;
    fds = openat(f.proc_thread, "fd", DIR_FLAGS); CHECK(fds >= 0);
    CHECK(proc_set_reference(fds, "0", &f.views_stat) == 0);
    CHECK(close(fds) == 0); fds = -1;
    CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0);
    CHECK(out.status == 5 && out.calls.unlinks == 0 && entry_present(&f));
    fds = openat(f.proc_thread, "fd", DIR_FLAGS); CHECK(fds >= 0);
    CHECK(proc_set_reference(fds, "0", &f.entries[1]) == 0);
    CHECK(close(fds) == 0); fds = -1;
    CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0 && out.status == 5 &&
        out.calls.unlinks == 0 && entry_present(&f));
    CHECK(proc_set_reference(f.proc_process, "exe", &f.entries[1]) == 0);
    CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0 && out.status == 5 &&
        out.calls.unlinks == 0 && entry_present(&f));
    CHECK(proc_set_reference(f.proc_process, "exe", &unrelated) == 0);
    fds = openat(f.proc_process, "map_files", DIR_FLAGS); CHECK(fds >= 0);
    CHECK(proc_set_reference(fds, "1000-2000", &f.entries[1]) == 0);
    CHECK(close(fds) == 0); fds = -1;
    CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0 && out.status == 5 &&
        out.calls.unlinks == 0 && entry_present(&f));
    fds = openat(f.proc_process, "map_files", DIR_FLAGS); CHECK(fds >= 0);
    CHECK(proc_set_reference(fds, "1000-2000", &unrelated) == 0);
    CHECK(close(fds) == 0); fds = -1;
    CHECK(proc_set_reference(f.proc_thread, "cwd", &unrelated) == 0);
    CHECK(proc_set_reference(f.proc_thread, "root", &unrelated) == 0);
    fds = openat(f.proc_thread, "fd", DIR_FLAGS); CHECK(fds >= 0);
    CHECK(proc_set_reference(fds, "0", &unrelated) == 0);
    CHECK(close(fds) == 0); fds = -1;
    seam.churn_proc = true;
    CHECK(recovery_cli(&f, "--remove", false, &out) == 0 && out.status == 0 &&
        strstr(out.json, "\"cleanup_state\":\"removed\"") && out.calls.unlinks > 0);
    CHECK(faccessat(f.local, f.loaded.name, F_OK, AT_SYMLINK_NOFOLLOW) < 0 && errno == ENOENT);
done:
    if (fds >= 0) close(fds);
    if (fixture_close(&f) < 0) result = -1;
    return result;
}

static int test_recovery_first_phase_rejection(void)
{
    for (int scenario = 0; scenario < 9; ++scenario) {
        const char *test_name = "recovery_first_phase_rejection";
        int result = 0, view = -1; struct fixture f; struct cli_result out;
        CHECK(fixture_open(&f) == 0); f.header[0] = AJIU_MANIFEST_V2;
        CHECK(manifest_write(&f) == 0 && recovery_boot(&f, false) == 0);
        const char *name = scenario == 0 ? "non-nonce" : "abcdef0123456789abcdef01";
        if (scenario == 1) CHECK(symlinkat("/foreign", f.views, name) == 0);
        else { view = mkdir_open(f.views, name, 0700); CHECK(view >= 0); }
        if (scenario == 2) node_fd(view)->mount_id = CURRENT_MOUNT + 1;
        if (scenario == 3) { struct stat st; CHECK(make_file(view, "unproven", "keep", &st) == 0); }
        if (scenario == 4) node_fd(view)->gid = 10000;
        if (scenario == 5) CHECK(fchmod(view, 0755) == 0);
        if (scenario == 6) CHECK(pwrite(seam.boot, "invalid", 7, 0) == 7);
        if (scenario == 8) {
            for (unsigned int i = 0; i < MAX_RECOVERY_VIEWS; ++i) {
                char nonce[25]; CHECK(snprintf(nonce, sizeof(nonce), "%024x", i) == 24);
                int extra = mkdir_open(f.views, nonce, 0700); CHECK(extra >= 0 && close(extra) == 0);
            }
        }
        CHECK(recovery_cli(&f, "--recover-remove", scenario == 7, &out) == 0);
        CHECK(out.status == 5 && !out.calls.unlinks && entry_present(&f));
        if (scenario == 8) {
            char error[32]; CHECK(snprintf(error, sizeof(error), "\"errno\":%d", EOVERFLOW) > 0 && strstr(out.json, error));
        }
        if (scenario == 6 || scenario == 7)
            CHECK(faccessat(f.base, REMOVE_RECEIPT, F_OK, AT_SYMLINK_NOFOLLOW) < 0 && errno == ENOENT);
        else
            CHECK(faccessat(f.base, REMOVE_RECEIPT, F_OK, AT_SYMLINK_NOFOLLOW) == 0);
    done:
        if (view >= 0) close(view);
        if (fixture_close(&f) < 0) result = -1;
        if (result) return -1;
    }
    return 0;
}

static int test_boot_source(void)
{
    enum { BOOT_NORMAL, BOOT_FOREIGN_PROCFS_LEAF, BOOT_FOREIGN_PARENT, BOOT_WRONG_FS, BOOT_NOT_DIRECTORY,
        BOOT_SYMLINK, BOOT_UNKNOWN_MOUNT, BOOT_PROC_OWNER, BOOT_PROC_MODE, BOOT_FILE_OWNER, BOOT_SOURCE_CASES };
    for (int scenario = 0; scenario < BOOT_SOURCE_CASES; ++scenario) {
        const char *test_name = "boot_source_mount_boundary";
        int result = 0; struct fixture f; struct cli_result out;
        CHECK(fixture_open(&f) == 0); f.header[0] = AJIU_MANIFEST_V2;
        CHECK(manifest_write(&f) == 0 && recovery_boot(&f, false) == 0);
        CHECK(node_fd(seam.proc)->mount_id != node_fd(seam.root)->mount_id);
        switch (scenario) {
        case BOOT_FOREIGN_PROCFS_LEAF: node_fd(seam.boot)->mount_id = PROC_MOUNT + 1; break;
        case BOOT_FOREIGN_PARENT: seam.boot_parent_submount = true; break;
        case BOOT_WRONG_FS: seam.proc_wrong_fs = true; break;
        case BOOT_NOT_DIRECTORY: {
            struct stat st;
            CHECK(renameat(f.sandbox, "proc", f.sandbox, "saved-proc") == 0);
            CHECK(make_file(f.sandbox, "proc", "foreign", &st) == 0); break;
        }
        case BOOT_SYMLINK:
            CHECK(renameat(f.sandbox, "proc", f.sandbox, "saved-proc") == 0);
            CHECK(symlinkat("saved-proc", f.sandbox, "proc") == 0); break;
        case BOOT_UNKNOWN_MOUNT: node_fd(seam.proc)->mount_id = 0; break;
        case BOOT_PROC_OWNER: node_fd(seam.proc)->uid = 10000; break;
        case BOOT_PROC_MODE: CHECK(fchmod(seam.proc, 0777) == 0); break;
        case BOOT_FILE_OWNER: node_fd(seam.boot)->uid = 10000; break;
        default: break;
        }
        const char *operation = scenario == BOOT_NORMAL ? "--remove" : "--recover-remove";
        CHECK(recovery_cli(&f, operation, false, &out) == 0);
        CHECK(!out.calls.rejected);
        if (scenario == BOOT_NORMAL) {
            CHECK(out.status == 0 && strstr(out.json, "\"cleanup_state\":\"removed\"") &&
                out.calls.boot_opens == 1 && !out.calls.boot_mount_rejections && out.calls.process_scans == 2);
            CHECK(faccessat(f.local, f.loaded.name, F_OK, AT_SYMLINK_NOFOLLOW) < 0 && errno == ENOENT);
        } else {
            CHECK(!out.calls.unlinks && entry_present(&f));
            CHECK(out.status == 5 && strstr(out.json, "\"stage\":\"recovery_boot_id\"") && strstr(out.json, "cleanup_unknown"));
            CHECK(faccessat(f.base, REMOVE_RECEIPT, F_OK, AT_SYMLINK_NOFOLLOW) < 0 && errno == ENOENT);
            if (scenario == BOOT_FOREIGN_PROCFS_LEAF || scenario == BOOT_FOREIGN_PARENT)
                CHECK(out.calls.boot_opens == 1 && out.calls.boot_mount_rejections == 1);
        }
    done:
        if (fixture_close(&f) < 0) result = -1;
        if (result) return -1;
    }
    puts("Boot source: validated pinned procfs and relative BENEATH/NO_XDEV; foreign procfs leaf or parent mount never creates a receipt or permits same-boot deletion.");
    return 0;
}

static int test_partial_internal_retry(void)
{
    for (unsigned int version = 1; version <= 2; ++version) {
        const char *test_name = version == 1 ? "v1_partial_internal_retry_refused" : "v2_partial_internal_retry_rescans";
        int result = 0; struct fixture f; struct cli_result out;
        CHECK(fixture_open(&f) == 0); f.header[0] = version == 1 ? AJIU_MANIFEST_V1 : AJIU_MANIFEST_V2;
        CHECK(manifest_write(&f) == 0 && recovery_boot(&f, false) == 0);
        seam.enforce_receipt = true;
        struct stat receipt, kept;
        seam.fail_unlink = "etc"; /* payload is already removed when its parent rmdir fails. */
        CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0 && out.status == 5 && strstr(out.json, "cleanup_unknown"));
        CHECK(fstatat(f.base, REMOVE_RECEIPT, &receipt, AT_SYMLINK_NOFOLLOW) == 0);
        CHECK(out.calls.unlinks == 2 && !out.calls.unsafe);
        CHECK(faccessat(f.root, "etc/payload", F_OK, AT_SYMLINK_NOFOLLOW) < 0 && errno == ENOENT);
        CHECK(fstatat(f.root, "etc", &kept, AT_SYMLINK_NOFOLLOW) == 0 && same_object(&kept, &f.entries[0]));
        CHECK(fstatat(f.base, REMOVE_RECEIPT, &kept, AT_SYMLINK_NOFOLLOW) == 0 && same_object(&kept, &receipt));
        CHECK(faccessat(f.base, "root", F_OK, AT_SYMLINK_NOFOLLOW) == 0 &&
            faccessat(f.base, "views", F_OK, AT_SYMLINK_NOFOLLOW) == 0 && faccessat(f.base, "manifest", F_OK, AT_SYMLINK_NOFOLLOW) == 0);
        seam.fail_unlink = NULL;
        CHECK(recovery_cli(&f, "--recover-remove", false, &out) == 0 && !out.calls.unsafe);
        if (version == 1) {
            CHECK(out.status == 5 && !out.calls.unlinks && strstr(out.json, "cleanup_unknown"));
            CHECK(fstatat(f.base, REMOVE_RECEIPT, &kept, AT_SYMLINK_NOFOLLOW) == 0 && same_object(&kept, &receipt));
            CHECK(fstatat(f.root, "etc", &kept, AT_SYMLINK_NOFOLLOW) == 0 && same_object(&kept, &f.entries[0]));
        } else {
            CHECK(out.status == 0 && strstr(out.json, "\"cleanup_state\":\"removed\"") && out.calls.local_syncs == 1);
            CHECK(faccessat(f.local, f.loaded.name, F_OK, AT_SYMLINK_NOFOLLOW) < 0 && errno == ENOENT);
        }
        printf("Partial same-boot deletion V%u: payload removed, original receipt retained on failure; retry %s.\n",
            version, version == 1 ? "refuses missing immutable-ledger entry" : "rescans remaining mutable tree and completes");
    done:
        if (fixture_close(&f) < 0) result = -1;
        if (result) return -1;
    }
    return 0;
}

static int test_incomplete_install_rollback(void)
{
    const char *test_name = "incomplete_install_without_base_identity";
    int result = 0;
    struct fixture f; CHECK(fixture_open(&f) == 0);
    f.loaded.base_created = true;
    CHECK(f.loaded.base == -1 && !f.loaded.base_known);
    errno = 0;
    CHECK(rollback(&f.loaded) == -1 && errno == ESTALE);
    CHECK(!seam.unlink_calls && !seam.rejected_calls && entry_present(&f));
    f.loaded.base = fcntl(f.base, F_DUPFD_CLOEXEC, 3); CHECK(f.loaded.base >= 0);
    errno = 0;
    CHECK(rollback(&f.loaded) == -1 && errno == ESTALE);
    CHECK(!seam.unlink_calls && !seam.rejected_calls && entry_present(&f));
    /* Possessing a descriptor alone cannot authorize deletion without its checked identity. */
    CHECK(!f.loaded.base_known);
 done:
    if (fixture_close(&f) < 0) result = -1;
    return result;
}

static int test_recover_remove(void)
{
    for (int i = 0; i < REC_CASES; ++i) if (test_recovery_case((enum recovery_case)i) < 0) return -1;
    if (test_recovery_first_phase_rejection() < 0 || test_legacy_receipt_same_boot() < 0 ||
        test_recovery_live_process() < 0 || test_incomplete_install_rollback() < 0 ||
        test_boot_source() < 0 || test_partial_internal_retry() < 0 || test_removal_scan_budget() < 0 ||
        test_removal_proc_churn() < 0 || test_removal_proc_guards() < 0 || test_removal_stat_guards() < 0 ||
        test_removal_process_finish() < 0) return -1;
    puts("Recovery coverage: same-boot receipt retries after lock release; old receipt identities, active process references, lock contention, proc mount boundaries, view replacement/budgets, finalization failures and strict missing-identity retries.");
    return 0;
}

int main(int argc, char **argv)
{
    if (argc == 2) {
        int rc;
        if (!strcmp(argv[1], "removalScanBudget")) rc = test_removal_scan_budget();
        else if (!strcmp(argv[1], "removalProcChurn")) rc = test_removal_proc_churn();
        else if (!strcmp(argv[1], "removalProcessFinish")) rc = test_removal_process_finish();
        else if (!strcmp(argv[1], "removalProcGuards")) rc = test_removal_proc_guards() || test_removal_stat_guards();
        else if (!strcmp(argv[1], "recoverRemove")) rc = test_recover_remove();
        else if (!strcmp(argv[1], "bootSource")) rc = test_boot_source();
        else if (!strcmp(argv[1], "partialInternalRetry")) rc = test_partial_internal_retry();
        else if (!strcmp(argv[1], "reviewRegressionNamedAnchorMount")) rc = test_review_regression_named_anchor_mount();
        else if (!strcmp(argv[1], "mutableRemoveV2")) rc = test_mutable_remove_v2();
        else if (!strcmp(argv[1], "archivePermissionControls")) rc = test_archive_permission_controls();
        else if (!strcmp(argv[1], "defaultAllowRootGuestArchivePermissions"))
            rc = test_archive_permission_controls() || test_default_allow_root_guest_archive_permissions();
        else { fputs("Unknown focused test name.\n", stderr); return 2; }
        if (!rc) printf("Installer focused checks passed: %s, %d assertions; local fixtures and synthetic privilege/mount/boot inputs only.\n", argv[1], checks);
        return rc ? 1 : 0;
    }
    if (argc != 1) { fputs("Expected no arguments or one focused test name.\n", stderr); return 2; }
    for (int test = 0; test < TEST_CASE_COUNT; ++test)
        if (run_case((enum test_case)test) < 0) return 1;
    if (test_archive_permission_controls() < 0 || test_default_allow_root_guest_archive_permissions() < 0 ||
        test_mutable_remove_v2() < 0 || test_review_regression_named_anchor_mount() < 0 || test_recover_remove() < 0) return 1;
    printf("Installer load/rollback and archive checks passed: %d load/rollback cases, %d assertions.\n", TEST_CASE_COUNT, checks);
    puts("Mocked: Root query/registered ownership/link counts, synthetic root/openat2 resolution, current/submount statx IDs and procfs boot_id; injected write/fsync/unlink failures.");
    puts("Real: owned-temp openat/fstat/read/write/fsync/readdir/unlinkat, CLI main in forked children with checked exit status and JSON. No host/device, chroot, mount or real reboot; kernel mount and reboot durability semantics not proved.");
    return 0;
}
