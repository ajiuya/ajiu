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
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

struct test_node {
    dev_t dev;
    ino_t ino;
    uid_t uid;
    gid_t gid;
    uint64_t mount_id;
};
struct test_seam {
    int root;
    struct test_node nodes[64];
    size_t count, root_open_calls, openat2_calls, statx_calls, unlink_calls, rejected_calls;
    uid_t uid, euid;
    gid_t gid, egid;
    bool replace_payload_on_named_stat;
    bool named_mount_changed;
    dev_t named_dev; ino_t named_ino;
    uint64_t named_mount, named_fd_mounts[1024];
    size_t named_mount_reads;
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
static uid_t synthetic_getuid(void) { return seam.uid; }
static uid_t synthetic_geteuid(void) { return seam.euid; }
static gid_t synthetic_getgid(void) { return seam.gid; }
static gid_t synthetic_getegid(void) { return seam.egid; }

/* Only production calls use these seams; no production main, setup, chroot or mount is run. */
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

#define CHECK(condition) do { \
    ++checks; \
    if (!(condition)) { \
        fprintf(stderr, "Installer check %s line %d: %s (errno=%d)\n", test_name, __LINE__, #condition, errno); \
        result = -1; goto done; \
    } \
} while (0)
#define CURRENT_MOUNT UINT64_C(9001)
#define OLD_MOUNT UINT64_C(41)

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
    seam.nodes[seam.count++] = (struct test_node){ st->st_dev, st->st_ino, 0, 0, CURRENT_MOUNT };
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
    int fd = openat(parent, name, flags, mode);
    remember_named_open(parent, name, fd);
    return fd;
}

static int synthetic_fstat(int fd, struct stat *st)
{
    if (fstat(fd, st) < 0) return -1;
    struct test_node *node = node_for(st);
    if (!node) { ++seam.rejected_calls; errno = EPERM; return -1; }
    st->st_uid = node->uid; st->st_gid = node->gid;
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
    if (fstatat(fd, path, st, flags) < 0) return -1;
    struct test_node *node = node_for(st);
    if (!node) { ++seam.rejected_calls; errno = EPERM; return -1; }
    st->st_uid = node->uid; st->st_gid = node->gid;
    return 0;
}

/* Explicit synthetic IN_ROOT: canonical relative components, real openat with O_NOFOLLOW. */
static int synthetic_openat2(int fd, const char *path, const struct open_how *how, size_t size)
{
    ++seam.openat2_calls;
    const uint64_t resolve = RESOLVE_IN_ROOT | RESOLVE_NO_MAGICLINKS | RESOLVE_NO_SYMLINKS;
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
    if (number == SYS_openat2) {
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
    if (seam.named_mount_changed) {
        struct stat named;
        if (fstatat(fd, path, &named, AT_SYMLINK_NOFOLLOW) == 0 &&
            named.st_dev == seam.named_dev && named.st_ino == seam.named_ino) { errno = EBUSY; return -1; }
    }
    return unlinkat(fd, path, flags);
}

struct fixture {
    char temp[64];
    int sandbox, data, local, base, root, views;
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
    f->sandbox = f->data = f->local = f->base = f->root = f->views = -1;
    f->loaded = (struct install){ .local = -1, .base = -1, .root = -1, .views = -1, .manifest = -1 };
    memset(&seam, 0, sizeof(seam)); seam.root = -1;
    (void)snprintf(f->temp, sizeof(f->temp), "/tmp/ajiu-rootfs-install-test-XXXXXX");
    if (!mkdtemp(f->temp)) return -1;
    f->sandbox = open(f->temp, DIR_FLAGS);
    struct stat st;
    if (f->sandbox < 0 || fstat(f->sandbox, &st) < 0 || remember(&st) < 0) return -1;
    seam.root = f->sandbox;
    f->data = mkdir_open(f->sandbox, "data", 0700);
    f->local = f->data < 0 ? -1 : mkdir_open(f->data, "local", 0700);
    (void)snprintf(f->loaded.name, sizeof(f->loaded.name), "%s%s", PREFIX, "0123456789abcdef01234567");
    f->base = f->local < 0 ? -1 : mkdir_open(f->local, f->loaded.name, 0700);
    f->root = f->base < 0 ? -1 : mkdir_open(f->base, "root", 0755);
    f->views = f->base < 0 ? -1 : mkdir_open(f->base, "views", 0700);
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
    int fds[] = { f->loaded.local, f->loaded.base, f->loaded.root, f->loaded.views, f->loaded.manifest,
                  f->data, f->local, f->base, f->root, f->views };
    for (size_t i = 0; i < sizeof(fds) / sizeof(*fds); ++i) if (fds[i] >= 0) close(fds[i]);
    int rc = 0;
    if (f->sandbox >= 0) {
        struct stat path, held;
        if (lstat(f->temp, &path) < 0 || fstat(f->sandbox, &held) < 0 || !same_object(&path, &held) ||
            !S_ISDIR(path.st_mode) || remove_owned_contents(f->sandbox, 0) < 0) rc = -1;
        close(f->sandbox);
        if (!rc && rmdir(f->temp) < 0) rc = -1;
    } else if (*f->temp && rmdir(f->temp) < 0 && errno != ENOENT) rc = -1;
    seam.root = -1;
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
        char target[128]; CHECK(snprintf(target, sizeof(target), "%s/foreign", f.temp) > 0);
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

int main(int argc, char **argv)
{
    if (argc == 2) {
        int rc;
        if (!strcmp(argv[1], "reviewRegressionNamedAnchorMount")) rc = test_review_regression_named_anchor_mount();
        else if (!strcmp(argv[1], "mutableRemoveV2")) rc = test_mutable_remove_v2();
        else if (!strcmp(argv[1], "archivePermissionControls")) rc = test_archive_permission_controls();
        else if (!strcmp(argv[1], "defaultAllowRootGuestArchivePermissions"))
            rc = test_archive_permission_controls() || test_default_allow_root_guest_archive_permissions();
        else { fputs("Unknown focused test name.\n", stderr); return 2; }
        if (!rc) printf("Installer focused checks passed: %s, %d assertions; existing parse_stream validate mode with synthetic memory input only.\n", argv[1], checks);
        return rc ? 1 : 0;
    }
    if (argc != 1) { fputs("Expected no arguments or one focused test name.\n", stderr); return 2; }
    for (int test = 0; test < TEST_CASE_COUNT; ++test)
        if (run_case((enum test_case)test) < 0) return 1;
    if (test_archive_permission_controls() < 0 || test_default_allow_root_guest_archive_permissions() < 0 ||
        test_mutable_remove_v2() < 0 || test_review_regression_named_anchor_mount() < 0) return 1;
    printf("Installer load/rollback and archive checks passed: %d load/rollback cases, %d assertions.\n", TEST_CASE_COUNT, checks);
    puts("Mocked: Root query/registered ownership, synthetic root/openat2 resolution, current/submount statx IDs; parse_stream validate mode reads synthetic memory input.");
    puts("Real: owned-temp openat/fstat/read/write/readdir/unlinkat. No production main, Root, chroot or mount; kernel openat2/statx semantics not tested.");
    return 0;
}
