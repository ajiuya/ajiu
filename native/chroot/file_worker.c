#define _GNU_SOURCE
#include "runtime_guest.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <limits.h>
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
#include <sys/prctl.h>
#include <sys/random.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/vfs.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <time.h>
#include <unistd.h>

#define FW_VERSION 1U
#define FW_HEADER 16U
#define FW_FRAME (1024U * 1024U)
#define FW_CHUNK 65536U
#define FW_PATH 4096U
#define FW_NAME 255U
#define FW_HANDLES 32U
#define FW_ENTRIES 10000U
#define FW_MOUNTS 32U
#define FW_BOUNDARIES 4096U
#define FW_MOUNTINFO (4U * 1024U * 1024U)
#define FW_WRITE_LIMIT (UINT64_C(512) * 1024U * 1024U)
#define FW_WAIT_MS 30000
#define FW_DF (O_PATH | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW)
#define FW_PF (O_PATH | O_CLOEXEC | O_NOFOLLOW)
#define FW_RESOLVE (RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS)

enum fw_op {
    FW_STAT = 1, FW_LIST, FW_RESOLVE_PATH, FW_OPEN_READ, FW_READ_CHUNK, FW_CLOSE,
    FW_BEGIN_WRITE, FW_WRITE_CHUNK, FW_COMMIT_WRITE, FW_ABORT, FW_MKDIRS, FW_DELETE, FW_RENAME
};

struct fw_header { uint16_t op; uint32_t id, length; };
struct fw_input { const unsigned char *bytes; size_t size, offset; };
struct fw_output { unsigned char *bytes; size_t size; };
struct fw_identity { struct statx st; };
struct fw_mount {
    uint64_t id;
    char path[FW_PATH + 1];
    bool writable;
    int fd;
    struct fw_identity identity;
};
struct fw_resolved {
    int fd, parent;
    char path[FW_PATH + 1], name[FW_NAME + 1];
    struct fw_identity identity;
    bool missing;
};
struct fw_handle {
    uint32_t number;
    bool writing, overwrite, poisoned, committed, metadata_applied;
    uid_t commit_uid;
    gid_t commit_gid;
    mode_t commit_mode;
    int fd, parent, original;
    uint64_t total;
    struct fw_identity identity, parent_identity, original_identity;
    char path[FW_PATH + 1], name[FW_NAME + 1], temporary[64];
};
struct fw_boundary { uint64_t id; char *path; };
struct fw_context {
    int root, proc, proc_self, proc_fds;
    struct fw_identity proc_identity;
    struct fw_mount mounts[FW_MOUNTS];
    size_t mount_count;
    struct fw_boundary *boundaries;
    size_t boundary_count, boundary_capacity;
    struct fw_handle handles[FW_HANDLES];
    uint64_t next_handle;
    int64_t deadline;
    bool cleanup_failed;
};

static volatile sig_atomic_t fw_stopping;

static int fw_fail(int error) { errno = error; return -1; }
static uint16_t fw_be16(const unsigned char *p) { return (uint16_t)(((uint16_t)p[0] << 8) | p[1]); }
static uint32_t fw_be32(const unsigned char *p)
{
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}
static void fw_put16(unsigned char *p, uint16_t n) { p[0] = (unsigned char)(n >> 8); p[1] = (unsigned char)n; }
static void fw_put32(unsigned char *p, uint32_t n)
{
    p[0] = (unsigned char)(n >> 24); p[1] = (unsigned char)(n >> 16);
    p[2] = (unsigned char)(n >> 8); p[3] = (unsigned char)n;
}

static bool fw_utf8(const unsigned char *p, size_t n)
{
    for (size_t i = 0; i < n;) {
        unsigned char c = p[i++];
        if (c == 0) return false;
        if (c < 0x80) continue;
        unsigned int need;
        uint32_t value, minimum;
        if (c >= 0xc2 && c <= 0xdf) { need = 1; value = c & 0x1fU; minimum = 0x80; }
        else if (c >= 0xe0 && c <= 0xef) { need = 2; value = c & 0x0fU; minimum = 0x800; }
        else if (c >= 0xf0 && c <= 0xf4) { need = 3; value = c & 7U; minimum = 0x10000; }
        else return false;
        if (need > n - i) return false;
        while (need--) {
            c = p[i++];
            if ((c & 0xc0U) != 0x80U) return false;
            value = (value << 6) | (c & 0x3fU);
        }
        if (value < minimum || value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff)) return false;
    }
    return true;
}

static bool fw_below(const char *path, const char *root)
{
    size_t n = strlen(root);
    return !strncmp(path, root, n) && (path[n] == 0 || path[n] == '/');
}

static bool fw_reserved(const char *path)
{
    static const char *const roots[] = { "/proc", "/dev", "/sys", "/tmp", "/run" };
    for (size_t i = 0; i < sizeof(roots) / sizeof(*roots); ++i)
        if (fw_below(path, roots[i])) return true;
    return false;
}

static int fw_path_valid(const char *path)
{
    size_t n = strlen(path);
    if (!n || path[0] != '/') return fw_fail(EINVAL);
    if (n > FW_PATH) return fw_fail(ENAMETOOLONG);
    if (!fw_utf8((const unsigned char *)path, n)) return fw_fail(EILSEQ);
    if (n == 1) return 0;
    const char *component = path + 1;
    for (const char *p = component;; ++p) {
        if (*p != '/' && *p) continue;
        size_t length = (size_t)(p - component);
        if (!length || (length == 1 && component[0] == '.') ||
            (length == 2 && component[0] == '.' && component[1] == '.')) return fw_fail(EINVAL);
        if (length > FW_NAME) return fw_fail(ENAMETOOLONG);
        if (!*p) break;
        component = p + 1;
    }
    return 0;
}

static int fw_input_path_valid(const char *path)
{
    size_t n = strnlen(path, FW_PATH + 1U);
    if (!n || path[0] != '/') return fw_fail(EINVAL);
    if (n > FW_PATH) return fw_fail(ENAMETOOLONG);
    if (!fw_utf8((const unsigned char *)path, n)) return fw_fail(EILSEQ);
    size_t component = 0;
    for (size_t i = 1; i < n; ++i) {
        if (path[i] == '/') component = 0;
        else if (++component > FW_NAME) return fw_fail(ENAMETOOLONG);
    }
    return 0;
}

static int fw_take32(struct fw_input *in, uint32_t *n)
{
    if (in->offset > in->size || in->size - in->offset < 4) return fw_fail(EINVAL);
    *n = fw_be32(in->bytes + in->offset); in->offset += 4; return 0;
}

static int fw_take_path(struct fw_input *in, char path[FW_PATH + 1])
{
    uint32_t length;
    if (fw_take32(in, &length) < 0) return -1;
    if (length > FW_PATH) return fw_fail(ENAMETOOLONG);
    if (length > in->size - in->offset) return fw_fail(EINVAL);
    if (!fw_utf8(in->bytes + in->offset, length)) return fw_fail(EILSEQ);
    memcpy(path, in->bytes + in->offset, length); path[length] = 0; in->offset += length;
    return fw_input_path_valid(path);
}

static int fw_end(const struct fw_input *in) { return in->offset == in->size ? 0 : fw_fail(EINVAL); }
static int fw_bool(struct fw_input *in, uint32_t *n)
{
    if (fw_take32(in, n) < 0) return -1;
    return *n <= 1 ? 0 : fw_fail(EINVAL);
}

static int fw_append(struct fw_output *out, const void *p, size_t size)
{
    if (out->size > FW_FRAME || size > FW_FRAME - out->size) return fw_fail(E2BIG);
    if (size) memcpy(out->bytes + out->size, p, size);
    out->size += size; return 0;
}
static int fw_out32(struct fw_output *out, uint32_t n)
{
    unsigned char p[4]; fw_put32(p, n); return fw_append(out, p, sizeof(p));
}
static int fw_out64(struct fw_output *out, uint64_t n)
{
    if (fw_out32(out, (uint32_t)(n >> 32)) < 0) return -1;
    return fw_out32(out, (uint32_t)n);
}
static int fw_text(struct fw_output *out, const char *s)
{
    size_t length = strlen(s);
    if (length > UINT32_MAX || fw_out32(out, (uint32_t)length) < 0) return -1;
    return fw_append(out, s, length);
}

static int fw_decode_header(const unsigned char bytes[FW_HEADER], struct fw_header *h)
{
    if (memcmp(bytes, "AJFW", 4) || fw_be16(bytes + 4) != FW_VERSION) return fw_fail(EPROTO);
    h->op = fw_be16(bytes + 6); h->id = fw_be32(bytes + 8); h->length = fw_be32(bytes + 12);
    if (h->op < FW_STAT || h->op > FW_RENAME || !h->id || h->length > FW_FRAME) return fw_fail(EPROTO);
    return 0;
}
static void fw_encode_header(unsigned char bytes[FW_HEADER], const struct fw_header *h, uint32_t length)
{
    memcpy(bytes, "AJFW", 4); fw_put16(bytes + 4, FW_VERSION); fw_put16(bytes + 6, h->op | 0x8000U);
    fw_put32(bytes + 8, h->id); fw_put32(bytes + 12, length);
}

static int64_t fw_now(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) < 0) return -1;
    if (ts.tv_sec < 0 || ts.tv_sec > (INT64_MAX - 1000) / 1000) return fw_fail(EOVERFLOW);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
