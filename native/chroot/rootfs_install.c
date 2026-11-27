#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "rootfs_manifest.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/capability.h>
#include <linux/magic.h>
#include <linux/openat2.h>
#include <linux/stat.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/vfs.h>
#include <time.h>
#include <unistd.h>

#define MAX_ENTRIES 65536U
#define MAX_FILE (UINT64_C(128) * 1024 * 1024)
#define MAX_TOTAL (UINT64_C(512) * 1024 * 1024)
#define MAX_NAMES (UINT64_C(16) * 1024 * 1024)
#define HASH_SIZE (MAX_ENTRIES * 2U)
#define PREFIX "ajiu-chroot-rootfs-"
#define DIR_FLAGS (O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
/* Presence alone also blocks runtime_helper, even if this file is incomplete. */
#define REMOVE_RECEIPT "remove-after-reboot"
#define REMOVE_MAGIC UINT64_C(0x414a525200000001)
#define MAX_RECOVERY_VIEWS 4096U
#define MAX_PROC_TASKS 65536U
#define MAX_PROC_ENTRIES 1048576U
#define RECOVERY_STOP_WAIT 7500

struct removal_receipt {
    uint64_t magic, identity[8]; /* base, root, views, manifest: dev/ino pairs */
    char instance[24], boot_id[36];
    uint32_t reserved;
};
_Static_assert(sizeof(struct removal_receipt) == 136, "bounded receipt layout");

struct entry {
    char *path;
    unsigned char kind;
    bool created;
    dev_t dev;
    ino_t ino;
    uint64_t mount_id;
};
struct proc_identity;
struct install {
    struct entry *entries;
    uint32_t *index;
    uint32_t count;
    uint64_t bytes, names;
    int local, base, root, views, manifest, receipt;
    char name[sizeof(PREFIX) + 24];
    struct stat base_stat, root_stat, views_stat;
    uint64_t mount_id;
    bool base_created, root_created, views_created, base_known, root_known, views_known, validate, mutable_tree;
    bool recover_remove, recovery_ready, removal_locked;
    struct removal_receipt receipt_data;
    struct stat receipt_stat;
    struct proc_identity *proc_targets;
    size_t proc_target_count;
};
static const char *stage = "arguments";
static volatile sig_atomic_t timed_out;
static int64_t deadline;

static int64_t monotonic_ms(void)
{
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) < 0) return -1;
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static void on_timeout(int signal_number)
{
    (void)signal_number;
    timed_out = 1;
}

static int time_remaining(void)
{
    int64_t now = monotonic_ms();
    if (timed_out || now < 0 || now >= deadline) { errno = ETIMEDOUT; return -1; }
    return (int)(deadline - now);
}

static ssize_t input_read(void *buffer, size_t size)
{
    for (;;) {
        int remaining = time_remaining();
        if (remaining < 0) return -1;
        struct pollfd p = { .fd = STDIN_FILENO, .events = POLLIN | POLLHUP };
        int rc = poll(&p, 1, remaining);
        if (rc < 0 && errno == EINTR) continue;
        if (rc < 0) return -1;
        if (rc == 0) { errno = ETIMEDOUT; return -1; }
        ssize_t n = read(STDIN_FILENO, buffer, size);
        if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        return n;
    }
}

static uint32_t be32(const unsigned char *p)
{
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}

static uint64_t be64(const unsigned char *p)
{
    return ((uint64_t)be32(p) << 32) | be32(p + 4);
}

static int exact_read(void *buffer, size_t size)
{
    unsigned char *p = buffer;
    while (size != 0) {
        ssize_t n = input_read(p, size);
        if (n <= 0) { if (n == 0) errno = EPROTO; return -1; }
        p += n;
        size -= (size_t)n;
    }
    return 0;
}

static bool utf8(const unsigned char *p, size_t size)
{
    for (size_t i = 0; i < size;) {
        uint32_t value = p[i++];
        unsigned int more;
        uint32_t minimum;
        if (value == 0) return false;
        if (value < 0x80) continue;
        if (value >= 0xc2 && value <= 0xdf) { more = 1; minimum = 0x80; value &= 0x1f; }
        else if (value >= 0xe0 && value <= 0xef) { more = 2; minimum = 0x800; value &= 0x0f; }
        else if (value >= 0xf0 && value <= 0xf4) { more = 3; minimum = 0x10000; value &= 7; }
        else return false;
        if (i + more > size) return false;
        while (more--) {
            if ((p[i] & 0xc0) != 0x80) return false;
            value = (value << 6) | (p[i++] & 0x3f);
        }
        if (value < minimum || value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff)) return false;
    }
    return true;
}

static bool safe_path(const char *path)
{
    if (*path == '\0' || *path == '/') return false;
    for (const char *p = path; *p;) {
        const char *end = strchr(p, '/');
        size_t n = end ? (size_t)(end - p) : strlen(p);
        if (n == 0 || n > 255 || (n == 1 && p[0] == '.') || (n == 2 && p[0] == '.' && p[1] == '.')) return false;
        if (!end) return true;
        p = end + 1;
        if (*p == '\0') return false;
    }
    return false;
}

static uint32_t slot(struct install *s, const char *path)
{
    uint32_t hash = 2166136261U;
    for (const unsigned char *p = (const unsigned char *)path; *p; ++p) hash = (hash ^ *p) * 16777619U;
    hash &= HASH_SIZE - 1;
    while (s->index[hash] && strcmp(s->entries[s->index[hash] - 1].path, path)) hash = (hash + 1) & (HASH_SIZE - 1);
    return hash;
}

static int at_root(int root, const char *path, int flags)
{
    struct open_how how = { .flags = (uint64_t)(flags | O_CLOEXEC | O_NOFOLLOW),
        .resolve = RESOLVE_IN_ROOT | RESOLVE_NO_MAGICLINKS | RESOLVE_NO_SYMLINKS };
    return (int)syscall(SYS_openat2, root, path, &how, sizeof(how));
}

static int mount_identity(int fd, uint64_t *id)
{
    struct statx sx;
    if (syscall(SYS_statx, fd, "", AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW, STATX_MNT_ID, &sx) < 0 ||
        !(sx.stx_mask & STATX_MNT_ID)) return -1;
    *id = sx.stx_mnt_id;
    return 0;
}