static int fw_remaining(int64_t deadline)
{
    if (fw_stopping) return fw_fail(ECANCELED);
    int64_t now = fw_now();
    if (now < 0) return -1;
    if (now >= deadline) return fw_fail(ETIMEDOUT);
    int64_t left = deadline - now;
    return left > INT_MAX ? INT_MAX : (int)left;
}
static int fw_check(const struct fw_context *ctx) { return fw_remaining(ctx->deadline) < 0 ? -1 : 0; }

static int fw_transfer(int fd, unsigned char *bytes, size_t length, bool writing, bool eof_ok, int64_t deadline)
{
    size_t offset = 0;
    while (offset < length) {
        int left = fw_remaining(deadline);
        if (left < 0) return -1;
        ssize_t n = writing ? write(fd, bytes + offset, length - offset) : read(fd, bytes + offset, length - offset);
        if (n > 0) { offset += (size_t)n; continue; }
        if (!n) return !writing && !offset && eof_ok ? 1 : fw_fail(writing ? EIO : EPROTO);
        if (errno == EINTR) continue;
        if (errno != EAGAIN && errno != EWOULDBLOCK) return -1;
        struct pollfd p = { .fd = fd, .events = writing ? POLLOUT : POLLIN };
        int rc = poll(&p, 1, left);
        if (rc < 0) { if (errno == EINTR) continue; return -1; }
        if (!rc) return fw_fail(ETIMEDOUT);
        if (p.revents & POLLNVAL) return fw_fail(EBADF);
        if (writing && (p.revents & (POLLERR | POLLHUP))) return fw_fail(EPIPE);
    }
    return 0;
}

static int fw_open(int parent, const char *name, int flags, mode_t mode, uint64_t resolve)
{
    struct open_how how = { .flags = (uint64_t)(unsigned int)flags, .mode = mode, .resolve = resolve };
    return (int)syscall(SYS_openat2, parent, name, &how, sizeof(how));
}
static int fw_dup(int fd) { return fcntl(fd, F_DUPFD_CLOEXEC, 3); }
static int fw_identify(int fd, struct fw_identity *id)
{
    memset(id, 0, sizeof(*id));
    if (syscall(SYS_statx, fd, "", AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW,
                STATX_BASIC_STATS | STATX_MNT_ID, &id->st) < 0) return -1;
    uint32_t required = STATX_TYPE | STATX_MODE | STATX_NLINK | STATX_UID | STATX_GID |
        STATX_INO | STATX_SIZE | STATX_MTIME | STATX_MNT_ID;
    if ((id->st.stx_mask & required) != required) return fw_fail(EOPNOTSUPP);
    return 0;
}
static bool fw_same(const struct fw_identity *a, const struct fw_identity *b)
{
    return a->st.stx_mnt_id == b->st.stx_mnt_id && a->st.stx_ino == b->st.stx_ino &&
        a->st.stx_dev_major == b->st.stx_dev_major && a->st.stx_dev_minor == b->st.stx_dev_minor &&
        (a->st.stx_mode & S_IFMT) == (b->st.stx_mode & S_IFMT);
}
static int fw_fd_same(int fd, const struct fw_identity *expected)
{
    struct fw_identity current;
    if (fw_identify(fd, &current) < 0) return -1;
    return fw_same(&current, expected) ? 0 : fw_fail(ESTALE);
}
static int fw_named_same(int parent, const char *name, const struct fw_identity *expected)
{
    int fd = fw_open(parent, name, FW_PF, 0, FW_RESOLVE);
    if (fd < 0) return errno == ENOENT ? fw_fail(ESTALE) : -1;
    int rc = fw_fd_same(fd, expected), saved = errno; close(fd); errno = saved; return rc;
}
static int fw_kind(const struct fw_identity *id)
{
    mode_t mode = id->st.stx_mode;
    if (S_ISDIR(mode)) return 1;
    if (S_ISREG(mode)) return 2;
    if (S_ISLNK(mode)) return 3;
    return fw_fail(EOPNOTSUPP);
}
static int fw_metadata(struct fw_output *out, const struct fw_identity *id)
{
    int kind = fw_kind(id);
    if (kind < 0) return -1;
    int64_t seconds = id->st.stx_mtime.tv_sec;
    if (seconds < 0 || (uint64_t)seconds > (UINT64_MAX - 999) / 1000) return fw_fail(EOVERFLOW);
    uint64_t millis = (uint64_t)seconds * 1000 + id->st.stx_mtime.tv_nsec / 1000000U;
    if (fw_out32(out, (uint32_t)kind) < 0 || fw_out32(out, id->st.stx_mode) < 0 ||
        fw_out64(out, id->st.stx_size) < 0 || fw_out64(out, millis) < 0 ||
        fw_out64(out, (uint64_t)makedev(id->st.stx_dev_major, id->st.stx_dev_minor)) < 0) return -1;
    return fw_out64(out, id->st.stx_ino);
}

static int fw_mount_class(const char *path, bool *must_ro)
{
    *must_ro = false;
    if (!strcmp(path, "/")) return 1;
    if (!strcmp(path, "/root") || !strcmp(path, "/root/workspace")) return 1;
    if (!strcmp(path, "/etc/resolv.conf")) { *must_ro = true; return 2; }
    static const char *const ro[] = { "skills", "agents", "docs", "bin", "lib", "prompts.custom" };
    static const char *const rw[] = { "memory", "scripts", "plugins", "tool-output" };
    const char *prefix = "/root/.aicode/";
    if (strncmp(path, prefix, strlen(prefix))) return 0;
    const char *name = path + strlen(prefix);
    for (size_t i = 0; i < sizeof(ro) / sizeof(*ro); ++i)
        if (!strcmp(name, ro[i])) { *must_ro = true; return 1; }
    for (size_t i = 0; i < sizeof(rw) / sizeof(*rw); ++i)
        if (!strcmp(name, rw[i])) return 1;
    return 0;
}

static bool fw_option(const char *options, const char *option)
{
    size_t n = strlen(option);
    for (const char *p = options; *p;) {
        const char *end = strchr(p, ',');
        size_t length = end ? (size_t)(end - p) : strlen(p);
        if (length == n && !memcmp(p, option, n)) return true;
        if (!end) break;
        p = end + 1;
    }
    return false;
}
static int fw_unescape(const char *source, char target[FW_PATH + 1])
{
    size_t used = 0;
    for (size_t i = 0; source[i]; ++i) {
        unsigned char c = (unsigned char)source[i];
        if (c == '\\') {
            if (strlen(source + i) < 4 || source[i + 1] < '0' || source[i + 1] > '7' ||
                source[i + 2] < '0' || source[i + 2] > '7' || source[i + 3] < '0' || source[i + 3] > '7')
                return fw_fail(EPROTO);
            unsigned int decoded = (unsigned int)(source[i + 1] - '0') * 64U +
                (unsigned int)(source[i + 2] - '0') * 8U + (unsigned int)(source[i + 3] - '0');
            if (!decoded || decoded > UCHAR_MAX) return fw_fail(EPROTO);
            c = (unsigned char)decoded; i += 3;
        }
        if (used == FW_PATH) return fw_fail(ENAMETOOLONG);
        target[used++] = (char)c;
    }
    target[used] = 0;
    return fw_path_valid(target);
}

static int fw_mount_line(char *line, uint64_t *id, char path[FW_PATH + 1], bool *readonly)
{
    char *tokens[6], *save = NULL;
    for (size_t i = 0; i < 6; ++i) {
        tokens[i] = strtok_r(i ? NULL : line, " ", &save);
        if (!tokens[i]) return fw_fail(EPROTO);
    }
    if (!save || (strncmp(save, "- ", 2) && !strstr(save, " - "))) return fw_fail(EPROTO);
    if (!tokens[0][0]) return fw_fail(EPROTO);
    uint64_t value = 0;
    for (const char *p = tokens[0]; *p; ++p) {
        if (*p < '0' || *p > '9' || value > (UINT64_MAX - (unsigned int)(*p - '0')) / 10)
            return fw_fail(EPROTO);
        value = value * 10 + (unsigned int)(*p - '0');
    }
    if (!value || fw_unescape(tokens[4], path) < 0) return fw_fail(EPROTO);
    bool ro = fw_option(tokens[5], "ro"), rw = fw_option(tokens[5], "rw");
    if (ro == rw) return fw_fail(EPROTO);
    *id = value; *readonly = ro; return 0;
}

static const struct fw_mount *fw_find_mount(const struct fw_context *ctx, uint64_t id)
{
    for (size_t i = 0; i < ctx->mount_count; ++i)
        if (ctx->mounts[i].id == id) return &ctx->mounts[i];
    return NULL;
}
static int fw_authorize(const struct fw_context *ctx, int fd, const struct fw_identity *id,
                        const char *path, bool writing)
{
    if (fw_reserved(path)) return fw_fail(EACCES);
    const struct fw_mount *m = fw_find_mount(ctx, id->st.stx_mnt_id);
    if (!m) return fw_fail(EXDEV);
    if (strcmp(m->path, "/") && !fw_below(path, m->path)) return fw_fail(EXDEV);
    size_t mount_path_length = strlen(m->path);
    for (size_t i = 0; i < ctx->boundary_count; ++i)
        if (strlen(ctx->boundaries[i].path) > mount_path_length &&
            fw_below(path, ctx->boundaries[i].path) && ctx->boundaries[i].id != m->id) return fw_fail(EXDEV);
    if (fw_below(path, "/root/.aicode") && strcmp(m->path, "/root") &&
        strncmp(m->path, "/root/.aicode/", 14)) return fw_fail(EACCES);
    if (fw_kind(id) < 0) return -1;
    if (writing) {
        struct statvfs fs;
        if (!m->writable) return fw_fail(EROFS);
        if (fstatvfs(fd, &fs) < 0) return -1;
        if (fs.f_flag & ST_RDONLY) return fw_fail(EROFS);
    }
    return 0;
}
static int fw_protected(const struct fw_context *ctx, const char *path)
{
    if (!strcmp(path, "/") || !strcmp(path, "/root/.aicode")) return fw_fail(EBUSY);
    for (size_t i = 0; i < ctx->boundary_count; ++i)
        if (fw_below(ctx->boundaries[i].path, path)) return fw_fail(EBUSY);
    return 0;
}

static int fw_pin_proc(struct fw_context *ctx)
{
    ctx->proc = fw_open(ctx->root, "/proc", FW_DF, 0,
        RESOLVE_IN_ROOT | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS);
    if (ctx->proc < 0) return -1;
    struct statfs fs;
    struct statvfs flags;
    if (fstatfs(ctx->proc, &fs) < 0 || fstatvfs(ctx->proc, &flags) < 0 ||
        fw_identify(ctx->proc, &ctx->proc_identity) < 0) return -1;
    if (fs.f_type != PROC_SUPER_MAGIC || !(flags.f_flag & ST_RDONLY)) return fw_fail(EACCES);
    char pid[32];
    int n = snprintf(pid, sizeof(pid), "%ld", (long)getpid());
    if (n <= 0 || (size_t)n >= sizeof(pid)) return fw_fail(EOVERFLOW);
    ctx->proc_self = fw_open(ctx->proc, pid, FW_DF, 0, FW_RESOLVE | RESOLVE_NO_XDEV);
    if (ctx->proc_self < 0) return -1;
    ctx->proc_fds = fw_open(ctx->proc_self, "fd", FW_DF, 0, FW_RESOLVE | RESOLVE_NO_XDEV);
    if (ctx->proc_fds < 0) return -1;
    struct fw_identity identity;
    if (fstatfs(ctx->proc_fds, &fs) < 0 || fw_identify(ctx->proc_fds, &identity) < 0) return -1;
    if (fs.f_type != PROC_SUPER_MAGIC || identity.st.stx_mnt_id != ctx->proc_identity.st.stx_mnt_id)
        return fw_fail(EACCES);
    return 0;
}

static int fw_add_boundary(struct fw_context *ctx, uint64_t id, const char *path)
{
    if (ctx->boundary_count == FW_BOUNDARIES) return fw_fail(E2BIG);
    if (ctx->boundary_count == ctx->boundary_capacity) {
        size_t capacity = ctx->boundary_capacity ? ctx->boundary_capacity * 2 : 32;
        void *p = realloc(ctx->boundaries, capacity * sizeof(*ctx->boundaries));
        if (!p) return -1;
        ctx->boundaries = p; ctx->boundary_capacity = capacity;
    }
    char *copy = strdup(path);
    if (!copy) return -1;
    ctx->boundaries[ctx->boundary_count++] = (struct fw_boundary){ .id = id, .path = copy };
    return 0;
}

static int fw_load_mounts(struct fw_context *ctx)
{
    int fd = fw_open(ctx->proc_self, "mountinfo", O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK,
        0, FW_RESOLVE | RESOLVE_NO_XDEV);
    if (fd < 0) return -1;
    char *bytes = malloc(FW_MOUNTINFO + 1U);
    if (!bytes) { close(fd); return -1; }
    size_t used = 0;
    int error = 0;
    for (;;) {
        if (used == FW_MOUNTINFO) { error = E2BIG; break; }
        ssize_t n = read(fd, bytes + used, FW_MOUNTINFO - used);
        if (n > 0) { used += (size_t)n; continue; }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) error = errno;
        break;
    }
    close(fd);
    if (!error && (!used || bytes[used - 1] != '\n' || memchr(bytes, 0, used))) error = EPROTO;
    bytes[used] = 0;
    bool have_root = false, have_home = false, have_workspace = false;
    char *save = NULL;
    for (char *line = error ? NULL : strtok_r(bytes, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        uint64_t id; char path[FW_PATH + 1]; bool ro, must_ro;
        if (fw_mount_line(line, &id, path, &ro) < 0 || fw_add_boundary(ctx, id, path) < 0) { error = errno; break; }
        int kind = fw_mount_class(path, &must_ro);
        if (!kind) continue;
        int anchor = fw_open(ctx->root, path, FW_PF, 0,
            RESOLVE_IN_ROOT | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS);
        if (anchor < 0) { error = errno; break; }
        struct fw_identity identity;
        struct statvfs fs;
        if (fw_identify(anchor, &identity) < 0 || fstatvfs(anchor, &fs) < 0) {
            error = errno; close(anchor); break;
        }
        if (identity.st.stx_mnt_id != id) { close(anchor); continue; }
        if ((kind == 1 ? !S_ISDIR(identity.st.stx_mode) : !S_ISREG(identity.st.stx_mode)) ||
            (must_ro && (!ro || !(fs.f_flag & ST_RDONLY))) || ro != ((fs.f_flag & ST_RDONLY) != 0) ||
            fw_find_mount(ctx, id) || ctx->mount_count == FW_MOUNTS) {
            error = EACCES; close(anchor); break;
        }
        struct fw_mount *mount = &ctx->mounts[ctx->mount_count++];
        mount->id = id; mount->fd = anchor; mount->identity = identity; mount->writable = !ro;
        memcpy(mount->path, path, strlen(path) + 1);
        if (!strcmp(path, "/")) have_root = true;
        if (!strcmp(path, "/root")) have_home = true;
        if (!strcmp(path, "/root/workspace")) have_workspace = true;
    }
    free(bytes);
    if (!error && (!have_root || !have_home || !have_workspace)) error = EACCES;
    return error ? fw_fail(error) : 0;
}

static void fw_resolved_init(struct fw_resolved *r)
{
    memset(r, 0, sizeof(*r)); r->fd = r->parent = -1;
}
static void fw_resolved_close(struct fw_resolved *r)
{
    int saved = errno;
    if (r->fd >= 0) close(r->fd);
    if (r->parent >= 0) close(r->parent);
    r->fd = r->parent = -1; errno = saved;
}
static int fw_join(char path[FW_PATH + 1], const char *parent, const char *name)
{
    size_t a = strlen(parent), b = strlen(name), slash = a == 1 ? 0 : 1;
    if (a + slash + b > FW_PATH) return fw_fail(ENAMETOOLONG);
    memcpy(path, parent, a);
    if (slash) path[a++] = '/';
    memcpy(path + a, name, b + 1); return 0;
}

struct fw_walk {
    int fds[FW_PATH / 2 + 2];
    size_t lengths[FW_PATH / 2 + 2], depth;
    char current[FW_PATH + 1], pending[FW_PATH + 1];
};