static int exact_write(int fd, const void *buffer, size_t size)
{
    const unsigned char *p = buffer;
    while (size) {
        if (time_remaining() < 0) return -1;
        ssize_t n = write(fd, p, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { if (n == 0) errno = EIO; return -1; }
        p += n;
        size -= (size_t)n;
    }
    return 0;
}

static int record_created(struct install *s, const struct entry *e)
{
    uint64_t header[5] = { e->kind, strlen(e->path), e->dev, e->ino, e->mount_id };
    return exact_write(s->manifest, header, sizeof(header)) < 0 ||
           exact_write(s->manifest, e->path, (size_t)header[1]) < 0 ? -1 : 0;
}

static int parent_open(struct install *s, const char *path, const char **leaf)
{
    const char *slash = strrchr(path, '/');
    *leaf = slash ? slash + 1 : path;
    if (!slash) return fcntl(s->root, F_DUPFD_CLOEXEC, 3);
    char parent[4097];
    size_t n = (size_t)(slash - path);
    memcpy(parent, path, n);
    parent[n] = 0;
    return at_root(s->root, parent, DIR_FLAGS);
}

static int setup(struct install *s)
{
    stage = "protected_parent";
    if (getuid() != 0 || geteuid() != 0 || getgid() != 0 || getegid() != 0) { errno = EPERM; return -1; }
    int parent = open("/", DIR_FLAGS);
    if (parent < 0) return -1;
    const char *parts[2] = { "data", "local" };
    for (int i = 0; i < 3; ++i) {
        struct stat st;
        if (fstat(parent, &st) < 0 || !S_ISDIR(st.st_mode) || (st.st_uid != 0 && st.st_uid != 1000) ||
            (st.st_mode & S_IWOTH) || ((st.st_mode & S_IWGRP) && st.st_gid != 0 && st.st_gid != 1000)) {
            close(parent); errno = EPERM; return -1;
        }
        if (i == 2) break;
        int next = openat(parent, parts[i], DIR_FLAGS);
        int saved = errno;
        close(parent);
        if (next < 0) { errno = saved; return -1; }
        parent = next;
    }
    s->local = parent;
    stage = "exclusive_instance";
    if (mkdirat(parent, s->name, 0700) < 0) return -1;
    s->base_created = true;
    s->base = openat(parent, s->name, DIR_FLAGS);
    if (s->base < 0 || fstat(s->base, &s->base_stat) < 0 || s->base_stat.st_uid != 0 ||
        (s->base_stat.st_mode & 07777) != 0700 || mount_identity(s->base, &s->mount_id) < 0) return -1;
    s->base_known = true;
    stage = "exclusive_root";
    if (mkdirat(s->base, "root", 0700) < 0) return -1;
    s->root_created = true;
    s->root = openat(s->base, "root", DIR_FLAGS);
    if (s->root < 0 || fstat(s->root, &s->root_stat) < 0 || s->root_stat.st_uid != 0) return -1;
    s->root_known = true;
    stage = "exclusive_views";
    if (mkdirat(s->base, "views", 0700) < 0) return -1;
    s->views_created = true;
    s->views = openat(s->base, "views", DIR_FLAGS);
    if (s->views < 0 || fstat(s->views, &s->views_stat) < 0 || s->views_stat.st_uid != 0 ||
        s->views_stat.st_gid != 0 || (s->views_stat.st_mode & 07777) != 0700) return -1;
    s->views_known = true;
    stage = "manifest_create";
    s->manifest = openat(s->base, "manifest", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (s->manifest < 0) return -1;
    uint64_t header[6] = { AJIU_MANIFEST_V2, s->root_stat.st_dev, s->root_stat.st_ino,
                          s->views_stat.st_dev, s->views_stat.st_ino, s->mount_id };
    return exact_write(s->manifest, header, sizeof(header));
}

static int create_entry(struct install *s, struct entry *e, uint32_t mode, uint32_t uid, uint32_t gid,
                         const char *link, int *file)
{
    stage = "entry_create";
    const char *leaf;
    int parent = parent_open(s, e->path, &leaf);
    if (parent < 0) return -1;
    int rc = 0, fd = -1;
    if (e->kind == 1) rc = mkdirat(parent, leaf, 0700);
    else if (e->kind == 2) fd = openat(parent, leaf, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    else if (e->kind == 3) rc = symlinkat(link, parent, leaf);
    else {
        int target = at_root(s->root, link, O_PATH);
        struct stat st;
        struct entry *previous = &s->entries[s->index[slot(s, link)] - 1];
        if (target < 0 || fstat(target, &st) < 0 || !S_ISREG(st.st_mode) ||
            st.st_dev != previous->dev || st.st_ino != previous->ino) rc = -1;
        else rc = linkat(target, "", parent, leaf, AT_EMPTY_PATH);
        if (target >= 0) close(target);
    }
    if (e->kind == 2 && fd < 0) rc = -1;
    int saved = errno;
    if (rc == 0) {
        int anchor = fd >= 0 ? fd : openat(parent, leaf, O_PATH | O_NOFOLLOW | O_CLOEXEC);
        struct stat st;
        if (anchor < 0 || fstat(anchor, &st) < 0 || mount_identity(anchor, &e->mount_id) < 0) rc = -1;
        else { e->created = true; e->dev = st.st_dev; e->ino = st.st_ino; }
        saved = errno;
        if (anchor >= 0 && anchor != fd) close(anchor);
    }
    if (rc == 0 && record_created(s, e) < 0) { rc = -1; saved = errno; }
    if (rc == 0 && e->kind != 2 && e->kind != 4) {
        rc = fchownat(parent, leaf, uid, gid, AT_SYMLINK_NOFOLLOW);
        if (rc == 0 && e->kind == 1) rc = fchmodat(parent, leaf, mode, 0);
        saved = errno;
    }
    close(parent);
    if (rc < 0 && fd >= 0) close(fd);
    *file = rc == 0 ? fd : -1;
    errno = saved;
    return rc;
}

static bool same_object(const struct stat *a, const struct stat *b)
{
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino && (a->st_mode & S_IFMT) == (b->st_mode & S_IFMT);
}

static int instance_current(struct install *s)
{
    struct stat st;
    uint64_t id;
    if (!s->base_known || fstatat(s->local, s->name, &st, AT_SYMLINK_NOFOLLOW) < 0 ||
        !same_object(&st, &s->base_stat) || st.st_uid != 0 || (st.st_mode & 07777) != 0700 ||
        mount_identity(s->base, &id) < 0 || id != s->mount_id) { errno = ESTALE; return -1; }
    if (s->root_created && (!s->root_known || fstatat(s->base, "root", &st, AT_SYMLINK_NOFOLLOW) < 0 ||
        !same_object(&st, &s->root_stat) || st.st_uid != 0 || mount_identity(s->root, &id) < 0 ||
        id != s->mount_id)) { errno = ESTALE; return -1; }
    return 0;
}

static int views_empty(struct install *s)
{
    struct stat current;
    uint64_t id;
    if (!s->views_created) return 0;
    if (!s->views_known || fstatat(s->base, "views", &current, AT_SYMLINK_NOFOLLOW) < 0 ||
        !same_object(&current, &s->views_stat) || current.st_uid != 0 || current.st_gid != 0 ||
        (current.st_mode & 07777) != 0700 || mount_identity(s->views, &id) < 0 || id != s->mount_id) {
        errno = ESTALE; return -1;
    }
    int fd = openat(s->views, ".", DIR_FLAGS);
    if (fd < 0) return -1;
    DIR *dir = fdopendir(fd);
    if (!dir) { close(fd); return -1; }
    int error = 0;
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(dir);
        if (!entry) { error = errno; break; }
        if (strcmp(entry->d_name, ".") && strcmp(entry->d_name, "..")) { error = EBUSY; break; }
    }
    if (closedir(dir) < 0 && !error) error = errno;
    if (error) { errno = error; return -1; }
    return 0;
}

static int remove_mutable_tree(struct install *s);
static int recovery_gate(struct install *s);
static int finish_base(struct install *s);
static int installed_anchors_current(struct install *s, bool empty_views);

static int receipt_absent(int base)
{
    struct stat st;
    if (fstatat(base, REMOVE_RECEIPT, &st, AT_SYMLINK_NOFOLLOW) == 0) { errno = EBUSY; return -1; }
    return errno == ENOENT ? 0 : -1;
}

static int rollback(struct install *s)
{
    /* A failed installation still rolls back exactly its created-inode ledger. An installed guest
     * is mutable: apt/editor replacements are not foreign outer instance anchors. */
    /* setup may have created base but failed before opening/identifying it.
     * Leave that case to instance_current's ESTALE refusal, without querying fd -1. */
    if (s->recover_remove ? recovery_gate(s) < 0 : s->base_known && receipt_absent(s->base) < 0) return -1;
    if (s->mutable_tree) return remove_mutable_tree(s);
    if (instance_current(s) < 0 || views_empty(s) < 0 ||
        (s->recover_remove && installed_anchors_current(s, true) < 0)) return -1;
    for (uint32_t i = s->count; i > 0; --i) {
        if (time_remaining() < 0 || (s->recover_remove ?
            recovery_gate(s) < 0 || installed_anchors_current(s, true) < 0 : receipt_absent(s->base) < 0)) return -1;
        struct entry *e = &s->entries[i - 1];
        if (!e->created) continue;
        const char *leaf;
        int parent = parent_open(s, e->path, &leaf);
        if (parent < 0) return -1;
        int fd = openat(parent, leaf, O_PATH | O_NOFOLLOW | O_CLOEXEC);
        struct stat st;
        uint64_t id;
        bool valid = fd >= 0 && fstat(fd, &st) == 0 && st.st_dev == e->dev && st.st_ino == e->ino &&
                     mount_identity(fd, &id) == 0 && id == e->mount_id && id == s->mount_id;
        if (fd >= 0) close(fd);
        int rc = valid ? unlinkat(parent, leaf, e->kind == 1 ? AT_REMOVEDIR : 0) : -1;
        int saved = valid ? errno : ESTALE;
        close(parent);
        if (rc < 0) { errno = saved; return -1; }
    }
    if (instance_current(s) < 0 || views_empty(s) < 0 || (s->recover_remove ?
        recovery_gate(s) < 0 || installed_anchors_current(s, true) < 0 : receipt_absent(s->base) < 0)) return -1;
    if (s->views_created && unlinkat(s->base, "views", AT_REMOVEDIR) < 0) return -1;
    if (s->root_created && unlinkat(s->base, "root", AT_REMOVEDIR) < 0) return -1;
    if (s->manifest >= 0) {
        struct stat saved, current;
        if (fstat(s->manifest, &saved) < 0 || fstatat(s->base, "manifest", &current, AT_SYMLINK_NOFOLLOW) < 0 ||
            !same_object(&saved, &current) || current.st_uid != 0 || !S_ISREG(current.st_mode) ||
            current.st_nlink != 1 || (current.st_mode & 0022) || unlinkat(s->base, "manifest", 0) < 0) return -1;
    }
    return finish_base(s);
}

#define MUTABLE_MAX_DEPTH 128U

struct mutable_entry { char *path; struct stat st; uint64_t mount_id; };
struct mutable_tree { struct mutable_entry *entries; size_t count, capacity; uint64_t names; };
struct proc_identity { dev_t dev; ino_t ino; mode_t type; };

static int proc_identity_compare(const void *left, const void *right)
{
    const struct proc_identity *a = left, *b = right;
    if (a->dev != b->dev) return a->dev < b->dev ? -1 : 1;
    if (a->ino != b->ino) return a->ino < b->ino ? -1 : 1;
    if (a->type != b->type) return a->type < b->type ? -1 : 1;
    return 0;
}

static bool proc_identity_contains(const struct install *s, const struct stat *target)
{
    struct proc_identity key = { .dev = target->st_dev, .ino = target->st_ino,
        .type = target->st_mode & S_IFMT };
    return s->proc_target_count && bsearch(&key, s->proc_targets, s->proc_target_count,
        sizeof(*s->proc_targets), proc_identity_compare) != NULL;
}

static int mutable_parent_current(struct install *s)
{
    int slash = open("/", DIR_FLAGS);
    if (slash < 0) return -1;
    int fresh = at_root(slash, "data/local", DIR_FLAGS), saved = errno;
    close(slash);
    if (fresh < 0) { errno = saved; return -1; }
    struct stat held, named; uint64_t id;
    bool valid = fstat(s->local, &held) == 0 && fstat(fresh, &named) == 0 && same_object(&held, &named) &&
        S_ISDIR(named.st_mode) && (named.st_uid == 0 || named.st_uid == 1000) && !(named.st_mode & 0002) &&
        (!(named.st_mode & 0020) || named.st_gid == 0 || named.st_gid == 1000) &&
        mount_identity(fresh, &id) == 0 && id == s->mount_id;
    close(fresh);
    if (!valid) { errno = ESTALE; return -1; }
    return 0;
}

static int mutable_named_current(struct install *s, int parent, const char *name, int held,
                                 const struct stat *expected)
{
    int fresh = openat(parent, name, O_PATH | O_NOFOLLOW | O_CLOEXEC);
    if (fresh < 0) return -1;
    struct stat current, named;
    uint64_t held_mount, named_mount;
    bool valid = fstat(held, &current) == 0 && fstat(fresh, &named) == 0 &&
        same_object(&current, &named) && (!expected || same_object(&named, expected)) &&
        mount_identity(held, &held_mount) == 0 && mount_identity(fresh, &named_mount) == 0 &&
        held_mount == s->mount_id && named_mount == held_mount;
    int saved = errno; close(fresh);
    if (!valid) { errno = saved ? saved : ESTALE; return -1; }
    return 0;
}

static int installed_anchors_current(struct install *s, bool empty_views)
{
    struct stat held, named, base, root, views;
    uint64_t id;
    if (mutable_parent_current(s) < 0 ||
        mutable_named_current(s, s->local, s->name, s->base, &s->base_stat) < 0 ||
        mutable_named_current(s, s->base, "root", s->root, &s->root_stat) < 0 ||
        mutable_named_current(s, s->base, "views", s->views, &s->views_stat) < 0 ||
        mutable_named_current(s, s->base, "manifest", s->manifest, NULL) < 0 ||
        instance_current(s) < 0 || (empty_views && views_empty(s) < 0) ||
        fstat(s->views, &views) < 0 || views.st_uid != 0 || views.st_gid != 0 ||
        (views.st_mode & 07777) != 0700 ||
        fstat(s->base, &base) < 0 || !same_object(&base, &s->base_stat) ||
        base.st_uid != 0 || base.st_gid != 0 || (base.st_mode & 07777) != 0700 ||
        fstat(s->root, &root) < 0 || !same_object(&root, &s->root_stat) ||
        root.st_uid != 0 || root.st_gid != 0 || (root.st_mode & 07777) != 0755 ||
        mount_identity(s->root, &id) < 0 || id != s->mount_id ||
        fstat(s->manifest, &held) < 0 || fstatat(s->base, "manifest", &named, AT_SYMLINK_NOFOLLOW) < 0 ||
        !same_object(&held, &named) || !S_ISREG(held.st_mode) || held.st_nlink != 1 ||
        held.st_uid != 0 || held.st_gid != 0 || (held.st_mode & 07777) != 0400 ||
        mount_identity(s->manifest, &id) < 0 || id != s->mount_id) { errno = ESTALE; return -1; }
    uint64_t header[AJIU_MANIFEST_HEADER_WORDS];
    if (pread(s->manifest, header, sizeof(header), 0) != (ssize_t)sizeof(header) ||
        header[0] != (s->mutable_tree ? AJIU_MANIFEST_V2 : AJIU_MANIFEST_V1) || header[1] != (uint64_t)s->root_stat.st_dev ||
        header[2] != (uint64_t)s->root_stat.st_ino || header[3] != (uint64_t)s->views_stat.st_dev ||
        header[4] != (uint64_t)s->views_stat.st_ino || !header[5]) { errno = ESTALE; return -1; }
    return 0;
}

static int mutable_anchors_current(struct install *s)
{
    return installed_anchors_current(s, true) < 0 ||
        (s->recover_remove ? recovery_gate(s) < 0 : receipt_absent(s->base) < 0) ? -1 : 0;
}

static int mutable_remember(struct mutable_tree *tree, const char *path, const struct stat *st, uint64_t mount_id)
{
    size_t length = strlen(path);
    if (tree->count == MAX_ENTRIES || tree->names + length + 1 > MAX_NAMES) { errno = EOVERFLOW; return -1; }
    if (tree->count == tree->capacity) {
        size_t capacity = tree->capacity ? tree->capacity * 2 : 64;
        if (capacity > MAX_ENTRIES) capacity = MAX_ENTRIES;
        struct mutable_entry *entries = realloc(tree->entries, capacity * sizeof(*entries));
        if (!entries) return -1;
        tree->entries = entries; tree->capacity = capacity;
    }
    char *copy = strdup(path);
    if (!copy) return -1;
    tree->entries[tree->count++] = (struct mutable_entry){ .path = copy, .st = *st, .mount_id = mount_id };
    tree->names += length + 1;
    return 0;
}

static int mutable_scan(struct install *s, struct mutable_tree *tree, int parent, const char *prefix, unsigned int depth)
{
    if (depth > MUTABLE_MAX_DEPTH || time_remaining() < 0) { if (depth > MUTABLE_MAX_DEPTH) errno = EOVERFLOW; return -1; }
    int copy = openat(parent, ".", DIR_FLAGS);
    if (copy < 0) return -1;
    DIR *directory = fdopendir(copy);
    if (!directory) { close(copy); return -1; }
    int error = 0;
    for (;;) {
        if (time_remaining() < 0) { error = errno; break; }
        errno = 0; struct dirent *entry = readdir(directory);
        if (!entry) { error = errno; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        char path[4097];
        int n = snprintf(path, sizeof(path), "%s%s%s", prefix, *prefix ? "/" : "", entry->d_name);
        if (n <= 0 || (size_t)n >= sizeof(path) || !safe_path(path) || !utf8((unsigned char *)path, (size_t)n)) {
            error = EPROTO; break;
        }
        int fd = at_root(s->root, path, O_PATH);
        struct stat st; uint64_t id;
        if (fd < 0) { error = errno; break; }
        if (fstat(fd, &st) < 0 || mount_identity(fd, &id) < 0) { error = errno; close(fd); break; }
        if (id != s->mount_id || st.st_dev != s->root_stat.st_dev ||
            (!S_ISREG(st.st_mode) && !S_ISDIR(st.st_mode) && !S_ISLNK(st.st_mode))) {
            close(fd); error = EXDEV; break;
        }
        if (mutable_remember(tree, path, &st, id) < 0) { error = errno; close(fd); break; }
        close(fd);
        if (S_ISDIR(st.st_mode)) {
            int child = at_root(s->root, path, DIR_FLAGS);
            struct stat opened;
            if (child < 0) { error = errno; break; }
            if (fstat(child, &opened) < 0 || !same_object(&opened, &st)) error = ESTALE;
            else if (mutable_scan(s, tree, child, path, depth + 1) < 0) error = errno;
            close(child);
            if (error) break;
        }
    }
    if (closedir(directory) < 0 && !error) error = errno;
    if (error) { errno = error; return -1; }
    return 0;
}

static int recovery_view(struct install *s, const char *name, const struct stat *expected, struct stat *out);
static bool nonce_valid(const char *nonce);

static int recovery_targets_prepare(struct install *s)
{
    struct mutable_tree tree = {0};
    struct stat manifest, receipt;
    int error = 0;
    if (installed_anchors_current(s, false) < 0 || recovery_gate(s) < 0 ||
        fstat(s->manifest, &manifest) < 0 || fstat(s->receipt, &receipt) < 0 ||
        mutable_scan(s, &tree, s->root, "", 0) < 0) error = errno;
    size_t capacity = tree.count + 5 + MAX_RECOVERY_VIEWS;
    struct proc_identity *targets = NULL;
    size_t count = 0;
    if (!error) {
        targets = calloc(capacity, sizeof(*targets));
        if (!targets) error = errno;
    }
    if (!error) {
        const struct stat *fixed[] = { &s->base_stat, &s->root_stat, &s->views_stat, &manifest, &receipt };
        for (size_t i = 0; i < sizeof(fixed) / sizeof(*fixed); ++i)
            targets[count++] = (struct proc_identity){ .dev = fixed[i]->st_dev, .ino = fixed[i]->st_ino,
                .type = fixed[i]->st_mode & S_IFMT };
        for (size_t i = 0; i < tree.count; ++i)
            targets[count++] = (struct proc_identity){ .dev = tree.entries[i].st.st_dev,
                .ino = tree.entries[i].st.st_ino, .type = tree.entries[i].st.st_mode & S_IFMT };
        int fd = openat(s->views, ".", DIR_FLAGS), saved = errno;
        DIR *directory = fd < 0 ? NULL : fdopendir(fd);
        if (!directory) { error = fd < 0 ? saved : errno; if (fd >= 0) close(fd); }
        while (directory && !error) {
            if (time_remaining() < 0) { error = errno; break; }
            errno = 0; struct dirent *entry = readdir(directory);
            if (!entry) { error = errno; break; }
            if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
            if (count == capacity) { error = EOVERFLOW; break; }
            if (!nonce_valid(entry->d_name)) { error = EPROTO; break; }
            struct stat view;
            if (recovery_view(s, entry->d_name, NULL, &view) < 0) { error = errno; break; }
            targets[count++] = (struct proc_identity){ .dev = view.st_dev, .ino = view.st_ino,
                .type = view.st_mode & S_IFMT };
        }
        if (directory) {
            int close_result = closedir(directory), saved = errno;
            directory = NULL;
            if (close_result < 0 && !error) error = saved;
        }
        if (!error) {
            qsort(targets, count, sizeof(*targets), proc_identity_compare);
            if (time_remaining() < 0 || installed_anchors_current(s, false) < 0 || recovery_gate(s) < 0)
                error = errno;
        }
    }
    for (size_t i = 0; i < tree.count; ++i) free(tree.entries[i].path);
    free(tree.entries);
    if (error) { free(targets); errno = error; return -1; }
    s->proc_targets = targets;
    s->proc_target_count = count;
    return 0;
}

static const struct stat *mutable_parent_identity(const struct install *s, const struct mutable_tree *tree, const char *path)
{
    const char *slash = strrchr(path, '/');
    if (!slash) return &s->root_stat;
    size_t length = (size_t)(slash - path);
    for (size_t i = 0; i < tree->count; ++i)
        if (strlen(tree->entries[i].path) == length && !memcmp(tree->entries[i].path, path, length))
            return &tree->entries[i].st;
    return NULL;
}

static int remove_mutable_tree(struct install *s)
{
    struct mutable_tree tree = {0};
    int error = 0;
    /* Pre-scan rejects submounts and exhausted budgets before the first unlink. The manifest is
     * historical provenance only: v2 never compares guest entries to their original package inode. */
    if (mutable_anchors_current(s) < 0 || mutable_scan(s, &tree, s->root, "", 0) < 0) error = errno;
    for (size_t i = 0; s->recover_remove && !error && i < tree.count; ++i) {
        if (!proc_identity_contains(s, &tree.entries[i].st)) error = ESTALE;
    }
    for (size_t i = tree.count; !error && i > 0; --i) {
        if (time_remaining() < 0 || mutable_anchors_current(s) < 0) { error = errno; break; }
        const struct mutable_entry *entry = &tree.entries[i - 1];
        const char *leaf;
        int parent = parent_open(s, entry->path, &leaf);
        if (parent < 0) { error = errno; break; }
        const struct stat *expected_parent = mutable_parent_identity(s, &tree, entry->path);
        struct stat pst, current, named;
        uint64_t parent_mount, id;
        int fd = -1;
        if (!expected_parent || fstat(parent, &pst) < 0 || !same_object(&pst, expected_parent) ||
            mount_identity(parent, &parent_mount) < 0 || parent_mount != s->mount_id) error = ESTALE;
        if (!error) fd = openat(parent, leaf, O_PATH | O_NOFOLLOW | O_CLOEXEC);
        if (!error && (fd < 0 || fstat(fd, &current) < 0 || !same_object(&current, &entry->st) ||
            mount_identity(fd, &id) < 0 || id != entry->mount_id || id != s->mount_id ||
            fstatat(parent, leaf, &named, AT_SYMLINK_NOFOLLOW) < 0 || !same_object(&named, &entry->st))) error = ESTALE;
        if (!error && unlinkat(parent, leaf, S_ISDIR(entry->st.st_mode) ? AT_REMOVEDIR : 0) < 0) error = errno;
        if (fd >= 0) close(fd);
        close(parent);
    }
    for (size_t i = 0; i < tree.count; ++i) free(tree.entries[i].path);
    free(tree.entries);
    if (!error && mutable_anchors_current(s) < 0) error = errno;
    if (!error && unlinkat(s->base, "views", AT_REMOVEDIR) < 0) error = errno;
    if (!error && unlinkat(s->base, "root", AT_REMOVEDIR) < 0) error = errno;
    if (!error && unlinkat(s->base, "manifest", 0) < 0) error = errno;
    if (!error && finish_base(s) < 0) error = errno;
    if (error) { errno = error; return -1; }
    return 0;
}

static int parse_stream(struct install *s)
{
    unsigned char header[24];
    stage = "stream_header";
    if (exact_read(header, 8) < 0) return -1;
    if (memcmp(header, "AJIF\0\1\0\0", 8)) { errno = EPROTO; return -1; }
    for (;;) {
        stage = "record_header";
        if (exact_read(header, sizeof(header)) < 0) return -1;
        unsigned int kind = header[0], path_length = ((unsigned int)header[2] << 8) | header[3];
        uint32_t mode = be32(header + 4), uid = be32(header + 8), gid = be32(header + 12);
        uint64_t size = be64(header + 16);
        if (kind == 255) {
            unsigned char zero[23] = {0}, tail;
            if (memcmp(header + 1, zero, sizeof(zero))) { errno = EPROTO; return -1; }
            ssize_t n = input_read(&tail, 1);
            if (n != 0) { if (n > 0) errno = EPROTO; return -1; }
            return 0;
        }
        if (kind < 1 || kind > 4 || header[1] != 0 || path_length == 0 || path_length > 4096 ||
            uid > 65535 || gid > 65535 || (mode & ~(kind == 1 ? 07777U : kind == 2 ? 06777U : 0777U)) ||
            s->count == MAX_ENTRIES || (kind == 1 && size != 0) ||
            (kind == 2 && (size > MAX_FILE || size > MAX_TOTAL - s->bytes)) ||
            (kind >= 3 && (size == 0 || size > 4096)) || s->names + path_length + 1 > MAX_NAMES) {
            errno = EPROTO; return -1;
        }
        char path[4097], link[4097];
        if (exact_read(path, path_length) < 0) return -1;
        path[path_length] = 0;
        stage = "record_path";
        if (!utf8((unsigned char *)path, path_length) || !safe_path(path)) { errno = EPROTO; return -1; }
        uint32_t bucket = slot(s, path);
        if (s->index[bucket]) { errno = EEXIST; return -1; }
        if (kind >= 3) {
            if (exact_read(link, (size_t)size) < 0) return -1;
            link[size] = 0;
            if (!utf8((unsigned char *)link, (size_t)size)) { errno = EPROTO; return -1; }
            if (kind == 4) {
                uint32_t previous = safe_path(link) ? s->index[slot(s, link)] : 0;
                if (!previous || s->entries[previous - 1].kind != 2) { errno = EPROTO; return -1; }
            }
        }
        struct entry *e = &s->entries[s->count];
        e->path = strdup(path);
        if (!e->path) return -1;
        ++s->count;
        e->kind = (unsigned char)kind;
        s->index[bucket] = s->count;
        s->names += path_length + 1;
        int file = -1;
        if (!s->validate && create_entry(s, e, mode, uid, gid, link, &file) < 0) return -1;
        if (kind == 2) {
            stage = "file_data";
            uint64_t remaining = size;
            unsigned char buffer[65536];
            while (remaining) {
                size_t chunk = remaining < sizeof(buffer) ? (size_t)remaining : sizeof(buffer);
                if (exact_read(buffer, chunk) < 0) { if (file >= 0) close(file); return -1; }
                if (file >= 0) {
                    if (exact_write(file, buffer, chunk) < 0) { close(file); return -1; }
                }
                remaining -= chunk;
            }
            s->bytes += size;
            if (file >= 0) {
                int rc = fchown(file, uid, gid) < 0 || fchmod(file, mode) < 0 ? -1 : 0;
                int saved = errno;
                if (close(file) < 0) { rc = -1; saved = errno; }
                errno = saved;
                if (rc < 0) return -1;
            }
        }
    }
}

static int prepare_runtime_layout(struct install *s)
{
    stage = "runtime_layout";
    static const char *const directories[] = { "root", "tmp", "run", "dev", "proc", "etc" };
    for (size_t i = 0; i < sizeof(directories) / sizeof(*directories); ++i) {
        int fd = at_root(s->root, directories[i], DIR_FLAGS);
        if (fd < 0) return -1;
        if (close(fd) < 0) return -1;
    }
    uint32_t bucket = slot(s, "etc/resolv.conf");
    if (s->index[bucket]) {
        struct entry *e = &s->entries[s->index[bucket] - 1];
        if (e->kind != 2) { errno = ELOOP; return -1; }
        return 0;
    }
    if (s->count == MAX_ENTRIES || s->names + sizeof("etc/resolv.conf") > MAX_NAMES) { errno = EOVERFLOW; return -1; }
    struct entry *entry = &s->entries[s->count];
    entry->path = strdup("etc/resolv.conf");
    if (!entry->path) return -1;
    ++s->count;
    entry->kind = 2;
    s->index[bucket] = s->count;
    s->names += sizeof("etc/resolv.conf");
    int file = -1;
    if (create_entry(s, entry, 0644, 0, 0, NULL, &file) < 0) return -1;
    int rc = fchown(file, 0, 0) < 0 || fchmod(file, 0644) < 0 ? -1 : 0;
    int saved = errno;
    if (close(file) < 0) { rc = -1; saved = errno; }
    errno = saved;
    return rc;
}

static int load_existing(struct install *s, uint64_t device, uint64_t inode)
{
    stage = "existing_instance";
    if (getuid() != 0 || geteuid() != 0 || getgid() != 0 || getegid() != 0) { errno = EPERM; return -1; }
    int slash = open("/", DIR_FLAGS);
    if (slash < 0) return -1;
    s->local = at_root(slash, "data/local", DIR_FLAGS);
    close(slash);
    if (s->local < 0) return -1;
    s->base = openat(s->local, s->name, DIR_FLAGS);
    if (s->base < 0 || fstat(s->base, &s->base_stat) < 0 || s->base_stat.st_uid != 0 ||
        s->base_stat.st_gid != 0 || (s->base_stat.st_mode & 07777) != 0700 ||
        (uint64_t)s->base_stat.st_dev != device || (uint64_t)s->base_stat.st_ino != inode ||
        mount_identity(s->base, &s->mount_id) < 0) { errno = ESTALE; return -1; }
    s->base_created = s->base_known = true;
    stage = "removal_receipt";
    if (!s->recover_remove && receipt_absent(s->base) < 0) return -1;
    stage = "existing_instance";
    s->root = openat(s->base, "root", DIR_FLAGS);
    if (s->root < 0 || fstat(s->root, &s->root_stat) < 0 || s->root_stat.st_uid != 0 ||
        (s->root_stat.st_mode & 07777) != 0755) { errno = ESTALE; return -1; }
    s->root_created = s->root_known = true;
    s->manifest = openat(s->base, "manifest", O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    struct stat st;
    if (s->manifest < 0 || fstat(s->manifest, &st) < 0 || !S_ISREG(st.st_mode) ||
        st.st_uid != 0 || st.st_gid != 0 || st.st_nlink != 1 || (st.st_mode & 07777) != 0400 ||
        st.st_size < (off_t)(6 * sizeof(uint64_t)) ||
        st.st_size > (off_t)(MAX_NAMES + MAX_ENTRIES * 5 * sizeof(uint64_t) + 6 * sizeof(uint64_t))) { errno = ESTALE; return -1; }
    s->views = openat(s->base, "views", DIR_FLAGS);
    if (s->views < 0 || fstat(s->views, &s->views_stat) < 0) return -1;
    s->views_created = s->views_known = true;
    uint64_t header[6];
    if (read(s->manifest, header, sizeof(header)) != (ssize_t)sizeof(header) ||
        (header[0] != AJIU_MANIFEST_V1 && header[0] != AJIU_MANIFEST_V2) || header[1] != (uint64_t)s->root_stat.st_dev ||
        header[2] != (uint64_t)s->root_stat.st_ino || header[3] != (uint64_t)s->views_stat.st_dev ||
        header[4] != (uint64_t)s->views_stat.st_ino || !header[5] || (!s->recover_remove && views_empty(s) < 0)) {
        errno = ESTALE; return -1;
    }
    for (;;) {
        if (time_remaining() < 0) return -1;
        uint64_t h[5];
        ssize_t n = read(s->manifest, h, sizeof(h));
        if (n == 0) break;
        if (n != (ssize_t)sizeof(h) || h[0] < 1 || h[0] > 4 || h[1] == 0 || h[1] > 4096 ||
            h[4] != header[5] || h[2] != (uint64_t)s->root_stat.st_dev ||
            s->count == MAX_ENTRIES || s->names + h[1] + 1 > MAX_NAMES) {
            errno = EPROTO; return -1;
        }
        char path[4097];
        n = read(s->manifest, path, (size_t)h[1]);
        if (n != (ssize_t)h[1]) { errno = EPROTO; return -1; }
        path[h[1]] = 0;
        if (!utf8((unsigned char *)path, (size_t)h[1]) || !safe_path(path)) { errno = EPROTO; return -1; }
        uint32_t bucket = slot(s, path);
        if (s->index[bucket]) { errno = EPROTO; return -1; }
        struct entry *e = &s->entries[s->count];
        e->path = strdup(path);
        if (!e->path) return -1;
        ++s->count;
        s->index[bucket] = s->count;
        s->names += h[1] + 1;
        e->kind = (unsigned char)h[0];
        e->created = true;
        // Mount IDs are transient; every unlink is checked against this invocation's protected mount.
        e->dev = (dev_t)h[2]; e->ino = (ino_t)h[3]; e->mount_id = s->mount_id;
    }
    if (instance_current(s) < 0) return -1;
    s->mutable_tree = header[0] == AJIU_MANIFEST_V2;
    return s->recover_remove ? installed_anchors_current(s, false) : 0;
}

static bool nonce_valid(const char *nonce)
{
    if (strnlen(nonce, 25) != 24) return false;
    for (int i = 0; i < 24; ++i)
        if (!((nonce[i] >= '0' && nonce[i] <= '9') || (nonce[i] >= 'a' && nonce[i] <= 'f'))) return false;
    return true;
}

static bool boot_id_valid(const char id[36])
{
    bool nonzero = false;
    for (size_t i = 0; i < 36; ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) { if (id[i] != '-') return false; }
        else {
            if (!((id[i] >= '0' && id[i] <= '9') || (id[i] >= 'a' && id[i] <= 'f'))) return false;
            if (id[i] != '0') nonzero = true;
        }
    }
    return nonzero;
}

static int protected_proc(uint64_t *proc_mount)
{
    int slash = open("/", DIR_FLAGS);
    if (slash < 0) return -1;
    /* /proc is a mount boundary: pin it first, then forbid every further mount
     * crossing. Merely checking the leaf's procfs type would accept random/uuid
     * bind-mounted over boot_id and mistake a changing UUID for a reboot. */
    int proc = at_root(slash, "proc", DIR_FLAGS), saved = errno;
    close(slash);
    if (proc < 0) { errno = saved; return -1; }
    struct stat st;
    struct statfs fs;
    if (fstat(proc, &st) < 0 || !S_ISDIR(st.st_mode) || st.st_uid != 0 || st.st_gid != 0 ||
        (st.st_mode & 0022) || fstatfs(proc, &fs) < 0 || fs.f_type != PROC_SUPER_MAGIC ||
        mount_identity(proc, proc_mount) < 0 || !*proc_mount) {
        close(proc); errno = ESTALE; return -1;
    }
    return proc;
}

static int read_boot_id(char id[36])
{
    stage = "recovery_boot_id";
    uint64_t proc_mount, boot_mount;
    int proc = protected_proc(&proc_mount);
    if (proc < 0) return -1;
    struct stat st;
    int error = 0, fd = -1;
    if (!error) {
        struct open_how how = { .flags = O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW,
            .resolve = RESOLVE_BENEATH | RESOLVE_NO_MAGICLINKS | RESOLVE_NO_SYMLINKS | RESOLVE_NO_XDEV };
        fd = (int)syscall(SYS_openat2, proc, "sys/kernel/random/boot_id", &how, sizeof(how));
        if (fd < 0) error = errno;
    }
    if (!error && (fstat(fd, &st) < 0 || !S_ISREG(st.st_mode) || st.st_uid != 0 || st.st_gid != 0 ||
        (st.st_mode & 0022) || mount_identity(fd, &boot_mount) < 0 || boot_mount != proc_mount)) error = ESTALE;
    char bytes[38];
    ssize_t n = -1;
    if (!error) {
        do {
            if (time_remaining() < 0) break;
            n = pread(fd, bytes, sizeof(bytes), 0);
        } while (n < 0 && errno == EINTR);
        if (n != 37 || bytes[36] != '\n' || !boot_id_valid(bytes)) error = ESTALE;
    }
    if (fd >= 0) close(fd);
    close(proc);
    if (error) { errno = error; return -1; }
    memcpy(id, bytes, 36);
    return 0;
}

/* No other base entries can be discarded or hidden by finalization. */
static int base_contents(struct install *s, unsigned int expected)
{
    int fd = openat(s->base, ".", DIR_FLAGS);
    if (fd < 0) return -1;
    DIR *dir = fdopendir(fd);
    if (!dir) { close(fd); return -1; }
    unsigned int seen = 0;
    int error = 0;
    for (;;) {
        if (time_remaining() < 0) { error = errno; break; }
        errno = 0; struct dirent *entry = readdir(dir);
        if (!entry) { error = errno; break; }
        const char *name = entry->d_name;
        if (!strcmp(name, ".") || !strcmp(name, "..")) continue;
        unsigned int bit = !strcmp(name, "root") ? 1U : !strcmp(name, "views") ? 2U :
            !strcmp(name, "manifest") ? 4U : !strcmp(name, REMOVE_RECEIPT) ? 8U : 0U;
        if (!bit || !(expected & bit) || (seen & bit)) { error = ENOTEMPTY; break; }
        seen |= bit;
    }
    if (!error && seen != expected) error = ESTALE;
    if (closedir(dir) < 0 && !error) error = errno;
    return error ? (errno = error, -1) : 0;
}

static int receipt_current(struct install *s)
{
    struct stat st, base;
    struct removal_receipt data;
    if (mutable_parent_current(s) < 0 ||
        mutable_named_current(s, s->local, s->name, s->base, &s->base_stat) < 0 ||
        fstat(s->base, &base) < 0 || base.st_uid != 0 || base.st_gid != 0 || (base.st_mode & 07777) != 0700 ||
        mutable_named_current(s, s->base, REMOVE_RECEIPT, s->receipt, &s->receipt_stat) < 0 ||
        fstat(s->receipt, &st) < 0 || !S_ISREG(st.st_mode) || st.st_uid != 0 || st.st_gid != 0 ||
        (st.st_mode & 07777) != 0600 || st.st_nlink != 1 || st.st_size != (off_t)sizeof(data) ||
        pread(s->receipt, &data, sizeof(data), 0) != (ssize_t)sizeof(data) ||
        memcmp(&data, &s->receipt_data, sizeof(data))) { errno = ESTALE; return -1; }
    return 0;
}

static int recovery_gate(struct install *s)
{
    if (!s->recovery_ready || !s->removal_locked) { errno = EBUSY; return -1; }
    return receipt_current(s);
}

static int receipt_create(struct install *s)
{
    s->receipt = openat(s->base, REMOVE_RECEIPT, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (s->receipt < 0) return -1;
    int error = 0;
    if (fchown(s->receipt, 0, 0) < 0 || fchmod(s->receipt, 0600) < 0 ||
        exact_write(s->receipt, &s->receipt_data, sizeof(s->receipt_data)) < 0 ||
        fstat(s->receipt, &s->receipt_stat) < 0) error = errno;
    /* Even a partial/failed receipt remains a startup barrier. Never roll it back. */
    if (fsync(s->receipt) < 0 && !error) error = errno;
    if (fsync(s->base) < 0 && !error) error = errno;
    if (!error && receipt_current(s) < 0) error = errno;
    return error ? (errno = error, -1) : 0;
}

static int recovery_view(struct install *s, const char *name, const struct stat *expected, struct stat *out)
{
    int fd = openat(s->views, name, DIR_FLAGS);
    if (fd < 0) return -1;
    struct stat st;
    uint64_t id;
    int error = 0;
    if (fstat(fd, &st) < 0 || !S_ISDIR(st.st_mode) || st.st_uid != 0 || st.st_gid != 0 ||
        (st.st_mode & 07777) != 0700 || st.st_dev != s->base_stat.st_dev ||
        (expected && !same_object(&st, expected)) || mount_identity(fd, &id) < 0 || id != s->mount_id ||
        mutable_named_current(s, s->views, name, fd, &st) < 0) error = ESTALE;
    DIR *dir = NULL;
    if (!error) { dir = fdopendir(fd); if (!dir) error = errno; }
    if (dir) {
        for (;;) {
            if (time_remaining() < 0) { error = errno; break; }
            errno = 0; struct dirent *entry = readdir(dir);
            if (!entry) { error = errno; break; }
            if (strcmp(entry->d_name, ".") && strcmp(entry->d_name, "..")) { error = ENOTEMPTY; break; }
        }
        if (!error && mutable_named_current(s, s->views, name, fd, &st) < 0) error = errno;
        if (closedir(dir) < 0 && !error) error = errno;
    } else close(fd);
    if (error) { errno = error; return -1; }
    if (out) *out = st;
    return 0;
}

static int recovery_views(struct install *s, bool remove)
{
    struct recovery_view_entry { char name[25]; struct stat st; };
    struct recovery_view_entry *entries = calloc(MAX_RECOVERY_VIEWS, sizeof(*entries));
    if (!entries) return -1;
    int fd = openat(s->views, ".", DIR_FLAGS), error = 0;
    DIR *dir = fd < 0 ? NULL : fdopendir(fd);
    size_t count = 0;
    if (!dir) { error = errno; if (fd >= 0) close(fd); }
    while (dir && !error) {
        if (time_remaining() < 0) { error = errno; break; }
        errno = 0; struct dirent *entry = readdir(dir);
        if (!entry) { error = errno; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        if (count == MAX_RECOVERY_VIEWS) { error = EOVERFLOW; break; }
        if (!nonce_valid(entry->d_name)) { error = EPROTO; break; }
        if (recovery_view(s, entry->d_name, NULL, &entries[count].st) < 0) { error = errno; break; }
        memcpy(entries[count++].name, entry->d_name, 25);
    }
    if (dir && closedir(dir) < 0 && !error) error = errno;
    /* Preflight every child before the first rmdir. No cgroup name or membership is inferred. */
    for (size_t i = 0; remove && !error && i < count; ++i) {
        struct stat current;
        if (installed_anchors_current(s, false) < 0 || recovery_gate(s) < 0 ||
            recovery_view(s, entries[i].name, &entries[i].st, &current) < 0) { error = errno; break; }
        if (!proc_identity_contains(s, &current)) { error = ESTALE; break; }
        if (unlinkat(s->views, entries[i].name, AT_REMOVEDIR) < 0) error = errno;
    }
    free(entries);
    if (remove && !error && (views_empty(s) < 0 || fsync(s->views) < 0)) error = errno;
    return error ? (errno = error, -1) : 0;
}

static int finish_base(struct install *s)
{
    if (!s->recover_remove) {
        if (unlinkat(s->local, s->name, AT_REMOVEDIR) < 0) return -1;
        stage = "remove_parent_sync";
        return fsync(s->local);
    }
    stage = "recovery_finalize";
    /* The receipt outlives ALL rootfs unlinks and any failure in that deletion.
     * Only the receipt may remain here; never call ordinary rollback's base rmdir. */
    if (recovery_gate(s) < 0 || base_contents(s, 8U) < 0 || fsync(s->base) < 0 ||
        unlinkat(s->base, REMOVE_RECEIPT, 0) < 0) return -1;
    if (unlinkat(s->local, s->name, AT_REMOVEDIR) == 0) {
        /* A failed parent sync is cleanup_unknown even though the name is now
         * absent. A later retry must still reject missing instance identities. */
        stage = "recovery_parent_sync";
        return fsync(s->local);
    }
    int saved = errno;
    /* Two unlinks cannot be atomic. Root/views/manifest are already gone, so a
     * crash here cannot start a guest. Restore the barrier on a failed rmdir. */
    close(s->receipt); s->receipt = -1;
    if (receipt_create(s) < 0) stage = "recovery_receipt_restore";
    errno = saved;
    return -1;
}

static int recovery_exclusive(struct install *s)
{
    int64_t until = monotonic_ms() + RECOVERY_STOP_WAIT;
    for (;;) {
        if (flock(s->base, LOCK_EX | LOCK_NB) == 0) { s->removal_locked = true; return 0; }
        if (errno != EWOULDBLOCK && errno != EINTR) return -1;
        if (time_remaining() < 0) return -1;
        if (monotonic_ms() >= until) { errno = EBUSY; return -1; }
        if (poll(NULL, 0, 20) < 0 && errno != EINTR) return -1;
    }
}

static bool decimal(const char *text, uint64_t *value);

static int proc_open(int parent, const char *name, int flags, uint64_t mount)
{
    struct open_how how = { .flags = (uint64_t)(flags | O_NOFOLLOW | O_CLOEXEC),
        .resolve = RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS | RESOLVE_NO_XDEV };
    int fd = (int)syscall(SYS_openat2, parent, name, &how, sizeof(how));
    if (fd < 0) return -1;
    uint64_t actual;
    if (mount_identity(fd, &actual) < 0 || actual != mount) { close(fd); errno = ESTALE; return -1; }
    return fd;
}

static int proc_magic_target(struct install *s, int parent, const char *name)
{
    struct stat before, target, after, target_after;
    if (fstatat(parent, name, &before, AT_SYMLINK_NOFOLLOW) < 0) return -1;
    if (!S_ISLNK(before.st_mode)) { errno = ESTALE; return -1; }
    if (fstatat(parent, name, &target, 0) < 0 || fstatat(parent, name, &after, AT_SYMLINK_NOFOLLOW) < 0 ||
        fstatat(parent, name, &target_after, 0) < 0) return -1;
    if (!same_object(&before, &after) || !same_object(&target, &target_after)) { errno = EBUSY; return -1; }
    if ((S_ISDIR(target.st_mode) && (same_object(&target, &s->base_stat) ||
        same_object(&target, &s->root_stat) || same_object(&target, &s->views_stat))) ||
        proc_identity_contains(s, &target)) { errno = EBUSY; return -1; }
    return 0;
}

static bool proc_number(const char *name)
{
    if (!*name) return false;
    for (const char *p = name; *p; ++p) if (*p < '0' || *p > '9') return false;
    return true;
}

static int proc_task_state(int task, uint64_t mount, uint64_t *start_time)
{
    int fd = proc_open(task, "stat", O_RDONLY | O_NONBLOCK, mount);
    if (fd < 0) return -1;
    char text[4096];
    ssize_t n = pread(fd, text, sizeof(text) - 1, 0);
    int saved = errno;
    close(fd);
    if (n < 0) { errno = saved; return -1; }
    if (!n || n == (ssize_t)sizeof(text) - 1 || memchr(text, 0, (size_t)n)) { errno = EPROTO; return -1; }
    text[n] = 0;
    const char *tail = strrchr(text, ')');
    if (!tail) { errno = EPROTO; return -1; }
    char *cursor = (char *)tail + 1;
    while (*cursor == ' ' || *cursor == '\t') ++cursor;
    if (!*cursor || !cursor[1] || cursor[1] != ' ' || cursor[0] == 'Z' || cursor[0] == 'X') return 1;
    cursor += 2;
    uint64_t flags = 0;
    for (unsigned int field = 4; field <= 9; ++field) {
        while (*cursor == ' ' || *cursor == '\t') ++cursor;
        bool signed_field = field == 7 || field == 8;
        bool negative = signed_field && *cursor == '-';
        if (negative) ++cursor;
        if (*cursor < '0' || *cursor > '9') { errno = EPROTO; return -1; }
        uint64_t number = 0;
        while (*cursor >= '0' && *cursor <= '9') {
            unsigned int digit = (unsigned int)(*cursor++ - '0');
            if (number > (UINT64_MAX - digit) / 10) { errno = EOVERFLOW; return -1; }
            number = number * 10 + digit;
        }
        if (*cursor != ' ' && *cursor != '\t' && *cursor != '\n') { errno = EPROTO; return -1; }
        if (field <= 8 && number > (negative ? (uint64_t)INT32_MAX + 1 : INT32_MAX)) {
            errno = ERANGE; return -1;
        }
        if (field == 9) flags = number;
    }
    if (flags & UINT64_C(0x00200000)) return 2;
    for (unsigned int field = 10; field < 22; ++field) {
        while (*cursor == ' ' || *cursor == '\t') ++cursor;
        if (!*cursor) { errno = EPROTO; return -1; }
        while (*cursor && *cursor != ' ' && *cursor != '\t' && *cursor != '\n') ++cursor;
    }
    while (*cursor == ' ' || *cursor == '\t') ++cursor;
    if (!*cursor || *cursor < '0' || *cursor > '9') { errno = EPROTO; return -1; }
    uint64_t value = 0;
    while (*cursor >= '0' && *cursor <= '9') {
        unsigned int digit = (unsigned int)(*cursor++ - '0');
        if (value > (UINT64_MAX - digit) / 10) { errno = EOVERFLOW; return -1; }
        value = value * 10 + digit;
    }
    *start_time = value;
    return 0;
}

static int proc_reference(struct install *s, int parent, const char *name, uint64_t mount)
{
    struct stat directory;
    uint64_t actual;
    if (fstat(parent, &directory) < 0 || mount_identity(parent, &actual) < 0 || actual != mount) return -1;
    return proc_magic_target(s, parent, name);
}

struct proc_scan { size_t tasks, entries; };

static int proc_budget(struct proc_scan *scan)
{
    if (++scan->entries > MAX_PROC_ENTRIES) { errno = EOVERFLOW; return -1; }
    return time_remaining() < 0 ? -1 : 0;
}

static int proc_task_disappeared(int task, uint64_t mount)
{
    uint64_t start;
    int state = proc_task_state(task, mount, &start);
    if (state == 1) return 1;
    if (state < 0) return -1;
    errno = EBUSY;
    return -1;
}

static int proc_task(struct install *s, int task, uint64_t mount, struct proc_scan *scan)
{
    uint64_t start_time;
    if (proc_budget(scan) < 0 || scan->tasks == MAX_PROC_TASKS) { if (scan->tasks == MAX_PROC_TASKS) errno = EOVERFLOW; return -1; }
    ++scan->tasks;
    int inactive = proc_task_state(task, mount, &start_time);
    if (inactive == 1 || inactive == 2) return 0;
    if (inactive < 0) return -1;
    const char *const refs[] = { "root", "cwd" };
    for (size_t i = 0; i < 2; ++i) {
        if (proc_reference(s, task, refs[i], mount) < 0) {
            int saved = errno;
            if ((saved == ENOENT || saved == ESRCH) && proc_task_disappeared(task, mount) == 1) return 0;
            errno = saved == ENOENT || saved == ESRCH ? EBUSY : saved; return -1;
        }
    }
    int fd = proc_open(task, "fd", DIR_FLAGS, mount);
    if (fd < 0) {
        int saved = errno;
        if ((saved == ENOENT || saved == ESRCH) && proc_task_disappeared(task, mount) == 1) return 0;
        errno = saved == ENOENT || saved == ESRCH ? EBUSY : saved; return -1;
    }
    DIR *dir = fdopendir(fd);
    if (!dir) { close(fd); return -1; }
    int error = 0;
    for (;;) {
        if (proc_budget(scan) < 0) { error = errno; break; }
        errno = 0; struct dirent *entry = readdir(dir);
        if (!entry) { error = errno; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        if (!proc_number(entry->d_name)) { error = EPROTO; break; }
        if (proc_reference(s, fd, entry->d_name, mount) < 0 && errno != ENOENT && errno != ESRCH) {
            error = errno; break;
        }
    }
    if (closedir(dir) < 0 && !error) error = errno;
    if (!error) {
        uint64_t current;
        int state = proc_task_state(task, mount, &current);
        if (state < 0) error = errno;
        else if (!state && current != start_time) error = ESTALE;
    }
    return error ? (errno = error, -1) : 0;
}

static bool proc_mapfile_name(const char *name)
{
    bool dash = false, left = false, right = false;
    for (const unsigned char *p = (const unsigned char *)name; *p; ++p) {
        if ((*p >= '0' && *p <= '9') || (*p >= 'a' && *p <= 'f') || (*p >= 'A' && *p <= 'F')) {
            if (dash) right = true; else left = true;
        } else if (*p == '-' && !dash && left) dash = true;
        else return false;
    }
    return dash && right;
}

static int proc_process_references(struct install *s, int process, uint64_t mount, struct proc_scan *scan)
{
    uint64_t start_time;
    int state = proc_task_state(process, mount, &start_time);
    if (state == 1 || state == 2) return 0;
    if (state < 0) return -1;
    if (proc_reference(s, process, "exe", mount) < 0) {
        int saved = errno;
        if ((saved == ENOENT || saved == ESRCH) && proc_task_disappeared(process, mount) == 1) return 0;
        errno = saved == ENOENT || saved == ESRCH ? EBUSY : saved;
        return -1;
    }
    int fd = proc_open(process, "map_files", DIR_FLAGS, mount);
    if (fd < 0) {
        int saved = errno;
        if ((saved == ENOENT || saved == ESRCH) && proc_task_disappeared(process, mount) == 1) return 0;
        errno = saved == ENOENT || saved == ESRCH ? EBUSY : saved;
        return -1;
    }
    DIR *dir = fdopendir(fd);
    if (!dir) { close(fd); return -1; }
    int error = 0;
    for (;;) {
        if (proc_budget(scan) < 0) { error = errno; break; }
        errno = 0; struct dirent *entry = readdir(dir);
        if (!entry) { error = errno; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        if (!proc_mapfile_name(entry->d_name)) { error = EPROTO; break; }
        if (proc_reference(s, fd, entry->d_name, mount) < 0 && errno != ENOENT && errno != ESRCH) {
            error = errno; break;
        }
    }
    if (closedir(dir) < 0 && !error) error = errno;
    if (!error) {
        uint64_t current;
        state = proc_task_state(process, mount, &current);
        if (state < 0) error = errno;
        else if (!state && current != start_time) error = ESTALE;
    }
    return error ? (errno = error, -1) : 0;
}

static int proc_threads(struct install *s, int process, uint64_t mount, struct proc_scan *scan)
{
    uint64_t process_start;
    int process_state = proc_task_state(process, mount, &process_start);
    if (process_state == 1 || process_state == 2) return 0;
    if (process_state < 0) return -1;
    int fd = proc_open(process, "task", DIR_FLAGS, mount);
    if (fd < 0) {
        if (errno == ENOENT || errno == ESRCH) {
            uint64_t start;
            int state = proc_task_state(process, mount, &start);
            if (state == 1 || state == 2) return 0;
        }
        return -1;
    }
    stage = "recovery_proc_process_references";
    if (proc_process_references(s, process, mount, scan) < 0) { close(fd); return -1; }
    DIR *dir = fdopendir(fd);
    if (!dir) { close(fd); return -1; }
    int error = 0;
    for (;;) {
        if (proc_budget(scan) < 0) { error = errno; break; }
        errno = 0; struct dirent *entry = readdir(dir);
        if (!entry) { error = errno; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        if (!proc_number(entry->d_name)) { error = EPROTO; break; }
        int task = proc_open(fd, entry->d_name, DIR_FLAGS, mount);
        if (task < 0) {
            if (errno == ENOENT || errno == ESRCH) continue;
            error = errno; break;
        }
        stage = "recovery_proc_task_references";
        if (proc_task(s, task, mount, scan) < 0) error = errno;
        close(task);
        if (error) break;
    }
    if (closedir(dir) < 0 && !error) error = errno;
    return error ? (errno = error, -1) : 0;
}

static int proc_snapshot(struct install *s, int proc, uint64_t mount, pid_t own, struct proc_scan *scan)
{
    int fd = proc_open(proc, ".", DIR_FLAGS, mount);
    if (fd < 0) return -1;
    DIR *dir = fdopendir(fd);
    if (!dir) { close(fd); return -1; }
    char own_name[32];
    (void)snprintf(own_name, sizeof(own_name), "%ld", (long)own);
    int error = 0;
    for (;;) {
        if (proc_budget(scan) < 0) { error = errno; break; }
        errno = 0; struct dirent *entry = readdir(dir);
        if (!entry) { error = errno; break; }
        if (!proc_number(entry->d_name)) continue;
        if (!strcmp(entry->d_name, own_name)) continue;
        int process = proc_open(fd, entry->d_name, DIR_FLAGS, mount);
        if (process < 0) {
            if (errno == ENOENT || errno == ESRCH) continue;
            error = errno; break;
        }
        stage = "recovery_proc_threads";
        if (proc_threads(s, process, mount, scan) < 0) error = errno;
        close(process);
        if (error) break;
    }
    if (closedir(dir) < 0 && !error) error = errno;
    return error ? (errno = error, -1) : 0;
}

static int proc_self_pid(int proc, pid_t *pid)
{
    char bytes[32];
    ssize_t n = readlinkat(proc, "self", bytes, sizeof(bytes));
    if (n <= 0 || (size_t)n == sizeof(bytes)) { errno = ESTALE; return -1; }
    bytes[n] = 0;
    uint64_t number;
    if (!decimal(bytes, &number) || number > INT32_MAX) { errno = ESTALE; return -1; }
    *pid = (pid_t)number;
    return 0;
}

static int recovery_processes(struct install *s)
{
    struct __user_cap_header_struct header = { .version = _LINUX_CAPABILITY_VERSION_3 };
    struct __user_cap_data_struct caps[2] = {{0}};
    if (syscall(SYS_capget, &header, caps) < 0) return -1;
    if (!(caps[0].effective & (UINT32_C(1) << CAP_SYS_PTRACE))) { errno = EPERM; return -1; }
    uint64_t mount;
    int proc = protected_proc(&mount);
    if (proc < 0) return -1;
    pid_t own;
    if (proc_self_pid(proc, &own) < 0 || own != getpid()) { int saved = errno ? errno : ESTALE; close(proc); errno = saved; return -1; }
    char own_name[32];
    (void)snprintf(own_name, sizeof(own_name), "%ld", (long)own);
    int own_process = proc_open(proc, own_name, DIR_FLAGS, mount), error = 0;
    if (own_process < 0) error = errno;
    uint64_t own_start;
    int own_state = error ? -1 : proc_task_state(own_process, mount, &own_start);
    if (!error && own_state != 0) error = own_state < 0 ? errno : ESTALE;
    if (!error && (proc_reference(s, own_process, "root", mount) < 0 ||
        proc_reference(s, own_process, "cwd", mount) < 0)) error = errno;
    if (!error && (proc_task_state(own_process, mount, &own_start) != 0)) error = ESTALE;
    if (own_process >= 0) close(own_process);
    for (unsigned int pass = 0; pass < 2 && !error; ++pass) {
        struct proc_scan scan = {0};
        if (proc_snapshot(s, proc, mount, own, &scan) < 0) error = errno;
        if (!error && pass == 0 && poll(NULL, 0, 25) < 0 && errno != EINTR) error = errno;
    }
    close(proc);
    return error ? (errno = error, -1) : 0;
}

static int recover_remove(struct install *s)
{
    s->recovery_ready = false;
    char boot[36] = {0};
    struct stat manifest;
    if (fstat(s->manifest, &manifest) < 0) return -1;
    struct removal_receipt expected = { .magic = REMOVE_MAGIC, .identity = {
        s->base_stat.st_dev, s->base_stat.st_ino, s->root_stat.st_dev, s->root_stat.st_ino,
        s->views_stat.st_dev, s->views_stat.st_ino, manifest.st_dev, manifest.st_ino } };
    memcpy(expected.instance, s->name + strlen(PREFIX), sizeof(expected.instance));
    memcpy(expected.boot_id, boot, sizeof(boot));
    stage = "recovery_receipt";
    s->receipt = openat(s->base, REMOVE_RECEIPT, O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    if (s->receipt < 0) {
        if (errno != ENOENT || receipt_absent(s->base) < 0 || base_contents(s, 7U) < 0 ||
            installed_anchors_current(s, false) < 0 || read_boot_id(boot) < 0) return -1;
        memcpy(expected.boot_id, boot, sizeof(expected.boot_id));
        s->receipt_data = expected;
        if (receipt_create(s) < 0) return -1;
    } else {
    if (fstat(s->receipt, &s->receipt_stat) < 0 ||
        pread(s->receipt, &s->receipt_data, sizeof(s->receipt_data), 0) != (ssize_t)sizeof(s->receipt_data) ||
        !boot_id_valid(s->receipt_data.boot_id)) { errno = ESTALE; return -1; }
    memcpy(expected.boot_id, s->receipt_data.boot_id, sizeof(expected.boot_id));
    if (memcmp(&expected, &s->receipt_data, sizeof(expected)) || receipt_current(s) < 0 ||
        base_contents(s, 15U) < 0 || installed_anchors_current(s, false) < 0) { errno = ESTALE; return -1; }
    /* Re-sync also makes a valid receipt left by an interrupted first call durable. */
    if (fsync(s->receipt) < 0 || fsync(s->base) < 0) return -1;
    }
    stage = "recovery_lock";
    if (recovery_exclusive(s) < 0) return -1;
    stage = "recovery_processes";
    if (receipt_current(s) < 0 || installed_anchors_current(s, false) < 0) return -1;
    s->recovery_ready = true;
    stage = "recovery_targets";
    if (recovery_targets_prepare(s) < 0) return -1;
    stage = "recovery_processes";
    if (recovery_processes(s) < 0 || receipt_current(s) < 0 || installed_anchors_current(s, false) < 0) return -1;
    stage = "recovery_views";
    if (recovery_gate(s) < 0 || recovery_views(s, true) < 0) return -1;
    stage = "recovery_rootfs";
    return rollback(s);
}

static bool decimal(const char *text, uint64_t *value)
{
    if (!*text) return false;
    uint64_t result = 0;
    for (const char *p = text; *p; ++p) {
        if (*p < '0' || *p > '9' || result > (UINT64_MAX - (unsigned int)(*p - '0')) / 10) return false;
        result = result * 10 + (unsigned int)(*p - '0');
    }
    *value = result;
    return result != 0;
}

int main(int argc, char **argv)
{
    bool help = argc == 2 && !strcmp(argv[1], "--help");
    bool validate = argc == 2 && !strcmp(argv[1], "--validate");
    bool install = argc == 3 && !strcmp(argv[1], "--install") && nonce_valid(argv[2]);
    uint64_t device = 0, inode = 0;
    bool recover = argc == 5 && !strcmp(argv[1], "--recover-remove");
    bool remove = argc == 5 && (!strcmp(argv[1], "--remove") || recover) && nonce_valid(argv[2]) &&
                  decimal(argv[3], &device) && decimal(argv[4], &inode);
    if (help) { puts("--help | --validate (read-only stdin) | --install <24hex> | --remove <24hex> <device> <inode> | --recover-remove <24hex> <device> <inode>"); return 0; }
    if (!validate && !install && !remove) { puts("{\"passed\":false,\"stage\":\"arguments\",\"created_directory\":null}"); return 2; }
    int flags = fcntl(STDOUT_FILENO, F_GETFL);
    if (flags < 0 || fcntl(STDOUT_FILENO, F_SETFL, flags | O_NONBLOCK) < 0) return 3;
    (void)signal(SIGPIPE, SIG_IGN);
    struct install s = { .local = -1, .base = -1, .root = -1, .views = -1, .manifest = -1, .receipt = -1,
        .validate = validate, .recover_remove = remove };
    s.entries = calloc(MAX_ENTRIES, sizeof(*s.entries));
    s.index = calloc(HASH_SIZE, sizeof(*s.index));
    int rc = -1;
    struct sigaction timeout_action = { .sa_handler = on_timeout };
    sigemptyset(&timeout_action.sa_mask);
    int64_t now = monotonic_ms();
    bool ready = now >= 0 && sigaction(SIGALRM, &timeout_action, NULL) == 0;
    deadline = now + 30000;
    if (ready && !remove) {
        int input_flags = fcntl(STDIN_FILENO, F_GETFL);
        ready = input_flags >= 0 && fcntl(STDIN_FILENO, F_SETFL, input_flags | O_NONBLOCK) == 0;
    }
    (void)umask(0077);
    if (ready && s.entries && s.index) {
        if (install || remove) (void)snprintf(s.name, sizeof(s.name), "%s%s", PREFIX, argv[2]);
        if (remove) {
            if (load_existing(&s, device, inode) == 0)
                rc = recover_remove(&s);
        } else if (validate || setup(&s) == 0) rc = parse_stream(&s);
        if (rc == 0 && install) {
            rc = prepare_runtime_layout(&s);
            stage = "root_mode";
            if (rc == 0) rc = instance_current(&s);
            if (rc == 0) rc = fchmod(s.root, 0755);
            if (rc == 0) rc = fchmod(s.manifest, 0400);
            if (rc == 0) rc = fsync(s.manifest);
        }
    }
    int saved = errno;
    if (remove && rc < 0 && saved == EBUSY) stage = "recovery_busy";
    const char *cleanup = remove ? (rc == 0 ? "removed" : "cleanup_unknown") :
        (s.base_created ? "installed" : "not_created");
    if (rc < 0 && s.base_created && !remove) {
        timed_out = 0;
        deadline = monotonic_ms() + 5000;
        cleanup = rollback(&s) == 0 ? "removed" : "cleanup_unknown";
    }
    printf("{\"passed\":%s,\"stage\":\"%s\",\"errno\":%d,\"entries\":%u,\"file_bytes\":%" PRIu64 ","
           "\"cleanup_state\":\"%s\",\"device\":%ju,\"inode\":%ju,\"created_directory\":", rc == 0 ? "true" : "false", stage,
           rc == 0 ? 0 : saved, s.count, s.bytes, cleanup, (uintmax_t)s.base_stat.st_dev, (uintmax_t)s.base_stat.st_ino);
    if (s.base_created) printf("\"/data/local/%s\"}\n", s.name); else puts("null}");
    if (s.entries) for (uint32_t i = 0; i < s.count; ++i) free(s.entries[i].path);
    free(s.entries); free(s.index); free(s.proc_targets);
    if (s.receipt >= 0) close(s.receipt);
    if (s.views >= 0) close(s.views);
    if (s.manifest >= 0) close(s.manifest);
    if (s.root >= 0) close(s.root);
    if (s.base >= 0) close(s.base);
    if (s.local >= 0) close(s.local);
    return rc == 0 ? 0 : (!strcmp(cleanup, "cleanup_unknown") ? 5 : 3);
}