static int fw_resolve(const struct fw_context *ctx, const char *path, bool follow_final, unsigned int missing,
                      struct fw_resolved *result)
{
    fw_resolved_init(result);
    if (fw_input_path_valid(path) < 0) return -1;
    if (fw_reserved(path)) return fw_fail(EACCES);
    struct fw_walk *w = calloc(1, sizeof(*w));
    if (!w) return -1;
    w->fds[0] = fw_dup(ctx->root);
    if (w->fds[0] < 0) { free(w); return -1; }
    strcpy(w->current, "/"); w->lengths[0] = 1; strcpy(w->pending, path + 1);
    unsigned int links = 0;
    int error = 0;
    for (;;) {
        if (fw_check(ctx) < 0) { error = errno; break; }
        char *start = w->pending;
        while (*start == '/') ++start;
        if (!*start) {
            result->fd = fw_dup(w->fds[w->depth]);
            if (result->fd < 0 || fw_identify(result->fd, &result->identity) < 0 ||
                fw_authorize(ctx, result->fd, &result->identity, w->current, false) < 0) { error = errno; break; }
            strcpy(result->path, w->current);
            if (w->depth) {
                result->parent = fw_dup(w->fds[w->depth - 1]);
                const char *name = strrchr(w->current, '/') + 1;
                strcpy(result->name, name);
                if (result->parent < 0) error = errno;
            }
            break;
        }
        char *end = strchr(start, '/');
        size_t length = end ? (size_t)(end - start) : strlen(start);
        char name[FW_NAME + 1], rest[FW_PATH + 1], current[FW_PATH + 1];
        if (length > FW_NAME) { error = ENAMETOOLONG; break; }
        memcpy(name, start, length); name[length] = 0;
        strcpy(rest, end ? end + 1 : "");
        bool final = rest[strspn(rest, "/")] == 0;
        if (!strcmp(name, ".")) { strcpy(w->pending, rest); continue; }
        if (!strcmp(name, "..")) {
            if (w->depth) close(w->fds[w->depth--]);
            w->current[w->lengths[w->depth]] = 0; strcpy(w->pending, rest); continue;
        }
        if (fw_join(current, w->current, name) < 0) { error = errno; break; }
        if (fw_reserved(current)) { error = EACCES; break; }
        int fd = fw_open(w->fds[w->depth], name, FW_PF, 0, FW_RESOLVE);
        if (fd < 0) {
            if (errno != ENOENT || !missing || (final && end) || (!final && missing != 2)) { error = errno; break; }
            strcpy(result->path, current);
            if (!final) {
                char remainder[FW_PATH + 1];
                if (fw_join(remainder, current, rest) < 0) { error = errno; break; }
                if (fw_path_valid(remainder) < 0) { error = errno == EINVAL ? ENOENT : errno; break; }
                if (fw_reserved(remainder)) { error = EACCES; break; }
                strcpy(result->path, remainder);
            }
            result->parent = fw_dup(w->fds[w->depth]);
            if (result->parent < 0) { error = errno; break; }
            strcpy(result->name, name); result->missing = true;
            if (fw_identify(result->parent, &result->identity) < 0 ||
                fw_authorize(ctx, result->parent, &result->identity, result->path, false) < 0) error = errno;
            break;
        }
        struct fw_identity identity;
        if (fw_identify(fd, &identity) < 0 || fw_authorize(ctx, fd, &identity, current, false) < 0) {
            error = errno; close(fd); break;
        }
        if (S_ISLNK(identity.st.stx_mode) && (!final || follow_final)) {
            if (++links > 40) { error = ELOOP; close(fd); break; }
            char link[FW_PATH + 1];
            ssize_t n = readlinkat(fd, "", link, sizeof(link));
            int saved = errno; close(fd);
            if (n <= 0 || n > (ssize_t)FW_PATH) { error = n < 0 ? saved : ENAMETOOLONG; break; }
            if (!fw_utf8((unsigned char *)link, (size_t)n)) { error = EILSEQ; break; }
            link[n] = 0;
            size_t tail = strlen(rest);
            if ((size_t)n + (end ? 1U : 0U) + tail > FW_PATH) { error = ENAMETOOLONG; break; }
            if (link[0] == '/') {
                while (w->depth) close(w->fds[w->depth--]);
                strcpy(w->current, "/");
            }
            memcpy(w->pending, link, (size_t)n);
            if (end) { w->pending[n] = '/'; memcpy(w->pending + n + 1, rest, tail + 1); }
            else w->pending[n] = 0;
            continue;
        }
        if (final) {
            if (end && !S_ISDIR(identity.st.stx_mode) && !S_ISLNK(identity.st.stx_mode)) {
                error = ENOTDIR; close(fd); break;
            }
            result->fd = fd; result->identity = identity; strcpy(result->path, current); strcpy(result->name, name);
            result->parent = fw_dup(w->fds[w->depth]);
            if (result->parent < 0) error = errno;
            break;
        }
        if (!S_ISDIR(identity.st.stx_mode)) { error = ENOTDIR; close(fd); break; }
        if (w->depth + 1 >= sizeof(w->fds) / sizeof(*w->fds)) { error = ENAMETOOLONG; close(fd); break; }
        w->fds[++w->depth] = fd; strcpy(w->current, current); w->lengths[w->depth] = strlen(current);
        strcpy(w->pending, rest);
    }
    for (size_t i = 0; i <= w->depth; ++i) close(w->fds[i]);
    free(w);
    if (error) { fw_resolved_close(result); return fw_fail(error); }
    return 0;
}

static int fw_directory_read(int anchor, const struct fw_identity *identity)
{
    if (!S_ISDIR(identity->st.stx_mode) || fw_fd_same(anchor, identity) < 0) return fw_fail(ESTALE);
    int fd = fw_open(anchor, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK, 0, FW_RESOLVE);
    if (fd < 0) return -1;
    if (fw_fd_same(fd, identity) < 0) { int saved = errno; close(fd); return fw_fail(saved); }
    return fd;
}
static int fw_regular_read(const struct fw_context *ctx, int anchor, const struct fw_identity *identity, const char *path)
{
    if (!S_ISREG(identity->st.stx_mode)) return fw_fail(EOPNOTSUPP);
    if (fw_fd_same(anchor, identity) < 0 || fw_authorize(ctx, anchor, identity, path, false) < 0) return -1;
    char number[32];
    int n = snprintf(number, sizeof(number), "%d", anchor);
    if (n <= 0 || (size_t)n >= sizeof(number)) return fw_fail(EOVERFLOW);
    /* Only a pinned, authorized regular FD is upgraded through our pinned real procfs; no name reopen can touch a raced-in device. */
    int fd = openat(ctx->proc_fds, number, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return -1;
    if (fw_fd_same(fd, identity) < 0 || fw_authorize(ctx, fd, identity, path, false) < 0) {
        int saved = errno; close(fd); return fw_fail(saved);
    }
    return fd;
}
static int fw_readable(const struct fw_context *ctx, const struct fw_resolved *r, bool directory)
{
    if (r->missing) return fw_fail(ENOENT);
    if (directory ? !S_ISDIR(r->identity.st.stx_mode) : !S_ISREG(r->identity.st.stx_mode))
        return fw_fail(directory ? ENOTDIR : EOPNOTSUPP);
    if (fw_authorize(ctx, r->fd, &r->identity, r->path, false) < 0) return -1;
    return directory ? fw_directory_read(r->fd, &r->identity) : fw_regular_read(ctx, r->fd, &r->identity, r->path);
}
static int fw_current(const struct fw_context *ctx, int fd, const struct fw_identity *id, const char *path, bool writing)
{
    if (fw_check(ctx) < 0 || fw_fd_same(fd, id) < 0 || fw_authorize(ctx, fd, id, path, writing) < 0) return -1;
    int current = fw_open(ctx->root, path, FW_PF, 0,
        RESOLVE_IN_ROOT | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS);
    if (current < 0) return errno == ENOENT ? fw_fail(ESTALE) : -1;
    int rc = fw_fd_same(current, id), saved = errno; close(current); errno = saved; return rc;
}
static int fw_write_target(const struct fw_context *ctx, const struct fw_resolved *r)
{
    if (r->parent < 0 || fw_protected(ctx, r->path) < 0) return fw_fail(EBUSY);
    struct fw_identity parent;
    if (fw_identify(r->parent, &parent) < 0) return -1;
    char path[FW_PATH + 1]; strcpy(path, r->path);
    char *slash = strrchr(path, '/'); if (slash == path) slash[1] = 0; else *slash = 0;
    if (fw_current(ctx, r->parent, &parent, path, true) < 0) return -1;
    if (r->missing) return fw_authorize(ctx, r->parent, &parent, r->path, true);
    if (r->identity.st.stx_mnt_id != parent.st.stx_mnt_id) return fw_fail(EXDEV);
    return fw_current(ctx, r->fd, &r->identity, r->path, true);
}

static int fw_stat_request(struct fw_context *ctx, struct fw_input *in, struct fw_output *out)
{
    char path[FW_PATH + 1]; uint32_t follow;
    if (fw_take_path(in, path) < 0 || fw_bool(in, &follow) < 0 || fw_end(in) < 0) return -1;
    struct fw_resolved r;
    if (fw_resolve(ctx, path, follow != 0, 0, &r) < 0) return -1;
    int rc = fw_metadata(out, &r.identity); fw_resolved_close(&r); return rc;
}
static int fw_resolve_request(struct fw_context *ctx, struct fw_input *in, struct fw_output *out)
{
    char path[FW_PATH + 1]; uint32_t follow;
    if (fw_take_path(in, path) < 0 || fw_bool(in, &follow) < 0 || fw_end(in) < 0) return -1;
    struct fw_resolved r;
    if (fw_resolve(ctx, path, follow != 0, 2, &r) < 0) return -1;
    int rc = fw_text(out, r.path); fw_resolved_close(&r); return rc;
}
static int fw_entry_name(const char *name)
{
    size_t length = strlen(name);
    if (!length || !strcmp(name, ".") || !strcmp(name, "..") || strchr(name, '/')) return fw_fail(EINVAL);
    if (length > FW_NAME) return fw_fail(ENAMETOOLONG);
    return fw_utf8((const unsigned char *)name, length) ? 0 : fw_fail(EILSEQ);
}
static int fw_list_request(struct fw_context *ctx, struct fw_input *in, struct fw_output *out)
{
    char path[FW_PATH + 1];
    if (fw_take_path(in, path) < 0 || fw_end(in) < 0) return -1;
    struct fw_resolved r;
    if (fw_resolve(ctx, path, true, 0, &r) < 0) return -1;
    int fd = fw_readable(ctx, &r, true);
    if (fd < 0) { fw_resolved_close(&r); return -1; }
    DIR *dir = fdopendir(fd);
    if (!dir) { int saved = errno; close(fd); fw_resolved_close(&r); return fw_fail(saved); }
    size_t count_offset = out->size;
    int error = fw_out32(out, 0) < 0 ? errno : 0;
    uint32_t count = 0;
    while (!error) {
        if (fw_check(ctx) < 0) { error = errno; break; }
        errno = 0; struct dirent *entry = readdir(dir);
        if (!entry) { error = errno; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        char child_path[FW_PATH + 1];
        if (fw_entry_name(entry->d_name) < 0 || fw_join(child_path, r.path, entry->d_name) < 0) { error = errno; break; }
        if (fw_reserved(child_path)) continue;
        int child = fw_open(fd, entry->d_name, FW_PF, 0, FW_RESOLVE);
        if (child < 0) { error = errno; break; }
        struct fw_identity identity;
        if (fw_identify(child, &identity) < 0) { error = errno; close(child); break; }
        if (fw_authorize(ctx, child, &identity, child_path, false) < 0) {
            int policy_error = errno; close(child);
            if (policy_error == EACCES || policy_error == EXDEV || policy_error == EOPNOTSUPP) continue;
            error = policy_error; break;
        }
        if (count == FW_ENTRIES) error = E2BIG;
        else if (fw_text(out, entry->d_name) < 0 || fw_metadata(out, &identity) < 0) error = errno;
        close(child);
        if (!error) ++count;
    }
    if (!error && fw_current(ctx, r.fd, &r.identity, r.path, false) < 0) error = errno;
    if (closedir(dir) < 0 && !error) error = errno;
    fw_resolved_close(&r);
    if (error) return fw_fail(error);
    fw_put32(out->bytes + count_offset, count); return 0;
}

static void fw_handle_init(struct fw_handle *h)
{
    memset(h, 0, sizeof(*h)); h->fd = h->parent = h->original = -1;
}
static struct fw_handle *fw_find_handle(struct fw_context *ctx, uint32_t number)
{
    if (number) for (size_t i = 0; i < FW_HANDLES; ++i)
        if (ctx->handles[i].number == number) return &ctx->handles[i];
    errno = EBADF; return NULL;
}
static struct fw_handle *fw_new_handle(struct fw_context *ctx)
{
    if (!ctx->next_handle || ctx->next_handle > UINT32_MAX) { errno = EOVERFLOW; return NULL; }
    for (size_t i = 0; i < FW_HANDLES; ++i) if (!ctx->handles[i].number) {
        struct fw_handle *h = &ctx->handles[i];
        fw_handle_init(h); h->number = (uint32_t)ctx->next_handle++; return h;
    }
    errno = EMFILE; return NULL;
}
static int fw_drop_handle(struct fw_context *ctx, struct fw_handle *h, bool cleanup)
{
    int error = 0;
    if (cleanup && h->writing && !h->committed && h->temporary[0]) {
        if (h->fd < 0 || h->parent < 0 || fw_fd_same(h->fd, &h->identity) < 0 ||
            fw_fd_same(h->parent, &h->parent_identity) < 0 || fw_named_same(h->parent, h->temporary, &h->identity) < 0)
            error = errno ? errno : ESTALE;
        else if (unlinkat(h->parent, h->temporary, 0) < 0) error = errno;
    }
    if (h->fd >= 0 && close(h->fd) < 0 && !error) error = errno;
    if (h->parent >= 0 && close(h->parent) < 0 && !error) error = errno;
    if (h->original >= 0 && close(h->original) < 0 && !error) error = errno;
    if (error && cleanup && h->writing && !h->committed && h->temporary[0]) ctx->cleanup_failed = true;
    fw_handle_init(h);
    return error ? fw_fail(error) : 0;
}
static int fw_open_read_request(struct fw_context *ctx, struct fw_input *in, struct fw_output *out)
{
    char path[FW_PATH + 1];
    if (fw_take_path(in, path) < 0 || fw_end(in) < 0) return -1;
    struct fw_resolved r;
    if (fw_resolve(ctx, path, true, 0, &r) < 0) return -1;
    int fd = fw_readable(ctx, &r, false);
    if (fd < 0) { fw_resolved_close(&r); return -1; }
    struct fw_handle *h = fw_new_handle(ctx);
    if (!h) { int saved = errno; close(fd); fw_resolved_close(&r); return fw_fail(saved); }
    h->fd = fd; h->identity = r.identity; strcpy(h->path, r.path);
    fw_resolved_close(&r);
    return fw_out32(out, h->number);
}
static int fw_read_chunk_request(struct fw_context *ctx, struct fw_input *in, struct fw_output *out)
{
    uint32_t number, length;
    if (fw_take32(in, &number) < 0 || fw_take32(in, &length) < 0 || fw_end(in) < 0) return -1;
    if (length > FW_CHUNK) return fw_fail(EINVAL);
    struct fw_handle *h = fw_find_handle(ctx, number);
    if (!h) return -1;
    if (h->writing) return fw_fail(EBADF);
    if (fw_fd_same(h->fd, &h->identity) < 0 || fw_authorize(ctx, h->fd, &h->identity, h->path, false) < 0) return -1;
    unsigned char bytes[FW_CHUNK]; ssize_t n;
    do {
        if (fw_check(ctx) < 0) return -1;
        n = read(h->fd, bytes, length);
    } while (n < 0 && errno == EINTR);
    if (n < 0) return -1;
    if (fw_out32(out, (uint32_t)n) < 0) return -1;
    return fw_append(out, bytes, (size_t)n);
}
static int fw_close_request(struct fw_context *ctx, struct fw_input *in, bool aborting)
{
    uint32_t number;
    if (fw_take32(in, &number) < 0 || fw_end(in) < 0) return -1;
    struct fw_handle *h = fw_find_handle(ctx, number);
    if (!h) return -1;
    if (h->writing != aborting) return fw_fail(EBADF);
    return fw_drop_handle(ctx, h, aborting);
}

static int fw_random_name(char name[64])
{
    unsigned char bytes[16]; size_t used = 0;
    while (used < sizeof(bytes)) {
        if (fw_stopping) return fw_fail(ECANCELED);
        ssize_t n = getrandom(bytes + used, sizeof(bytes) - used, GRND_NONBLOCK);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return n < 0 ? -1 : fw_fail(EIO);
        used += (size_t)n;
    }
    static const char hex[] = "0123456789abcdef";
    memcpy(name, ".ajfw-", 6);
    for (size_t i = 0; i < sizeof(bytes); ++i) {
        name[6 + i * 2] = hex[bytes[i] >> 4]; name[7 + i * 2] = hex[bytes[i] & 15];
    }
    name[38] = 0; return 0;
}
static int fw_creation_owner(const struct fw_context *ctx, uint64_t mount_id, uid_t *uid, gid_t *gid)
{
    const struct fw_mount *mount = fw_find_mount(ctx, mount_id);
    if (!mount) return fw_fail(EXDEV);
    if (geteuid() != 0) { *uid = geteuid(); *gid = getegid(); return 0; }
    *uid = 0; *gid = 0;
    if (!strcmp(mount->path, "/")) return 0;
    /* New files in App-owned binds must remain editable by the App, not owned by guest root.
     * Derive this from the pinned bind source, not an environment variable or a new wire field. */
    const struct fw_mount *home = NULL;
    for (size_t i = 0; i < ctx->mount_count; ++i)
        if (!strcmp(ctx->mounts[i].path, "/root")) { home = &ctx->mounts[i]; break; }
    if (!home) return fw_fail(EACCES);
    struct fw_identity current;
    if (fw_identify(home->fd, &current) < 0) return -1;
    if (!fw_same(&current, &home->identity) || current.st.stx_uid != home->identity.st.stx_uid ||
        current.st.stx_gid != home->identity.st.stx_gid) return fw_fail(ESTALE);
    if (current.st.stx_uid < 10000 || current.st.stx_uid > INT_MAX ||
        current.st.stx_gid != current.st.stx_uid) return fw_fail(EACCES);
    *uid = (uid_t)current.st.stx_uid; *gid = (gid_t)current.st.stx_gid;
    return 0;
}

static int fw_begin_write_request(struct fw_context *ctx, struct fw_input *in, struct fw_output *out)
{
    char path[FW_PATH + 1]; uint32_t overwrite;
    if (fw_take_path(in, path) < 0 || fw_bool(in, &overwrite) < 0 || fw_end(in) < 0) return -1;
    struct fw_resolved r;
    if (fw_resolve(ctx, path, true, 1, &r) < 0) return -1;
    int error = 0;
    if (fw_write_target(ctx, &r) < 0) error = errno;
    if (!error && !r.missing && !S_ISREG(r.identity.st.stx_mode)) error = EOPNOTSUPP;
    if (!error && !r.missing && !overwrite) error = EEXIST;
    struct fw_handle *h = error ? NULL : fw_new_handle(ctx);
    if (!error && !h) error = errno;
    if (!error) {
        h->writing = true; h->overwrite = overwrite != 0;
        strcpy(h->path, r.path); strcpy(h->name, r.name);
        if (fw_identify(r.parent, &h->parent_identity) < 0) error = errno;
        if (!error) {
            h->parent = fw_directory_read(r.parent, &h->parent_identity);
            if (h->parent < 0) error = errno;
        }
        if (!r.missing) {
            h->original = r.fd; r.fd = -1; h->original_identity = r.identity;
            h->commit_uid = (uid_t)r.identity.st.stx_uid; h->commit_gid = (gid_t)r.identity.st.stx_gid;
            h->commit_mode = (mode_t)(r.identity.st.stx_mode & 07777U);
        } else {
            h->commit_mode = 0600;
            if (fw_creation_owner(ctx, r.identity.st.stx_mnt_id, &h->commit_uid, &h->commit_gid) < 0) error = errno;
        }
        for (size_t i = 0; !error && h->fd < 0 && i < 8; ++i) {
            char name[64];
            if (fw_random_name(name) < 0) { error = errno; break; }
            int fd = fw_open(h->parent, name, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK,
                0600, FW_RESOLVE);
            if (fd < 0) { if (errno == EEXIST) continue; error = errno; break; }
            h->fd = fd; strcpy(h->temporary, name);
            if (fw_identify(fd, &h->identity) < 0) { error = errno; break; }
            if (!S_ISREG(h->identity.st.stx_mode) || h->identity.st.stx_uid != (uint32_t)geteuid() ||
                h->identity.st.stx_nlink != 1 || (h->identity.st.stx_mode & 0777U) != 0600 ||
                h->identity.st.stx_mnt_id != h->parent_identity.st.stx_mnt_id) error = ESTALE;
        }
        if (!error && h->fd < 0) error = EEXIST;
    }
    fw_resolved_close(&r);
    if (error) {
        if (h && fw_drop_handle(ctx, h, true) < 0) fputs("AJFW_CLEANUP_UNKNOWN\n", stderr);
        return fw_fail(error);
    }
    return fw_out32(out, h->number);
}
static int fw_write_handle_current(struct fw_context *ctx, struct fw_handle *h)
{
    if (!h->writing || h->committed || h->poisoned) return fw_fail(h->poisoned ? EIO : EBADF);
    char parent_path[FW_PATH + 1]; strcpy(parent_path, h->path);
    char *slash = strrchr(parent_path, '/'); if (slash == parent_path) slash[1] = 0; else *slash = 0;
    if (fw_current(ctx, h->parent, &h->parent_identity, parent_path, true) < 0 ||
        fw_named_same(h->parent, h->temporary, &h->identity) < 0) return -1;
    struct fw_identity current;
    if (fw_identify(h->fd, &current) < 0) return -1;
    if (!fw_same(&current, &h->identity) || current.st.stx_size != h->total || current.st.stx_nlink != 1)
        return fw_fail(ESTALE);
    uid_t uid = h->metadata_applied ? h->commit_uid : (uid_t)h->identity.st.stx_uid;
    gid_t gid = h->metadata_applied ? h->commit_gid : (gid_t)h->identity.st.stx_gid;
    mode_t mode = h->metadata_applied ? h->commit_mode : 0600;
    if (current.st.stx_uid != (uint32_t)uid || current.st.stx_gid != (uint32_t)gid ||
        (current.st.stx_mode & 07777U) != mode) return fw_fail(ESTALE);
    return fw_authorize(ctx, h->fd, &current, h->path, true);
}
static int fw_write_chunk_request(struct fw_context *ctx, struct fw_input *in)
{
    uint32_t number, length;
    if (fw_take32(in, &number) < 0 || fw_take32(in, &length) < 0) return -1;
    if (length > FW_CHUNK || length != in->size - in->offset) return fw_fail(EINVAL);
    struct fw_handle *h = fw_find_handle(ctx, number);
    if (!h) return -1;
    if (h->metadata_applied) return fw_fail(EBADF);
    if (fw_write_handle_current(ctx, h) < 0) return -1;
    if (h->total > FW_WRITE_LIMIT - length) return fw_fail(EFBIG);
    size_t used = 0;
    while (used < length) {
        if (fw_check(ctx) < 0) { h->poisoned = true; return -1; }
        ssize_t n = pwrite(h->fd, in->bytes + in->offset + used, length - used, (off_t)(h->total + used));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { h->poisoned = true; return n < 0 ? -1 : fw_fail(EIO); }
        used += (size_t)n;
    }
    h->total += length;
    if (fw_write_handle_current(ctx, h) < 0) { h->poisoned = true; return -1; }
    return 0;
}
static int fw_destination_current(const struct fw_handle *h)
{
    if (h->original >= 0) {
        struct fw_identity current;
        if (fw_identify(h->original, &current) < 0) return -1;
        if (!fw_same(&current, &h->original_identity) ||
            current.st.stx_mode != h->original_identity.st.stx_mode ||
            current.st.stx_uid != h->original_identity.st.stx_uid ||
            current.st.stx_gid != h->original_identity.st.stx_gid) return fw_fail(ESTALE);
        return fw_named_same(h->parent, h->name, &h->original_identity);
    }
    int fd = fw_open(h->parent, h->name, FW_PF, 0, FW_RESOLVE);
    if (fd >= 0) { close(fd); return fw_fail(h->overwrite ? ESTALE : EEXIST); }
    return errno == ENOENT ? 0 : -1;
}
static int fw_commit_write_request(struct fw_context *ctx, struct fw_input *in, struct fw_output *out)
{
    uint32_t number;
    if (fw_take32(in, &number) < 0 || fw_end(in) < 0) return -1;
    struct fw_handle *h = fw_find_handle(ctx, number);
    if (!h || fw_write_handle_current(ctx, h) < 0 || fw_destination_current(h) < 0) return -1;
    if (!h->metadata_applied) {
        /* chown may clear setid bits: restore ownership first, then the complete permission mode.
         * All changes affect only the owned, pinned 0600 temporary inode, never the old target. */
        if ((h->identity.st.stx_uid != (uint32_t)h->commit_uid || h->identity.st.stx_gid != (uint32_t)h->commit_gid) &&
            fchown(h->fd, h->commit_uid, h->commit_gid) < 0) { h->poisoned = true; return -1; }
        if (fchmod(h->fd, h->commit_mode) < 0) { h->poisoned = true; return -1; }
        h->metadata_applied = true;
    }
    if (fsync(h->fd) < 0 || fw_check(ctx) < 0 || fw_write_handle_current(ctx, h) < 0 ||
        fw_destination_current(h) < 0) return -1;
    unsigned int flags = h->overwrite && h->original >= 0 ? 0U : RENAME_NOREPLACE;
    if (syscall(SYS_renameat2, h->parent, h->temporary, h->parent, h->name, flags) < 0) return -1;
    h->committed = true;
    uint64_t size = h->total;
    int error = 0;
    if (fw_named_same(h->parent, h->name, &h->identity) < 0 || fsync(h->parent) < 0) error = errno;
    if (fw_drop_handle(ctx, h, false) < 0 && !error) error = errno;
    if (error) return fw_fail(error);
    return fw_out64(out, size);
}

static int fw_publish_directory(struct fw_context *ctx, const struct fw_resolved *target)
{
    struct fw_identity parent_identity, created_identity = {0};
    uid_t uid; gid_t gid;
    if (fw_write_target(ctx, target) < 0 || fw_identify(target->parent, &parent_identity) < 0 ||
        fw_creation_owner(ctx, parent_identity.st.stx_mnt_id, &uid, &gid) < 0) return -1;
    int parent = fw_directory_read(target->parent, &parent_identity);
    if (parent < 0) return -1;
    char parent_path[FW_PATH + 1]; strcpy(parent_path, target->path);
    char *slash = strrchr(parent_path, '/'); if (slash == parent_path) slash[1] = 0; else *slash = 0;
    char temporary[64] = {0};
    int directory = -1, error = 0, result = 0;
    bool created = false, owned = false, published = false;
    for (size_t attempt = 0; attempt < 8; ++attempt) {
        if (fw_random_name(temporary) < 0) { error = errno; break; }
        if (mkdirat(parent, temporary, 0700) == 0) { created = true; break; }
        if (errno != EEXIST) { error = errno; break; }
    }
    if (!error && !created) error = EEXIST;
    if (!error) {
        directory = fw_open(parent, temporary, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK, 0, FW_RESOLVE);
        if (directory < 0 || fw_identify(directory, &created_identity) < 0) error = errno;
        else if (!S_ISDIR(created_identity.st.stx_mode) || created_identity.st.stx_uid != (uint32_t)geteuid() ||
            (created_identity.st.stx_mode & 0777U) != 0700 || created_identity.st.stx_nlink != 2 ||
            created_identity.st.stx_mnt_id != parent_identity.st.stx_mnt_id) error = ESTALE;
        else owned = true;
    }
    if (!error && (fw_current(ctx, parent, &parent_identity, parent_path, true) < 0 ||
        fw_named_same(parent, temporary, &created_identity) < 0 ||
        fw_authorize(ctx, directory, &created_identity, target->path, true) < 0)) error = errno;
    /* Never follow/re-resolve the published name for metadata changes. Only this private, pinned
     * 0700 inode receives ownership; competing existing directories/symlinks are read-only inputs. */
    if (!error && (created_identity.st.stx_uid != (uint32_t)uid || created_identity.st.stx_gid != (uint32_t)gid) &&
        fchown(directory, uid, gid) < 0) error = errno;
    if (!error && fchmod(directory, 0700) < 0) error = errno;
    struct fw_identity current;
    if (!error && (fw_identify(directory, &current) < 0 || !fw_same(&current, &created_identity) ||
        current.st.stx_uid != (uint32_t)uid || current.st.stx_gid != (uint32_t)gid ||
        (current.st.stx_mode & 07777U) != 0700 || current.st.stx_nlink != 2)) error = ESTALE;
    if (!error && (fsync(directory) < 0 || fw_check(ctx) < 0 ||
        fw_current(ctx, parent, &parent_identity, parent_path, true) < 0 ||
        fw_named_same(parent, temporary, &created_identity) < 0 || fw_protected(ctx, target->path) < 0)) error = errno;
    if (!error) {
        if (syscall(SYS_renameat2, parent, temporary, parent, target->name, RENAME_NOREPLACE) < 0) {
            if (errno == EEXIST) result = 1; else error = errno;
        } else published = true;
    }
    if (published && (fw_current(ctx, directory, &created_identity, target->path, true) < 0 || fsync(parent) < 0)) {
        error = errno; ctx->cleanup_failed = true;
    }
    if (created && !published) {
        if (!owned || fw_fd_same(directory, &created_identity) < 0 ||
            fw_named_same(parent, temporary, &created_identity) < 0 || unlinkat(parent, temporary, AT_REMOVEDIR) < 0) {
            ctx->cleanup_failed = true; if (!error) error = EUCLEAN;
        }
    }
    if (directory >= 0) close(directory);
    close(parent);
    return error ? fw_fail(error) : result;
}

static int fw_mkdirs_request(struct fw_context *ctx, struct fw_input *in)
{
    char path[FW_PATH + 1], prefix[FW_PATH + 1];
    if (fw_take_path(in, path) < 0 || fw_end(in) < 0) return -1;
    if (!strcmp(path, "/")) return fw_fail(EBUSY);
    size_t length = strlen(path);
    for (size_t i = 1; i <= length; ++i) {
        if (path[i] && path[i] != '/') continue;
        memcpy(prefix, path, i); prefix[i] = 0;
        struct fw_resolved r;
        if (fw_resolve(ctx, prefix, true, 1, &r) < 0) return -1;
        int error = 0;
        if (r.missing) {
            if (fw_publish_directory(ctx, &r) < 0) error = errno;
            if (!error) {
                fw_resolved_close(&r);
                if (fw_resolve(ctx, prefix, true, 0, &r) < 0) error = errno;
            }
        }
        if (!error && !S_ISDIR(r.identity.st.stx_mode)) error = ENOTDIR;
        if (!error && i == length && (fw_protected(ctx, r.path) < 0 ||
            fw_current(ctx, r.fd, &r.identity, r.path, true) < 0)) error = errno;
        fw_resolved_close(&r);
        if (error) return fw_fail(error);
    }
    return 0;
}

struct fw_delete_node {
    int fd;
    size_t parent;
    struct fw_identity identity;
    char name[FW_NAME + 1];
};
struct fw_delete_tree {
    struct fw_delete_node *nodes;
    size_t used, capacity;
    uint64_t mount;
};
static int fw_delete_add(struct fw_delete_tree *tree, int fd, size_t parent, const char *name,
                          const struct fw_identity *identity, size_t *index)
{
    if (tree->used == FW_ENTRIES) return fw_fail(E2BIG);
    if (tree->used == tree->capacity) {
        size_t capacity = tree->capacity ? tree->capacity * 2 : 32;
        if (capacity > FW_ENTRIES) capacity = FW_ENTRIES;
        void *p = realloc(tree->nodes, capacity * sizeof(*tree->nodes));
        if (!p) return -1;
        tree->nodes = p; tree->capacity = capacity;
    }
    *index = tree->used++;
    struct fw_delete_node *node = &tree->nodes[*index];
    node->fd = fd; node->parent = parent; node->identity = *identity; strcpy(node->name, name); return 0;
}
static int fw_delete_snapshot(struct fw_context *ctx, struct fw_delete_tree *tree, size_t index,
                              const char *path, size_t depth)
{
    if (depth > 128) return fw_fail(ELOOP);
    int anchor = tree->nodes[index].fd;
    struct fw_identity identity = tree->nodes[index].identity;
    if (!S_ISDIR(identity.st.stx_mode)) return 0;
    int fd = fw_directory_read(anchor, &identity);
    if (fd < 0) return -1;
    DIR *dir = fdopendir(fd);
    if (!dir) { int saved = errno; close(fd); return fw_fail(saved); }
    int error = 0;
    for (;;) {
        if (fw_check(ctx) < 0) { error = errno; break; }
        errno = 0; struct dirent *entry = readdir(dir);
        if (!entry) { error = errno; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        char child_path[FW_PATH + 1];
        if (fw_entry_name(entry->d_name) < 0 || fw_join(child_path, path, entry->d_name) < 0 ||
            fw_protected(ctx, child_path) < 0) { error = errno; break; }
        int child = fw_open(anchor, entry->d_name, FW_PF, 0, FW_RESOLVE);
        if (child < 0) { error = errno; break; }
        struct fw_identity id;
        if (fw_identify(child, &id) < 0 || fw_authorize(ctx, child, &id, child_path, true) < 0) {
            error = errno; close(child); break;
        }
        if (id.st.stx_mnt_id != tree->mount) { error = EXDEV; close(child); break; }
        size_t child_index;
        if (fw_delete_add(tree, child, index, entry->d_name, &id, &child_index) < 0) {
            error = errno; close(child); break;
        }
        if (fw_delete_snapshot(ctx, tree, child_index, child_path, depth + 1) < 0) { error = errno; break; }
    }
    if (closedir(dir) < 0 && !error) error = errno;
    return error ? fw_fail(error) : 0;
}
static int fw_delete_guard(struct fw_context *ctx, const struct fw_delete_tree *tree, size_t index,
                            const struct fw_resolved *root)
{
    size_t chain[130], count = 0, current = index;
    while (tree->nodes[current].parent != SIZE_MAX) {
        if (count == sizeof(chain) / sizeof(*chain)) return fw_fail(ELOOP);
        chain[count++] = current; current = tree->nodes[current].parent;
    }
    char path[FW_PATH + 1]; strcpy(path, root->path);
    while (count) {
        char child[FW_PATH + 1];
        if (fw_join(child, path, tree->nodes[chain[--count]].name) < 0) return -1;
        strcpy(path, child);
    }
    const struct fw_delete_node *node = &tree->nodes[index];
    if (fw_write_target(ctx, root) < 0 || fw_protected(ctx, path) < 0 ||
        fw_current(ctx, node->fd, &node->identity, path, true) < 0) return -1;
    int parent = node->parent == SIZE_MAX ? root->parent : tree->nodes[node->parent].fd;
    return fw_named_same(parent, node->name, &node->identity);
}
static int fw_delete_request(struct fw_context *ctx, struct fw_input *in)
{
    char path[FW_PATH + 1]; uint32_t recursive;
    if (fw_take_path(in, path) < 0 || fw_bool(in, &recursive) < 0 || fw_end(in) < 0) return -1;
    struct fw_resolved r;
    if (fw_resolve(ctx, path, false, 0, &r) < 0) return -1;
    if (fw_write_target(ctx, &r) < 0) { fw_resolved_close(&r); return -1; }
    struct fw_delete_tree tree = { .mount = r.identity.st.stx_mnt_id };
    size_t root_index;
    int error = 0;
    int root_fd = fw_dup(r.fd);
    if (root_fd < 0) error = errno;
    else if (fw_delete_add(&tree, root_fd, SIZE_MAX, r.name, &r.identity, &root_index) < 0) {
        error = errno; close(root_fd);
    }
    if (!error && recursive && fw_delete_snapshot(ctx, &tree, 0, r.path, 0) < 0) error = errno;
    if (!error && fw_write_target(ctx, &r) < 0) error = errno;
    for (size_t i = 0; !error && i < tree.used; ++i)
        if (fw_delete_guard(ctx, &tree, i, &r) < 0) error = errno;
    for (size_t i = tree.used; !error && i > 0;) {
        struct fw_delete_node *node = &tree.nodes[--i];
        int parent = node->parent == SIZE_MAX ? r.parent : tree.nodes[node->parent].fd;
        if (fw_delete_guard(ctx, &tree, i, &r) < 0 ||
            unlinkat(parent, node->name, S_ISDIR(node->identity.st.stx_mode) ? AT_REMOVEDIR : 0) < 0) error = errno;
    }
    for (size_t i = 0; i < tree.used; ++i) close(tree.nodes[i].fd);
    free(tree.nodes); fw_resolved_close(&r);
    return error ? fw_fail(error) : 0;
}

static int fw_rename_request(struct fw_context *ctx, struct fw_input *in)
{
    char source[FW_PATH + 1], target[FW_PATH + 1]; uint32_t overwrite;
    if (fw_take_path(in, source) < 0 || fw_take_path(in, target) < 0 || fw_bool(in, &overwrite) < 0 || fw_end(in) < 0)
        return -1;
    struct fw_resolved src, dst;
    if (fw_resolve(ctx, source, false, 0, &src) < 0) return -1;
    if (fw_resolve(ctx, target, false, 1, &dst) < 0) { fw_resolved_close(&src); return -1; }
    int error = 0;
    if (fw_write_target(ctx, &src) < 0 || fw_write_target(ctx, &dst) < 0) error = errno;
    if (!error && (fw_below(src.path, dst.path) || fw_below(dst.path, src.path) ||
        (!dst.missing && fw_same(&src.identity, &dst.identity)))) error = EINVAL;
    if (!error && src.identity.st.stx_mnt_id != dst.identity.st.stx_mnt_id) error = EXDEV;
    if (!error && !overwrite && !dst.missing) error = EEXIST;
    if (!error && (fw_named_same(src.parent, src.name, &src.identity) < 0 ||
        (!dst.missing && fw_named_same(dst.parent, dst.name, &dst.identity) < 0))) error = errno;
    if (!error) {
        unsigned int flags = overwrite && !dst.missing ? 0U : RENAME_NOREPLACE;
        if (syscall(SYS_renameat2, src.parent, src.name, dst.parent, dst.name, flags) < 0) error = errno;
    }
    fw_resolved_close(&src); fw_resolved_close(&dst);
    return error ? fw_fail(error) : 0;
}

static int fw_dispatch(struct fw_context *ctx, uint16_t op, const unsigned char *bytes, size_t length, struct fw_output *out)
{
    struct fw_input in = { .bytes = bytes, .size = length };
    switch (op) {
    case FW_STAT: return fw_stat_request(ctx, &in, out);
    case FW_LIST: return fw_list_request(ctx, &in, out);
    case FW_RESOLVE_PATH: return fw_resolve_request(ctx, &in, out);
    case FW_OPEN_READ: return fw_open_read_request(ctx, &in, out);
    case FW_READ_CHUNK: return fw_read_chunk_request(ctx, &in, out);
    case FW_CLOSE: return fw_close_request(ctx, &in, false);
    case FW_BEGIN_WRITE: return fw_begin_write_request(ctx, &in, out);
    case FW_WRITE_CHUNK: return fw_write_chunk_request(ctx, &in);
    case FW_COMMIT_WRITE: return fw_commit_write_request(ctx, &in, out);
    case FW_ABORT: return fw_close_request(ctx, &in, true);
    case FW_MKDIRS: return fw_mkdirs_request(ctx, &in);
    case FW_DELETE: return fw_delete_request(ctx, &in);
    case FW_RENAME: return fw_rename_request(ctx, &in);
    default: return fw_fail(EPROTO);
    }
}

static bool fw_credentials_valid(uid_t ruid, uid_t euid, uid_t suid, gid_t rgid, gid_t egid, gid_t sgid)
{
    return (ruid == 0 || (ruid >= 10000 && ruid <= INT_MAX)) && ruid == euid && ruid == suid &&
        (uint64_t)rgid == (uint64_t)ruid && rgid == egid && rgid == sgid;
}

static bool fw_capabilities_valid(bool root, const uint64_t caps[5])
{
    if (root) return ajiu_guest_initial_caps(caps);
    for (size_t i = 0; i < 5; ++i) if (caps[i]) return false;
    return true;
}
static int fw_guard(void)
{
    uid_t ruid, euid, suid; gid_t rgid, egid, sgid;
    if (getresuid(&ruid, &euid, &suid) < 0 || getresgid(&rgid, &egid, &sgid) < 0 ||
        !fw_credentials_valid(ruid, euid, suid, rgid, egid, sgid) || prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) != 1)
        return fw_fail(EPERM);
    if ((uid_t)syscall(SYS_setfsuid, (uid_t)-1) != euid || (gid_t)syscall(SYS_setfsgid, (gid_t)-1) != egid)
        return fw_fail(EPERM);
    struct __user_cap_header_struct header = { .version = _LINUX_CAPABILITY_VERSION_3, .pid = 0 };
    struct __user_cap_data_struct data[2]; memset(data, 0, sizeof(data));
    if (syscall(SYS_capget, &header, data) < 0) return -1;
    uint64_t caps[5] = {
        ((uint64_t)data[1].effective << 32) | data[0].effective,
        ((uint64_t)data[1].permitted << 32) | data[0].permitted,
        ((uint64_t)data[1].inheritable << 32) | data[0].inheritable, 0, 0
    };
    if (!euid) {
        gid_t groups[2];
        int count = getgroups(2, groups);
        if (prctl(PR_GET_SECUREBITS, 0, 0, 0, 0) != AJIU_GUEST_SECUREBITS ||
            count < 0 || count > 1 || (count == 1 && groups[0] != 3003)) return fw_fail(EPERM);
    }
    for (unsigned int cap = 0; cap <= 64; ++cap) {
        int bounding = prctl(PR_CAPBSET_READ, cap, 0, 0, 0);
        if (bounding < 0 && errno == EINVAL && cap > CAP_SETFCAP)
            return fw_capabilities_valid(euid == 0, caps) ? 0 : fw_fail(EPERM);
        if (cap == 64 || bounding < 0 || prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_IS_SET, cap, 0, 0) != 0)
            return fw_fail(EPERM);
        if (bounding) caps[3] |= UINT64_C(1) << cap;
    }
    return fw_fail(EOPNOTSUPP);
}
static void fw_signal(int signal_number) { (void)signal_number; fw_stopping = 1; }
static int fw_signals(void)
{
    struct sigaction action; memset(&action, 0, sizeof(action));
    action.sa_handler = SIG_IGN;
    if (sigemptyset(&action.sa_mask) < 0 || sigaction(SIGPIPE, &action, NULL) < 0) return -1;
    action.sa_handler = fw_signal;
    if (sigaction(SIGTERM, &action, NULL) < 0 || sigaction(SIGINT, &action, NULL) < 0 ||
        sigaction(SIGHUP, &action, NULL) < 0 || sigprocmask(SIG_SETMASK, &action.sa_mask, NULL) < 0) return -1;
    return 0;
}
static int fw_nonblock(int fd)
{
    int flags = fcntl(fd, F_GETFL);
    return flags < 0 ? -1 : fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}
static int fw_serve(struct fw_context *ctx)
{
    unsigned char *input = malloc(FW_FRAME), *response = malloc(FW_FRAME);
    if (!input || !response) { free(input); free(response); return -1; }
    int result = 0;
    for (;;) {
        unsigned char header_bytes[FW_HEADER]; struct fw_header header;
        int64_t now = fw_now();
        if (now < 0) { result = -1; break; }
        int64_t deadline = now + FW_WAIT_MS;
        int rc = fw_transfer(STDIN_FILENO, header_bytes, FW_HEADER, false, true, deadline);
        if (rc == 1) break;
        if (rc < 0 || fw_decode_header(header_bytes, &header) < 0 ||
            fw_transfer(STDIN_FILENO, input, header.length, false, false, deadline) < 0) { result = -1; break; }
        now = fw_now();
        if (now < 0) { result = -1; break; }
        ctx->deadline = now + FW_WAIT_MS;
        struct fw_output out = { .bytes = response };
        fw_put32(response, 0); out.size = 4;
        if (fw_dispatch(ctx, header.op, input, header.length, &out) < 0) {
            int error = errno ? errno : EIO;
            fw_put32(response, (uint32_t)error); out.size = 4;
        }
        fw_encode_header(header_bytes, &header, (uint32_t)out.size);
        if (fw_transfer(STDOUT_FILENO, header_bytes, FW_HEADER, true, false, ctx->deadline) < 0 ||
            fw_transfer(STDOUT_FILENO, response, out.size, true, false, ctx->deadline) < 0) { result = -1; break; }
    }
    int saved = errno; free(input); free(response); errno = saved; return result;
}
static int fw_cleanup(struct fw_context *ctx)
{
    int error = 0;
    for (size_t i = 0; i < FW_HANDLES; ++i)
        if (ctx->handles[i].number && fw_drop_handle(ctx, &ctx->handles[i], true) < 0) error = errno;
    for (size_t i = 0; i < ctx->mount_count; ++i) close(ctx->mounts[i].fd);
    for (size_t i = 0; i < ctx->boundary_count; ++i) free(ctx->boundaries[i].path);
    free(ctx->boundaries);
    if (ctx->proc_fds >= 0) close(ctx->proc_fds);
    if (ctx->proc_self >= 0) close(ctx->proc_self);
    if (ctx->proc >= 0) close(ctx->proc);
    if (ctx->root >= 0) close(ctx->root);
    if (ctx->cleanup_failed) return fw_fail(EUCLEAN);
    return error ? fw_fail(error) : 0;
}

int main(int argc, char **argv)
{
    if (argc == 2 && !strcmp(argv[1], "--help")) {
        fputs("ajiu-file-worker: no arguments; AJFW v1 binary stdio; managed root guest (not a security sandbox).\n", stdout);
        return 0;
    }
    if (argc != 1) { fputs("AJFW_ARGUMENT\n", stderr); return 2; }
    if (fw_guard() < 0) { fputs("AJFW_GUARD\n", stderr); return 3; }
    if (fw_signals() < 0 || syscall(SYS_close_range, 3U, UINT_MAX, 0U) < 0 ||
        fw_nonblock(STDIN_FILENO) < 0 || fw_nonblock(STDOUT_FILENO) < 0) {
        fputs("AJFW_STARTUP\n", stderr); return 3;
    }
    (void)umask(0077);
    struct fw_context *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) { fputs("AJFW_STARTUP\n", stderr); return 3; }
    ctx->root = ctx->proc = ctx->proc_self = ctx->proc_fds = -1; ctx->next_handle = 1;
    for (size_t i = 0; i < FW_HANDLES; ++i) fw_handle_init(&ctx->handles[i]);
    ctx->root = open("/", FW_DF);
    if (ctx->root < 0 || fw_pin_proc(ctx) < 0 || fw_load_mounts(ctx) < 0) {
        fputs("AJFW_STARTUP\n", stderr); (void)fw_cleanup(ctx); free(ctx); return 3;
    }
    int result = fw_serve(ctx) < 0 ? 4 : 0;
    if (result) fputs("AJFW_TRANSPORT\n", stderr);
    if (fw_cleanup(ctx) < 0) {
        fputs(ctx->cleanup_failed ? "AJFW_CLEANUP_UNKNOWN\n" : "AJFW_CLEANUP\n", stderr); result = 5;
    }
    free(ctx); return result;
}
