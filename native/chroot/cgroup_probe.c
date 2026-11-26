#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <linux/capability.h>
#include <linux/magic.h>
#include <linux/securebits.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <grp.h>

#define CGROOT "/sys/fs/cgroup"
#define PREFIX "ajiu_probe_"
#define MAX_GROUPS 1
#define MAX_TASKS 3
#define TEXT_MAX 4096
#define NS_COUNT 4
#define MS(x) ((int64_t)(x) * 1000000)
#define LOAD_LIMIT_MS 9000
#define ACTION_LIMIT_MS 7000
#define WATCH_LIMIT_MS 11500
#define TOTAL_LIMIT_MS 14000

static const char *const ns_names[NS_COUNT] = { "pid", "mnt", "user", "cgroup" };
static int output_fd = STDOUT_FILENO;

enum mode { MODE_NONE, MODE_HELP, MODE_INSPECT, MODE_BASELINE, MODE_EOF, MODE_CRASH };
enum control_index { CT_PROCS, CT_KILL, CT_EVENTS, CT_FREEZE, CT_COUNT };
static const char *const control_names[CT_COUNT] = {
    "cgroup.procs", "cgroup.kill", "cgroup.events", "cgroup.freeze"
};
struct config {
    enum mode mode;
    uid_t uid;
    gid_t gid;
    gid_t groups[MAX_GROUPS];
    size_t ngroups;
    char name[sizeof(PREFIX) + 24];
    char path[sizeof(CGROOT) + sizeof(PREFIX) + 25];
    int cap_last;
    int app_group;
    int64_t start;
};
struct anchor {
    int fd;
    struct stat st;
};
struct probe_group {
    struct anchor root;
    struct anchor dir;
    struct anchor ctl[CT_COUNT];
    bool created;
    char origin[TEXT_MAX];
    struct stat namespaces[NS_COUNT];
};
struct caps {
    uint64_t effective;
    uint64_t permitted;
    uint64_t inheritable;
    uint64_t bounding;
    uint64_t ambient;
    int nnp;
};
struct known {
    pid_t pid;
    uint64_t start;
    unsigned int accepted;
};
struct shared {
    struct known task[MAX_TASKS];
    unsigned int unsafe;
    unsigned int armed;
    unsigned int watchdog_ready;
    unsigned int watchdog_done;
    unsigned int killed;
    unsigned int empty;
    unsigned int trigger;
    int watchdog_errno;
};
struct message {
    uint32_t type;
    int role;
    pid_t pid;
    pid_t ppid;
    pid_t sid;
    uid_t uid[3];
    gid_t gid[3];
    gid_t groups[MAX_GROUPS];
    int ngroups;
    struct caps caps;
    int denied[5];
    int membership;
    int error;
};
enum { MSG_BORN = 1, MSG_HELLO, MSG_ERROR };
struct identity {
    pid_t ppid;
    uint64_t start;
    uid_t uid[4];
    gid_t gid[4];
    gid_t groups[MAX_GROUPS];
    int ngroups;
    struct caps caps;
};

static unsigned int load_u(const unsigned int *p)
{
    return __atomic_load_n(p, __ATOMIC_ACQUIRE);
}

static void store_u(unsigned int *p, unsigned int value)
{
    __atomic_store_n(p, value, __ATOMIC_RELEASE);
}

static int64_t now_ns(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) < 0)
        return -1;
    return (int64_t)ts.tv_sec * 1000000000 + ts.tv_nsec;
}

static int remaining_ms(int64_t deadline)
{
    int64_t n = now_ns();
    if (n < 0 || n >= deadline)
        return 0;
    int64_t left = (deadline - n + 999999) / 1000000;
    return left > INT_MAX ? INT_MAX : (int)left;
}

static void delay_ms(int ms)
{
    (void)poll(NULL, 0, ms);
}

static void emit(const char *fmt, ...)
{
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n < 0 || (size_t)n >= sizeof(buf))
        return;
    size_t off = 0;
    int64_t deadline = now_ns() + MS(30);
    while (off < (size_t)n && remaining_ms(deadline) > 0) {
        ssize_t rc = write(output_fd, buf + off, (size_t)n - off);
        if (rc > 0) {
            off += (size_t)rc;
        } else if (rc < 0 && errno == EINTR) {
            continue;
        } else if (rc < 0 && errno == EAGAIN) {
            struct pollfd p = { .fd = output_fd, .events = POLLOUT };
            (void)poll(&p, 1, remaining_ms(deadline));
        } else {
            break;
        }
    }
}

static const char *mode_name(enum mode m)
{
    switch (m) {
    case MODE_HELP: return "help";
    case MODE_INSPECT: return "inspect";
    case MODE_BASELINE: return "baseline";
    case MODE_EOF: return "eof";
    case MODE_CRASH: return "supervisor-crash";
    default: return "invalid";
    }
}

static void final_event(const struct config *c, int rc, const char *error,
                        const char *stage, const char *cleanup, bool created)
{
    char path[128];
    if (created)
        (void)snprintf(path, sizeof(path), "\"%s\"", c->path);
    else
        (void)snprintf(path, sizeof(path), "null");
    emit("{\"passed\":%s,\"error_code\":\"%s\",\"stage\":\"%s\","
         "\"created_group\":%s,\"cleanup_state\":\"%s\",\"mode\":\"%s\"}\n",
         rc == 0 ? "true" : "false", error, stage, path, cleanup, mode_name(c->mode));
}

static int decimal(const char *s, uint32_t *value)
{
    if (*s == '\0' || (s[0] == '0' && s[1] != '\0'))
        return -1;
    uint64_t n = 0;
    for (size_t i = 0; s[i] != '\0'; ++i) {
        if (i >= 10 || s[i] < '0' || s[i] > '9')
            return -1;
        n = n * 10 + (unsigned int)(s[i] - '0');
        if (n > INT_MAX)
            return -1;
    }
    *value = (uint32_t)n;
    return 0;
}

static int parse_args(int argc, char **argv, struct config *c)
{
    memset(c, 0, sizeof(*c));
    if (argc == 2 && strcmp(argv[1], "--help") == 0) {
        c->mode = MODE_HELP;
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--inspect") == 0) {
        c->mode = MODE_INSPECT;
        return 0;
    }
    if (argc != 7 || strcmp(argv[1], "--exercise") != 0)
        return -1;
    if (strnlen(argv[2], 25) != 24)
        return -1;
    for (size_t i = 0; i < 24; ++i) {
        if (!((argv[2][i] >= '0' && argv[2][i] <= '9') ||
              (argv[2][i] >= 'a' && argv[2][i] <= 'f')))
            return -1;
    }
    uint32_t uid, gid;
    if (decimal(argv[3], &uid) < 0 || decimal(argv[4], &gid) < 0 ||
        uid < 10000 || gid != uid)
        return -1;
    c->uid = (uid_t)uid;
    c->gid = (gid_t)gid;
    if (strcmp(argv[5], "-") == 0) {
        c->ngroups = 0;
    } else if (strcmp(argv[5], "3003") == 0) {
        c->groups[0] = 3003;
        c->ngroups = 1;
    } else {
        return -1;
    }
    if (strcmp(argv[6], "baseline") == 0)
        c->mode = MODE_BASELINE;
    else if (strcmp(argv[6], "eof") == 0)
        c->mode = MODE_EOF;
    else if (strcmp(argv[6], "supervisor-crash") == 0)
        c->mode = MODE_CRASH;
    else
        return -1;
    int n = snprintf(c->name, sizeof(c->name), PREFIX "%s", argv[2]);
    if (n < 0 || (size_t)n >= sizeof(c->name))
        return -1;
    n = snprintf(c->path, sizeof(c->path), CGROOT "/%s", c->name);
    return n > 0 && (size_t)n < sizeof(c->path) ? 0 : -1;
}

static int close_except(const int *keep, size_t n)
{
    unsigned int first = 0;
    for (;;) {
        unsigned int next = UINT_MAX;
        bool found = false;
        for (size_t i = 0; i < n; ++i) {
            if (keep[i] >= 0 && (unsigned int)keep[i] >= first &&
                (!found || (unsigned int)keep[i] < next)) {
                next = (unsigned int)keep[i];
                found = true;
            }
        }
        if (!found)
            return (int)syscall(SYS_close_range, first, UINT_MAX, 0);
        if (next > first && syscall(SYS_close_range, first, next - 1, 0) < 0)
            return -1;
        if (next == UINT_MAX)
            return 0;
        first = next + 1;
    }
}

static int arm_death(int64_t deadline)
{
    sigset_t empty;
    if (sigemptyset(&empty) < 0 || sigprocmask(SIG_SETMASK, &empty, NULL) < 0)
        return -1;
    for (int sig = 1; sig < NSIG; ++sig) {
        if (sig == SIGKILL || sig == SIGSTOP)
            continue;
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = sig == SIGPIPE ? SIG_IGN : SIG_DFL;
        (void)sigemptyset(&sa.sa_mask);
        if (sigaction(sig, &sa, NULL) < 0 && errno != EINVAL)
            return -1;
    }
    int64_t left = deadline - now_ns();
    if (left <= 0) {
        errno = ETIMEDOUT;
        return -1;
    }
    struct itimerval it;
    memset(&it, 0, sizeof(it));
    it.it_value.tv_sec = (time_t)(left / 1000000000);
    it.it_value.tv_usec = (suseconds_t)((left % 1000000000) / 1000 + 1);
    if (it.it_value.tv_usec >= 1000000) {
        ++it.it_value.tv_sec;
        it.it_value.tv_usec = 0;
    }
    return setitimer(ITIMER_REAL, &it, NULL);
}

static int read_text_fd(int fd, char *buf, size_t size)
{
    if (size < 2 || lseek(fd, 0, SEEK_SET) < 0)
        return -1;
    size_t off = 0;
    for (;;) {
        if (off == size - 1) {
            errno = EOVERFLOW;
            return -1;
        }
        ssize_t n = read(fd, buf + off, size - 1 - off);
        if (n > 0)
            off += (size_t)n;
        else if (n == 0)
            break;
        else if (errno != EINTR)
            return -1;
    }
    buf[off] = '\0';
    return (int)off;
}

static int read_path(const char *path, char *buf, size_t size)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0)
        return -1;
    int rc = read_text_fd(fd, buf, size);
    int saved = errno;
    (void)close(fd);
    errno = saved;
    return rc;
}

static int cap_snapshot(struct caps *c, int last)
{
    memset(c, 0, sizeof(*c));
    struct __user_cap_header_struct h = { .version = _LINUX_CAPABILITY_VERSION_3, .pid = 0 };
    struct __user_cap_data_struct d[2];
    memset(d, 0, sizeof(d));
    if (syscall(SYS_capget, &h, d) < 0)
        return -1;
    c->effective = (uint64_t)d[0].effective | ((uint64_t)d[1].effective << 32);
    c->permitted = (uint64_t)d[0].permitted | ((uint64_t)d[1].permitted << 32);
    c->inheritable = (uint64_t)d[0].inheritable | ((uint64_t)d[1].inheritable << 32);
    for (int i = 0; i <= last; ++i) {
        int b = prctl(PR_CAPBSET_READ, i, 0, 0, 0);
        int a = prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_IS_SET, i, 0, 0);
        if (b < 0 || a < 0)
            return -1;
        if (b != 0)
            c->bounding |= UINT64_C(1) << i;
        if (a != 0)
            c->ambient |= UINT64_C(1) << i;
    }
    c->nnp = prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0);
    return c->nnp < 0 ? -1 : 0;
}

static int cap_last(void)
{
    char text[32];
    if (read_path("/proc/sys/kernel/cap_last_cap", text, sizeof(text)) < 0)
        return -1;
    char *end;
    errno = 0;
    long n = strtol(text, &end, 10);
    if (errno != 0 || end == text || n < CAP_SETPCAP || n > 63 ||
        (*end != '\0' && !(*end == '\n' && end[1] == '\0')))
        return -1;
    return (int)n;
}

static int self_cgroup(pid_t pid, char *path, size_t size)
{
    char file[64], text[TEXT_MAX];
    (void)snprintf(file, sizeof(file), "/proc/%ld/cgroup", (long)pid);
    if (read_path(file, text, sizeof(text)) < 0)
        return -1;
    errno = EPROTO;
    bool found = false;
    char *p = text;
    while (*p != '\0') {
        char *end = strchr(p, '\n');
        if (end == NULL)
            return -1;
        *end = '\0';
        if (strncmp(p, "0::/", 4) == 0) {
            size_t n = strlen(p + 3);
            if (found || n >= size)
                return -1;
            memcpy(path, p + 3, n + 1);
            found = true;
        }
        p = end + 1;
    }
    return found ? 0 : -1;
}

static int namespace_snapshot(struct stat out[NS_COUNT])
{
    memset(out, 0, sizeof(struct stat) * NS_COUNT);
    for (int i = 0; i < NS_COUNT; ++i) {
        char path[64];
        (void)snprintf(path, sizeof(path), "/proc/self/ns/%s", ns_names[i]);
        if (stat(path, &out[i]) < 0 && errno != ENOENT)
            return -1;
    }
    return 0;
}

static bool same_inode(const struct stat *a, const struct stat *b)
{
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino &&
           (a->st_mode & S_IFMT) == (b->st_mode & S_IFMT);
}

static int anchor_open(struct anchor *a, int dirfd, const char *name, int flags)
{
    a->fd = openat(dirfd, name, flags | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (a->fd < 0)
        return -1;
    if (fstat(a->fd, &a->st) < 0)
        return -1;
    struct statfs fs;
    if (fstatfs(a->fd, &fs) < 0 || (unsigned long)fs.f_type != CGROUP2_SUPER_MAGIC) {
        errno = ENODEV;
        return -1;
    }
    return 0;
}

static void group_init(struct probe_group *g)
{
    memset(g, 0, sizeof(*g));
    g->root.fd = -1;
    g->dir.fd = -1;
    for (int i = 0; i < CT_COUNT; ++i)
        g->ctl[i].fd = -1;
}

static void group_close(struct probe_group *g)
{
    for (int i = 0; i < CT_COUNT; ++i)
        if (g->ctl[i].fd >= 0)
            (void)close(g->ctl[i].fd);
    if (g->dir.fd >= 0)
        (void)close(g->dir.fd);
    if (g->root.fd >= 0)
        (void)close(g->root.fd);
}

static int anchor_valid(const struct anchor *a)
{
    struct stat st;
    struct statfs fs;
    if (a->fd < 0 || fstat(a->fd, &st) < 0 || !same_inode(&st, &a->st) ||
        st.st_uid != a->st.st_uid || st.st_gid != a->st.st_gid ||
        st.st_mode != a->st.st_mode || fstatfs(a->fd, &fs) < 0 ||
        (unsigned long)fs.f_type != CGROUP2_SUPER_MAGIC) {
        errno = ESTALE;
        return -1;
    }
    return 0;
}

static int group_valid(const struct config *c, const struct probe_group *g, bool complete)
{
    struct stat st, ns[NS_COUNT];
    if (anchor_valid(&g->root) < 0 || anchor_valid(&g->dir) < 0 ||
        fstatat(g->root.fd, c->name, &st, AT_SYMLINK_NOFOLLOW) < 0 ||
        !same_inode(&st, &g->dir.st) || st.st_uid != 0 || st.st_gid != 0 ||
        (st.st_mode & 07777) != 0700 || namespace_snapshot(ns) < 0)
        return -1;
    for (int i = 0; i < NS_COUNT; ++i) {
        if (!same_inode(&ns[i], &g->namespaces[i])) {
            errno = ESTALE;
            return -1;
        }
    }
    char origin[TEXT_MAX];
    if (self_cgroup(getpid(), origin, sizeof(origin)) < 0 || strcmp(origin, g->origin) != 0) {
        errno = ESTALE;
        return -1;
    }
    for (int i = 0; i < CT_COUNT; ++i) {
        if (g->ctl[i].fd < 0) {
            if (complete) {
                errno = EBADF;
                return -1;
            }
            continue;
        }
        if (anchor_valid(&g->ctl[i]) < 0 ||
            fstatat(g->dir.fd, control_names[i], &st, AT_SYMLINK_NOFOLLOW) < 0 ||
            !same_inode(&st, &g->ctl[i].st) || st.st_uid != 0 || (st.st_mode & 0022) != 0) {
            errno = ESTALE;
            return -1;
        }
    }
    return 0;
}

static int binary_field(int fd, const char *name, int *out)
{
    char text[512];
    if (read_text_fd(fd, text, sizeof(text)) < 0)
        return -1;
    size_t len = strlen(name);
    const char *p = text;
    while (*p != '\0') {
        const char *end = strchr(p, '\n');
        if (end == NULL)
            return -1;
        if ((size_t)(end - p) == len + 2 && memcmp(p, name, len) == 0 &&
            p[len] == ' ' && (p[len + 1] == '0' || p[len + 1] == '1')) {
            *out = p[len + 1] - '0';
            return 0;
        }
        p = end + 1;
    }
    errno = EPROTO;
    return -1;
}

static int put_control(int fd, const char *text)
{
    size_t len = strlen(text);
    ssize_t n;
    do {
        n = pwrite(fd, text, len, 0);
    } while (n < 0 && errno == EINTR);
    if (n != (ssize_t)len) {
        if (n >= 0)
            errno = EIO;
        return -1;
    }
    return 0;
}

static int proc_stat(pid_t pid, uint64_t *start, pid_t *ppid)
{
    char path[64], text[TEXT_MAX];
    (void)snprintf(path, sizeof(path), "/proc/%ld/stat", (long)pid);
    if (read_path(path, text, sizeof(text)) < 0)
        return -1;
    errno = EPROTO;
    char *p = strrchr(text, ')');
    if (p == NULL || p[1] != ' ' || p[2] == '\0' || p[3] != ' ')
        return -1;
    p += 4;
    for (int field = 4; field <= 22; ++field) {
        char *end;
        errno = 0;
        unsigned long long n = strtoull(p, &end, 10);
        if (end == p || errno != 0 || (*end != ' ' && *end != '\n' && *end != '\0'))
            return -1;
        if (field == 4) {
            if (n > INT_MAX)
                return -1;
            *ppid = (pid_t)n;
        }
        if (field == 22) {
            *start = (uint64_t)n;
            return 0;
        }
        p = end;
        while (*p == ' ')
            ++p;
    }
    return -1;
}

static int parse_four(const char *line, unsigned int out[4])
{
    int used = 0;
    if (sscanf(line, "%u %u %u %u%n", &out[0], &out[1], &out[2], &out[3], &used) != 4)
        return -1;
    return line[used] == '\0' ? 0 : -1;
}

static int proc_identity(pid_t pid, struct identity *id)
{
    memset(id, 0, sizeof(*id));
    if (proc_stat(pid, &id->start, &id->ppid) < 0)
        return -1;
    char path[64], text[TEXT_MAX * 2];
    (void)snprintf(path, sizeof(path), "/proc/%ld/status", (long)pid);
    if (read_path(path, text, sizeof(text)) < 0)
        return -1;
    unsigned int seen = 0;
    char *save;
    for (char *line = strtok_r(text, "\n", &save); line != NULL; line = strtok_r(NULL, "\n", &save)) {
        unsigned int ids[4];
        if (strncmp(line, "Uid:\t", 5) == 0) {
            if (parse_four(line + 5, ids) < 0)
                return -1;
            for (int i = 0; i < 4; ++i)
                id->uid[i] = (uid_t)ids[i];
            seen |= 1;
        } else if (strncmp(line, "Gid:\t", 5) == 0) {
            if (parse_four(line + 5, ids) < 0)
                return -1;
            for (int i = 0; i < 4; ++i)
                id->gid[i] = (gid_t)ids[i];
            seen |= 2;
        } else if (strncmp(line, "Groups:\t", 8) == 0) {
            char *p = line + 8;
            while (*p == ' ' || *p == '\t')
                ++p;
            while (*p != '\0') {
                char *end;
                errno = 0;
                unsigned long n = strtoul(p, &end, 10);
                if (end == p || errno != 0 || n > INT_MAX || id->ngroups == MAX_GROUPS)
                    return -1;
                id->groups[id->ngroups++] = (gid_t)n;
                p = end;
                while (*p == ' ' || *p == '\t')
                    ++p;
            }
            seen |= 4;
        } else {
            static const char *const keys[6] = {
                "CapEff:\t", "CapPrm:\t", "CapInh:\t", "CapBnd:\t", "CapAmb:\t", "NoNewPrivs:\t"
            };
            uint64_t *values[5] = { &id->caps.effective, &id->caps.permitted,
                &id->caps.inheritable, &id->caps.bounding, &id->caps.ambient };
            for (int i = 0; i < 6; ++i) {
                size_t len = strlen(keys[i]);
                if (strncmp(line, keys[i], len) != 0)
                    continue;
                char *end;
                errno = 0;
                unsigned long long n = strtoull(line + len, &end, i == 5 ? 10 : 16);
                if (end == line + len || errno != 0 || *end != '\0' || (i == 5 && n > 1))
                    return -1;
                if (i == 5)
                    id->caps.nnp = (int)n;
                else
                    *values[i] = (uint64_t)n;
                seen |= 8U << i;
            }
        }
    }
    return seen == 511 ? 0 : -1;
}

static bool zero_caps(const struct caps *c)
{
    return c->effective == 0 && c->permitted == 0 && c->inheritable == 0 &&
           c->bounding == 0 && c->ambient == 0 && c->nnp == 1;
}

static bool app_identity(const struct config *c, const struct identity *id)
{
    for (int i = 0; i < 4; ++i)
        if (id->uid[i] != c->uid || id->gid[i] != c->gid)
            return false;
    if (id->ngroups != (int)c->ngroups || !zero_caps(&id->caps))
        return false;
    for (size_t i = 0; i < c->ngroups; ++i)
        if (id->groups[i] != c->groups[i])
            return false;
    return true;
}

static int member_list(const struct probe_group *g, pid_t pids[MAX_TASKS], size_t *count)
{
    char text[256];
    if (g->ctl[CT_PROCS].fd < 0 || read_text_fd(g->ctl[CT_PROCS].fd, text, sizeof(text)) < 0)
        return -1;
    *count = 0;
    char *p = text;
    while (*p != '\0') {
        if (*count == MAX_TASKS || *p < '1' || *p > '9')
            return -1;
        char *end;
        errno = 0;
        long n = strtol(p, &end, 10);
        if (errno != 0 || n <= 1 || n > INT_MAX || *end != '\n')
            return -1;
        for (size_t i = 0; i < *count; ++i)
            if (pids[i] == (pid_t)n)
                return -1;
        pids[(*count)++] = (pid_t)n;
        p = end + 1;
    }
    return 0;
}

static int no_subgroups(const struct probe_group *g)
{
    int fd = openat(g->dir.fd, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0)
        return -1;
    DIR *dir = fdopendir(fd);
    if (dir == NULL) {
        (void)close(fd);
        return -1;
    }
    int rc = 0;
    unsigned int count = 0;
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(dir);
        if (entry == NULL) {
            if (errno != 0)
                rc = -1;
            break;
        }
        if (++count > 128) {
            rc = -1;
            break;
        }
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0)
            continue;
        struct stat st;
        if (fstatat(fd, entry->d_name, &st, AT_SYMLINK_NOFOLLOW) < 0 || !S_ISREG(st.st_mode)) {
            rc = -1;
            break;
        }
    }
    (void)closedir(dir);
    return rc;
}

static int registered_member(const struct config *c, const struct known *task, bool armed)
{
    int fd = (int)syscall(SYS_pidfd_open, task->pid, 0);
    if (fd < 0)
        return errno == ESRCH ? 0 : -1;
    struct pollfd p = { .fd = fd, .events = POLLIN };
    int rc = poll(&p, 1, 0);
    if (rc == 1 && (p.revents & POLLIN) != 0) {
        (void)close(fd);
        return 0;
    }
    uint64_t start;
    pid_t parent;
    char path[TEXT_MAX];
    bool valid = rc == 0 && proc_stat(task->pid, &start, &parent) == 0 && start == task->start &&
                 self_cgroup(task->pid, path, sizeof(path)) == 0 && path[0] == '/' &&
                 strcmp(path + 1, c->name) == 0;
    if (valid && armed) {
        struct identity id;
        valid = proc_identity(task->pid, &id) == 0 && app_identity(c, &id);
    }
    p.revents = 0;
    rc = poll(&p, 1, 0);
    (void)close(fd);
    if (rc == 1 && (p.revents & POLLIN) != 0)
        return 0;
    if (!valid || rc != 0) {
        errno = ESTALE;
        return -1;
    }
    return 0;
}

static int registered_guard(const struct config *c, struct shared *s)
{
    if (load_u(&s->unsafe) != 0)
        return -1;
    for (int i = 0; i < MAX_TASKS; ++i) {
        if (load_u(&s->task[i].accepted) != 0 &&
            registered_member(c, &s->task[i], load_u(&s->armed) != 0) < 0) {
            store_u(&s->unsafe, 1);
            return -1;
        }
    }
    return 0;
}

static bool tasks_exited(const int fds[MAX_TASKS])
{
    for (int i = 0; i < MAX_TASKS; ++i) {
        if (fds[i] < 0)
            return false;
        struct pollfd p = { .fd = fds[i], .events = POLLIN };
        if (poll(&p, 1, 0) != 1 || (p.revents & POLLIN) == 0)
            return false;
    }
    return true;
}

static int member_guard(const struct config *c, const struct probe_group *g, struct shared *s)
{
    if (registered_guard(c, s) < 0 || group_valid(c, g, true) < 0 || no_subgroups(g) < 0)
        goto unsafe;
    pid_t pids[MAX_TASKS];
    size_t count;
    if (member_list(g, pids, &count) < 0)
        goto unsafe;
    for (size_t i = 0; i < count; ++i) {
        bool found = false;
        for (int j = 0; j < MAX_TASKS; ++j) {
            if (load_u(&s->task[j].accepted) == 0 || s->task[j].pid != pids[i])
                continue;
            uint64_t start;
            pid_t ppid;
            if (proc_stat(pids[i], &start, &ppid) < 0) {
                if (errno == ENOENT || errno == ESRCH) {
                    found = true;
                    break;
                }
                goto unsafe;
            }
            if (start != s->task[j].start)
                goto unsafe;
            char path[TEXT_MAX];
            if (self_cgroup(pids[i], path, sizeof(path)) < 0 ||
                strcmp(path + (path[0] == '/' ? 1 : 0), c->name) != 0)
                goto unsafe;
            if (load_u(&s->armed) != 0) {
                struct identity id;
                if (proc_identity(pids[i], &id) < 0 || !app_identity(c, &id))
                    goto unsafe;
            }
            found = true;
            break;
        }
        if (!found)
            goto unsafe;
    }
    return 0;
unsafe:
    store_u(&s->unsafe, 1);
    errno = ESTALE;
    return -1;
}

static int kill_group(const struct config *c, const struct probe_group *g, struct shared *s)
{
    /* Never turn a pathname or an unregistered PID into a kill target. */
    if (member_guard(c, g, s) < 0)
        return -1;
    if (put_control(g->ctl[CT_KILL].fd, "1\n") < 0)
        return -1;
    store_u(&s->killed, 1);
    return 0;
}

static int wait_empty(const struct config *c, const struct probe_group *g, struct shared *s, int64_t deadline)
{
    while (remaining_ms(deadline) > 0) {
        int populated;
        if (registered_guard(c, s) < 0 || group_valid(c, g, true) < 0 || no_subgroups(g) < 0) {
            store_u(&s->unsafe, 1);
            return -1;
        }
        if (binary_field(g->ctl[CT_EVENTS].fd, "populated", &populated) < 0)
            return -1;
        if (populated == 0) {
            pid_t pids[MAX_TASKS];
            size_t count;
            if (member_list(g, pids, &count) < 0 || count != 0)
                return -1;
            store_u(&s->empty, 1);
            return 0;
        }
        if (member_guard(c, g, s) < 0)
            return -1;
        delay_ms(20);
    }
    errno = ETIMEDOUT;
    return -1;
}

static int drop_privileges(const struct config *c)
{
    if (prctl(PR_SET_DUMPABLE, 0, 0, 0, 0) < 0 ||
        prctl(PR_SET_KEEPCAPS, 0, 0, 0, 0) < 0 ||
        prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL, 0, 0, 0) < 0 ||
        prctl(PR_SET_SECUREBITS, SECBIT_NOROOT | SECBIT_NOROOT_LOCKED, 0, 0, 0) < 0)
        return -1;
    for (int i = 0; i <= c->cap_last; ++i)
        if (prctl(PR_CAPBSET_DROP, i, 0, 0, 0) < 0)
            return -1;
    if (setgroups(c->ngroups, c->groups) < 0 ||
        setresgid(c->gid, c->gid, c->gid) < 0 || setresuid(c->uid, c->uid, c->uid) < 0)
        return -1;
    struct __user_cap_header_struct h = { .version = _LINUX_CAPABILITY_VERSION_3, .pid = 0 };
    struct __user_cap_data_struct d[2];
    memset(d, 0, sizeof(d));
    return syscall(SYS_capset, &h, d) < 0 ||
           prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL, 0, 0, 0) < 0 ||
           prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0 ? -1 : 0;
}

static int pipe_byte(int fd, unsigned char byte)
{
    ssize_t n;
    do {
        n = write(fd, &byte, 1);
    } while (n < 0 && errno == EINTR);
    return n == 1 ? 0 : -1;
}

static int read_byte(int fd, int64_t deadline, unsigned char *byte)
{
    while (remaining_ms(deadline) > 0) {
        struct pollfd p = { .fd = fd, .events = POLLIN | POLLHUP };
        int n = poll(&p, 1, remaining_ms(deadline));
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0) {
            errno = ETIMEDOUT;
            return -1;
        }
        ssize_t got = read(fd, byte, 1);
        if (got == 1)
            return 1;
        if (got == 0)
            return 0;
        if (errno != EINTR && errno != EAGAIN)
            return -1;
    }
    errno = ETIMEDOUT;
    return -1;
}

static int send_message(int fd, const struct message *m)
{
    ssize_t n;
    do {
        n = write(fd, m, sizeof(*m));
    } while (n < 0 && errno == EINTR);
    return n == (ssize_t)sizeof(*m) ? 0 : -1;
}

static void payload_error(int report, int role, int error)
{
    struct message m;
    memset(&m, 0, sizeof(m));
    m.type = MSG_ERROR;
    m.role = role;
    m.pid = getpid();
    m.error = error;
    (void)send_message(report, &m);
    _exit(120);
}

static int denied_open(const char *path)
{
    int fd = open(path, O_WRONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd >= 0) {
        (void)close(fd);
        return 0;
    }
    return errno;
}

static int payload_hello(const struct config *c, int report, int role)
{
    struct message m;
    memset(&m, 0, sizeof(m));
    m.type = MSG_HELLO;
    m.role = role;
    m.pid = getpid();
    m.ppid = getppid();
    m.sid = getsid(0);
    if (getresuid(&m.uid[0], &m.uid[1], &m.uid[2]) < 0 ||
        getresgid(&m.gid[0], &m.gid[1], &m.gid[2]) < 0 ||
        (m.ngroups = getgroups(MAX_GROUPS, m.groups)) < 0 ||
        cap_snapshot(&m.caps, c->cap_last) < 0)
        return -1;
    char path[160], current[TEXT_MAX];
    for (int i = 0; i < 3; ++i) {
        int which = i == 0 ? CT_PROCS : (i == 1 ? CT_KILL : CT_FREEZE);
        (void)snprintf(path, sizeof(path), "%s/%s", c->path, control_names[which]);
        m.denied[i] = denied_open(path);
    }
    m.denied[3] = denied_open(CGROOT "/cgroup.procs");
    if (c->app_group != 0) {
        (void)snprintf(path, sizeof(path), c->app_group == 1 ?
                       CGROOT "/uid_%u/cgroup.procs" : CGROOT "/apps/uid_%u/cgroup.procs",
                       (unsigned int)c->uid);
        m.denied[4] = denied_open(path);
    } else {
        m.denied[4] = -1;
    }
    if (self_cgroup(getpid(), current, sizeof(current)) == 0 &&
        current[0] == '/' && strcmp(current + 1, c->name) == 0)
        m.membership = 1;
    return send_message(report, &m);
}

static void payload_idle(int64_t deadline)
{
    while (remaining_ms(deadline) > 0) {
        int status;
        while (waitpid(-1, &status, WNOHANG) > 0) { }
        int ms = remaining_ms(deadline);
        delay_ms(ms < 100 ? ms : 100);
    }
    _exit(0);
}

static void payload(const struct config *c, int report, int gates[MAX_TASKS][2])
{
    int keep[7] = { report, gates[0][0], gates[1][0], gates[2][0], -1, -1, -1 };
    int64_t deadline = c->start + MS(LOAD_LIMIT_MS);
    if (close_except(keep, 4) < 0 || arm_death(deadline) < 0)
        _exit(120);
    unsigned char byte;
    if (read_byte(gates[0][0], deadline, &byte) != 1 || byte != 'S')
        payload_error(report, 0, ETIMEDOUT);
    if (drop_privileges(c) < 0 || payload_hello(c, report, 0) < 0)
        payload_error(report, 0, errno);
    if (read_byte(gates[0][0], deadline, &byte) != 1 || byte != 'F')
        payload_error(report, 0, ETIMEDOUT);
    pid_t first = fork();
    if (first < 0)
        payload_error(report, 0, errno);
    if (first > 0) {
        (void)close(gates[0][0]);
        (void)close(gates[1][0]);
        (void)close(gates[2][0]);
        (void)close(report);
        payload_idle(deadline);
    }
    if (arm_death(deadline) < 0 || setsid() < 0)
        payload_error(report, 1, errno);
    (void)close(gates[0][0]);
    if (payload_hello(c, report, 1) < 0)
        payload_error(report, 1, errno);
    if (read_byte(gates[1][0], deadline, &byte) != 1 || byte != 'F')
        payload_error(report, 1, ETIMEDOUT);
    pid_t second = fork();
    if (second < 0)
        payload_error(report, 1, errno);
    if (second > 0) {
        (void)close(gates[2][0]);
        (void)close(report);
        if (read_byte(gates[1][0], deadline, &byte) != 1 || byte != 'E')
            _exit(120);
        _exit(0);
    }
    if (arm_death(deadline) < 0)
        payload_error(report, 2, errno);
    (void)close(gates[1][0]);
    if (payload_hello(c, report, 2) < 0)
        payload_error(report, 2, errno);
    if (read_byte(gates[2][0], deadline, &byte) != 1 || byte != 'F')
        payload_error(report, 2, ETIMEDOUT);
    (void)close(gates[2][0]);
    (void)close(report);
    payload_idle(deadline);
}

static void controller(const struct config *c, int owner, int lifetime, int report,
                       int gates[MAX_TASKS][2])
{
    int keep[6] = { owner, lifetime, report, gates[0][0], gates[1][0], gates[2][0] };
    if (close_except(keep, 6) < 0 || arm_death(c->start + MS(10000)) < 0)
        _exit(121);
    pid_t child = fork();
    if (child < 0)
        payload_error(report, 0, errno);
    if (child == 0)
        payload(c, report, gates);
    for (int i = 0; i < MAX_TASKS; ++i)
        (void)close(gates[i][0]);
    struct message m;
    memset(&m, 0, sizeof(m));
    m.type = MSG_BORN;
    m.pid = child;
    if (send_message(report, &m) < 0)
        _exit(121);
    (void)close(report);
    unsigned char byte;
    int rc = read_byte(owner, c->start + MS(ACTION_LIMIT_MS), &byte);
    if (rc == 1 && byte == 'C') {
        (void)raise(SIGKILL);
        _exit(125);
    }
    if (rc == 0) {
        (void)pipe_byte(lifetime, 'E');
        _exit(0);
    }
    if (rc == 1 && byte == 'B') {
        (void)pipe_byte(lifetime, 'B');
        _exit(0);
    }
    _exit(122);
}

static void watchdog(const struct config *c, const struct probe_group *g, struct shared *s, int lifetime)
{
    int keep[CT_COUNT + 3] = { lifetime, g->root.fd, g->dir.fd,
        g->ctl[0].fd, g->ctl[1].fd, g->ctl[2].fd, g->ctl[3].fd };
    if (close_except(keep, CT_COUNT + 3) < 0 ||
        arm_death(c->start + MS(12500)) < 0 || group_valid(c, g, true) < 0) {
        s->watchdog_errno = errno;
        store_u(&s->watchdog_done, 1);
        _exit(123);
    }
    store_u(&s->watchdog_ready, 1);
    unsigned char byte = 0;
    int rc = read_byte(lifetime, c->start + MS(ACTION_LIMIT_MS), &byte);
    unsigned int trigger = rc == 0 ? 3U : (rc < 0 ? 4U : (byte == 'B' ? 1U : (byte == 'E' ? 2U : 5U)));
    store_u(&s->trigger, trigger);
    if (kill_group(c, g, s) < 0) {
        s->watchdog_errno = errno;
    } else if (wait_empty(c, g, s, c->start + MS(WATCH_LIMIT_MS)) < 0) {
        s->watchdog_errno = errno;
    }
    store_u(&s->watchdog_done, 1);
    _exit(s->watchdog_errno == 0 ? 0 : 124);
}

static int pidfd_open_fixed(pid_t pid)
{
    return (int)syscall(SYS_pidfd_open, pid, 0);
}

static int pidfd_signal_fixed(int fd, int sig)
{
    return (int)syscall(SYS_pidfd_send_signal, fd, sig, NULL, 0);
}

static int enroll(struct shared *s, int role, pid_t pid, int *pidfd)
{
    if (role < 0 || role >= MAX_TASKS || pid <= 1 || load_u(&s->task[role].accepted) != 0)
        return -1;
    int fd = pidfd_open_fixed(pid);
    if (fd < 0)
        return -1;
    uint64_t start;
    pid_t parent;
    if (proc_stat(pid, &start, &parent) < 0) {
        (void)close(fd);
        return -1;
    }
    struct pollfd p = { .fd = fd, .events = POLLIN };
    if (poll(&p, 1, 0) != 0) {
        (void)close(fd);
        return -1;
    }
    s->task[role].pid = pid;
    s->task[role].start = start;
    *pidfd = fd;
    store_u(&s->task[role].accepted, 1);
    return 0;
}

static int check_hello(const struct config *c, const struct message *m,
                       const struct shared *s, pid_t controller_pid)
{
    int role = m->role;
    if (role < 0 || role >= MAX_TASKS || m->pid <= 1 || m->membership != 1 ||
        m->ngroups != (int)c->ngroups || !zero_caps(&m->caps))
        return -1;
    for (int i = 0; i < 3; ++i)
        if (m->uid[i] != c->uid || m->gid[i] != c->gid)
            return -1;
    for (size_t i = 0; i < c->ngroups; ++i)
        if (m->groups[i] != c->groups[i])
            return -1;
    for (int i = 0; i < 4; ++i)
        if (m->denied[i] != EACCES)
            return -1;
    if ((c->app_group != 0 && m->denied[4] != EACCES) ||
        (c->app_group == 0 && m->denied[4] != -1))
        return -1;
    struct identity id;
    if (proc_identity(m->pid, &id) < 0 || !app_identity(c, &id))
        return -1;
    pid_t expected_parent = role == 0 ? controller_pid : s->task[role - 1].pid;
    if (id.ppid != expected_parent || m->ppid != expected_parent)
        return -1;
    if ((role == 1 && m->sid != m->pid) ||
        (role == 2 && m->sid != s->task[1].pid))
        return -1;
    char path[TEXT_MAX];
    if (self_cgroup(m->pid, path, sizeof(path)) < 0 || path[0] != '/' || strcmp(path + 1, c->name) != 0)
        return -1;
    return 0;
}

static void report_hello(const struct config *c, const struct message *m)
{
    char groups[32];
    if (c->ngroups == 0)
        (void)snprintf(groups, sizeof(groups), "[]");
    else
        (void)snprintf(groups, sizeof(groups), "[%u]", (unsigned int)m->groups[0]);
    emit("{\"event\":\"payload\",\"role\":%d,\"pid\":%ld,\"ppid\":%ld,\"sid\":%ld,"
         "\"uid\":%u,\"gid\":%u,\"groups\":%s,\"cap_eff\":\"%016" PRIx64 "\","
         "\"cap_prm\":\"%016" PRIx64 "\",\"cap_inh\":\"%016" PRIx64 "\","
         "\"cap_bnd\":\"%016" PRIx64 "\",\"cap_amb\":\"%016" PRIx64 "\","
         "\"nnp\":%d,\"in_created_group\":true,\"procs_open_errno\":%d,"
         "\"kill_open_errno\":%d,\"freeze_open_errno\":%d,\"root_procs_open_errno\":%d,"
         "\"app_procs_open_errno\":%d}\n", m->role, (long)m->pid, (long)m->ppid, (long)m->sid,
         (unsigned int)m->uid[0], (unsigned int)m->gid[0], groups,
         m->caps.effective, m->caps.permitted, m->caps.inheritable, m->caps.bounding,
         m->caps.ambient, m->caps.nnp, m->denied[0], m->denied[1], m->denied[2],
         m->denied[3], m->denied[4]);
}

static int read_message(int fd, struct message *m, int64_t deadline)
{
    while (remaining_ms(deadline) > 0) {
        struct pollfd p = { .fd = fd, .events = POLLIN | POLLHUP };
        int rc = poll(&p, 1, remaining_ms(deadline));
        if (rc < 0 && errno == EINTR)
            continue;
        if (rc <= 0) {
            errno = ETIMEDOUT;
            return -1;
        }
        ssize_t n = read(fd, m, sizeof(*m));
        if (n == (ssize_t)sizeof(*m))
            return 0;
        if (n < 0 && (errno == EAGAIN || errno == EINTR))
            continue;
        errno = EPROTO;
        return -1;
    }
    errno = ETIMEDOUT;
    return -1;
}

static int find_app_group(const struct config *c, int rootfd)
{
    char uidname[48];
    (void)snprintf(uidname, sizeof(uidname), "uid_%u", (unsigned int)c->uid);
    for (int kind = 1; kind <= 2; ++kind) {
        int parent = rootfd;
        if (kind == 2) {
            parent = openat(rootfd, "apps", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
            if (parent < 0)
                continue;
        }
        int dir = openat(parent, uidname, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (kind == 2)
            (void)close(parent);
        if (dir < 0)
            continue;
        struct statfs fs;
        int fd = -1;
        if (fstatfs(dir, &fs) == 0 && (unsigned long)fs.f_type == CGROUP2_SUPER_MAGIC)
            fd = openat(dir, "cgroup.procs", O_WRONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
        int saved = fd >= 0 ? 0 : errno;
        if (fd >= 0)
            (void)close(fd);
        (void)close(dir);
        emit("{\"event\":\"migration_open\",\"target\":\"app_uid\",\"layout\":%d,\"errno\":%d,\"written\":false}\n",
             kind, saved);
        return kind;
    }
    emit("{\"event\":\"migration_open\",\"target\":\"app_uid\",\"layout\":0,\"errno\":%d,\"written\":false}\n", ENOENT);
    return 0;
}

static int create_group(struct config *c, struct probe_group *g)
{
    if (anchor_open(&g->root, AT_FDCWD, CGROOT, O_RDONLY | O_DIRECTORY) < 0 ||
        self_cgroup(getpid(), g->origin, sizeof(g->origin)) < 0 ||
        namespace_snapshot(g->namespaces) < 0)
        return -1;
    int fd = openat(g->root.fd, "cgroup.procs", O_WRONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    int saved = fd < 0 ? errno : 0;
    if (fd >= 0)
        (void)close(fd);
    emit("{\"event\":\"migration_open\",\"target\":\"root\",\"errno\":%d,\"written\":false}\n", saved);
    c->app_group = find_app_group(c, g->root.fd);
    mode_t oldmask = umask(077);
    int rc = mkdirat(g->root.fd, c->name, 0700);
    saved = errno;
    (void)umask(oldmask);
    if (rc < 0) {
        errno = saved;
        return -1;
    }
    g->created = true;
    if (anchor_open(&g->dir, g->root.fd, c->name, O_RDONLY | O_DIRECTORY) < 0)
        return -1;
    if (g->dir.st.st_uid != 0 || !S_ISDIR(g->dir.st.st_mode) ||
        fchown(g->dir.fd, 0, 0) < 0 || fchmod(g->dir.fd, 0700) < 0 ||
        fstat(g->dir.fd, &g->dir.st) < 0)
        return -1;
    int flags[CT_COUNT] = { O_RDWR, O_WRONLY, O_RDONLY, O_RDWR };
    for (int i = 0; i < CT_COUNT; ++i)
        if (anchor_open(&g->ctl[i], g->dir.fd, control_names[i], flags[i]) < 0)
            return -1;
    if (group_valid(c, g, true) < 0)
        return -1;
    int typefd = openat(g->dir.fd, "cgroup.type", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    char type[32];
    if (typefd < 0)
        return -1;
    rc = read_text_fd(typefd, type, sizeof(type));
    saved = errno;
    (void)close(typefd);
    if (rc < 0 || strcmp(type, "domain\n") != 0) {
        errno = rc < 0 ? saved : ENOTSUP;
        return -1;
    }
    int populated, frozen;
    if (binary_field(g->ctl[CT_EVENTS].fd, "populated", &populated) < 0 || populated != 0 ||
        binary_field(g->ctl[CT_EVENTS].fd, "frozen", &frozen) < 0 || frozen != 0) {
        errno = EBUSY;
        return -1;
    }
    emit("{\"event\":\"created\",\"uid\":0,\"gid\":0,\"mode\":448,\"domain\":true,"
         "\"dev\":%ju,\"ino\":%ju}\n", (uintmax_t)g->dir.st.st_dev, (uintmax_t)g->dir.st.st_ino);
    return 0;
}

static int freeze_round(const struct config *c, const struct probe_group *g, struct shared *s)
{
    if (member_guard(c, g, s) < 0 || put_control(g->ctl[CT_FREEZE].fd, "1\n") < 0)
        return -1;
    int64_t deadline = now_ns() + MS(700);
    bool frozen = false;
    while (remaining_ms(deadline) > 0) {
        int value;
        if (member_guard(c, g, s) < 0 || binary_field(g->ctl[CT_EVENTS].fd, "frozen", &value) < 0)
            return -1;
        if (value == 1) {
            frozen = true;
            break;
        }
        delay_ms(10);
    }
    if (member_guard(c, g, s) < 0 || put_control(g->ctl[CT_FREEZE].fd, "0\n") < 0)
        return -1;
    deadline = now_ns() + MS(500);
    bool thawed = false;
    while (remaining_ms(deadline) > 0) {
        int value;
        if (member_guard(c, g, s) < 0 || binary_field(g->ctl[CT_EVENTS].fd, "frozen", &value) < 0)
            return -1;
        if (value == 0) {
            thawed = true;
            break;
        }
        delay_ms(10);
    }
    emit("{\"event\":\"freeze\",\"frozen_observed\":%s,\"thawed_observed\":%s}\n",
         frozen ? "true" : "false", thawed ? "true" : "false");
    if (!frozen || !thawed) {
        errno = ETIMEDOUT;
        return -1;
    }
    return 0;
}

static bool reap_fixed(pid_t controller_pid, int64_t deadline, int *controller_status)
{
    while (remaining_ms(deadline) > 0) {
        int status;
        pid_t got = waitpid(-1, &status, WNOHANG);
        if (got > 0) {
            if (got == controller_pid)
                *controller_status = status;
            continue;
        }
        if (got < 0 && errno == ECHILD)
            return true;
        if (got < 0 && errno != EINTR)
            return false;
        delay_ms(10);
    }
    return false;
}

static int remove_empty(const struct config *c, const struct probe_group *g, struct shared *s)
{
    if (load_u(&s->unsafe) != 0 || group_valid(c, g, false) < 0)
        return -1;
    int procs = g->ctl[CT_PROCS].fd;
    int events = g->ctl[CT_EVENTS].fd;
    bool opened_procs = false, opened_events = false;
    if (procs < 0) {
        procs = openat(g->dir.fd, "cgroup.procs", O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
        opened_procs = true;
    }
    if (events < 0) {
        events = openat(g->dir.fd, "cgroup.events", O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
        opened_events = true;
    }
    char text[256];
    int populated;
    bool empty = procs >= 0 && events >= 0 && read_text_fd(procs, text, sizeof(text)) == 0 &&
                 binary_field(events, "populated", &populated) == 0 && populated == 0;
    if (opened_procs && procs >= 0)
        (void)close(procs);
    if (opened_events && events >= 0)
        (void)close(events);
    if (!empty || group_valid(c, g, false) < 0) {
        errno = ESTALE;
        return -1;
    }
    return unlinkat(g->root.fd, c->name, AT_REMOVEDIR);
}

static int inspect(struct config *c)
{
    struct probe_group g;
    group_init(&g);
    c->cap_last = cap_last();
    struct caps caps;
    struct utsname uts;
    struct stat ns[NS_COUNT];
    if (c->cap_last < 0 || cap_snapshot(&caps, c->cap_last) < 0 || uname(&uts) < 0 ||
        namespace_snapshot(ns) < 0 || anchor_open(&g.root, AT_FDCWD, CGROOT, O_RDONLY | O_DIRECTORY) < 0) {
        emit("{\"event\":\"error\",\"errno\":%d}\n", errno);
        group_close(&g);
        final_event(c, 3, "inspect_unavailable", "inspect", "not_created", false);
        return 3;
    }
    bool arm64 = strcmp(uts.machine, "aarch64") == 0;
    emit("{\"event\":\"inspect\",\"architecture\":\"%s\",\"cgroup2\":true,"
         "\"uid\":%u,\"gid\":%u,\"root_uid\":%u,\"root_gid\":%u,\"root_mode\":%u,"
         "\"root_dev\":%ju,\"root_ino\":%ju,\"cap_eff\":\"%016" PRIx64 "\","
         "\"cap_prm\":\"%016" PRIx64 "\",\"cap_inh\":\"%016" PRIx64 "\","
         "\"cap_bnd\":\"%016" PRIx64 "\",\"cap_amb\":\"%016" PRIx64 "\",\"nnp\":%d}\n",
         arm64 ? "aarch64" : "other", (unsigned int)getuid(), (unsigned int)getgid(),
         (unsigned int)g.root.st.st_uid, (unsigned int)g.root.st.st_gid,
         (unsigned int)(g.root.st.st_mode & 07777), (uintmax_t)g.root.st.st_dev,
         (uintmax_t)g.root.st.st_ino, caps.effective, caps.permitted, caps.inheritable,
         caps.bounding, caps.ambient, caps.nnp);
    for (int i = 0; i < NS_COUNT; ++i) {
        char path[64];
        struct stat init;
        (void)snprintf(path, sizeof(path), "/proc/1/ns/%s", ns_names[i]);
        bool readable = stat(path, &init) == 0;
        emit("{\"event\":\"namespace\",\"kind\":\"%s\",\"dev\":%ju,\"ino\":%ju,"
             "\"init_readable\":%s,\"same_as_init\":%s}\n", ns_names[i],
             (uintmax_t)ns[i].st_dev, (uintmax_t)ns[i].st_ino, readable ? "true" : "false",
             readable && same_inode(&ns[i], &init) ? "true" : "false");
    }
    int fd = openat(g.root.fd, "cgroup.controllers", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    char controllers[512];
    int len = fd >= 0 ? read_text_fd(fd, controllers, sizeof(controllers)) : -1;
    if (fd >= 0)
        (void)close(fd);
    bool controllers_empty = len >= 0;
    bool pids = false;
    if (len >= 0) {
        for (int i = 0; i < len; ++i)
            if (!isspace((unsigned char)controllers[i]))
                controllers_empty = false;
        char *save;
        for (char *p = strtok_r(controllers, " \t\n", &save); p != NULL; p = strtok_r(NULL, " \t\n", &save))
            if (strcmp(p, "pids") == 0)
                pids = true;
    }
    struct stat st;
    bool kill_present = fstatat(g.root.fd, "cgroup.kill", &st, AT_SYMLINK_NOFOLLOW) == 0 && S_ISREG(st.st_mode);
    bool freeze_present = fstatat(g.root.fd, "cgroup.freeze", &st, AT_SYMLINK_NOFOLLOW) == 0 && S_ISREG(st.st_mode);
    bool events_present = fstatat(g.root.fd, "cgroup.events", &st, AT_SYMLINK_NOFOLLOW) == 0 && S_ISREG(st.st_mode);
    char own[TEXT_MAX];
    bool own_readable = self_cgroup(getpid(), own, sizeof(own)) == 0;
    emit("{\"event\":\"cgroup_metadata\",\"controllers_readable\":%s,\"controllers_empty\":%s,"
         "\"pids_controller\":%s,\"root_kill_present\":%s,\"root_freeze_present\":%s,"
         "\"root_events_present\":%s,\"self_cgroup_readable\":%s}\n",
         len >= 0 ? "true" : "false", controllers_empty ? "true" : "false", pids ? "true" : "false",
         kill_present ? "true" : "false", freeze_present ? "true" : "false",
         events_present ? "true" : "false", own_readable ? "true" : "false");
    group_close(&g);
    int rc = len >= 0 && own_readable ? 0 : 3;
    final_event(c, rc, rc == 0 ? "none" : "inspect_incomplete", "inspect", "not_created", false);
    return rc;
}

static int exercise(struct config *c)
{
    struct probe_group g;
    group_init(&g);
    struct shared *s = MAP_FAILED;
    int owner[2] = { -1, -1 }, lifetime[2] = { -1, -1 }, report[2] = { -1, -1 };
    int gates[MAX_TASKS][2];
    int taskfd[MAX_TASKS] = { -1, -1, -1 };
    int controllerfd = -1, watchdogfd = -1;
    pid_t controller_pid = -1, watchdog_pid = -1;
    bool started = false, triggered = false;
    bool completed = false, reclaimed = false;
    int rc = 3, saved = 0, controller_status = -1;
    const char *error = "capability_missing", *stage = "preflight";
    const char *cleanup = "not_created";
    for (int i = 0; i < MAX_TASKS; ++i)
        gates[i][0] = gates[i][1] = -1;
    c->start = now_ns();
    if (c->start < 0 || arm_death(c->start + MS(TOTAL_LIMIT_MS)) < 0)
        goto done;
    struct utsname uts;
    struct caps caps;
    uid_t r, e, u;
    gid_t gr, ge, gs;
    if (getresuid(&r, &e, &u) < 0 || getresgid(&gr, &ge, &gs) < 0 ||
        r != 0 || e != 0 || u != 0 || gr != 0 || ge != 0 || gs != 0 ||
        uname(&uts) < 0 || strcmp(uts.machine, "aarch64") != 0 ||
        (c->cap_last = cap_last()) < 0 || cap_snapshot(&caps, c->cap_last) < 0 ||
        (caps.effective & ((UINT64_C(1) << CAP_SETUID) | (UINT64_C(1) << CAP_SETGID) |
                           (UINT64_C(1) << CAP_SETPCAP))) !=
                          ((UINT64_C(1) << CAP_SETUID) | (UINT64_C(1) << CAP_SETGID) |
                           (UINT64_C(1) << CAP_SETPCAP)))
        goto done;
    if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) < 0)
        goto done;
    int self_fd = pidfd_open_fixed(getpid());
    if (self_fd < 0)
        goto done;
    int test_rc = pidfd_signal_fixed(self_fd, 0);
    saved = errno;
    (void)close(self_fd);
    if (test_rc < 0) {
        errno = saved;
        goto done;
    }
    s = mmap(NULL, sizeof(*s), PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (s == MAP_FAILED)
        goto done;
    memset(s, 0, sizeof(*s));
    if (pipe2(owner, O_CLOEXEC | O_NONBLOCK) < 0 || pipe2(lifetime, O_CLOEXEC | O_NONBLOCK) < 0 ||
        pipe2(report, O_CLOEXEC | O_NONBLOCK) < 0)
        goto done;
    for (int i = 0; i < MAX_TASKS; ++i)
        if (pipe2(gates[i], O_CLOEXEC | O_NONBLOCK) < 0)
            goto done;
    stage = "create";
    if (create_group(c, &g) < 0) {
        error = errno == EEXIST ? "group_exists" : "cgroup_unavailable";
        goto done;
    }
    stage = "watchdog";
    watchdog_pid = fork();
    if (watchdog_pid < 0)
        goto done;
    if (watchdog_pid == 0)
        watchdog(c, &g, s, lifetime[0]);
    started = true;
    watchdogfd = pidfd_open_fixed(watchdog_pid);
    if (watchdogfd < 0)
        goto done;
    int64_t ready_deadline = c->start + MS(1200);
    while (load_u(&s->watchdog_ready) == 0 && load_u(&s->watchdog_done) == 0 &&
           remaining_ms(ready_deadline) > 0)
        delay_ms(5);
    if (load_u(&s->watchdog_ready) == 0)
        goto done;
    stage = "controller";
    controller_pid = fork();
    if (controller_pid < 0)
        goto done;
    if (controller_pid == 0) {
        if (munmap(s, sizeof(*s)) < 0)
            _exit(121);
        controller(c, owner[0], lifetime[1], report[1], gates);
    }
    controllerfd = pidfd_open_fixed(controller_pid);
    if (controllerfd < 0)
        goto done;
    (void)close(owner[0]); owner[0] = -1;
    (void)close(lifetime[0]); lifetime[0] = -1;
    (void)close(lifetime[1]); lifetime[1] = -1;
    (void)close(report[1]); report[1] = -1;
    for (int i = 0; i < MAX_TASKS; ++i) {
        (void)close(gates[i][0]);
        gates[i][0] = -1;
    }
    rc = 4;
    error = "payload_failed";
    stage = "payload";
    bool born = false;
    int hello = 0;
    while (hello < MAX_TASKS && remaining_ms(c->start + MS(4500)) > 0) {
        struct message m;
        if (read_message(report[0], &m, c->start + MS(4500)) < 0)
            goto done;
        if (m.type == MSG_ERROR) {
            errno = m.error;
            goto done;
        }
        if (m.type == MSG_BORN) {
            uint64_t start;
            pid_t parent;
            if (born || m.role != 0 || proc_stat(m.pid, &start, &parent) < 0 ||
                parent != controller_pid || group_valid(c, &g, true) < 0 ||
                enroll(s, 0, m.pid, &taskfd[0]) < 0)
                goto done;
            char pidtext[32];
            (void)snprintf(pidtext, sizeof(pidtext), "%ld\n", (long)m.pid);
            if (put_control(g.ctl[CT_PROCS].fd, pidtext) < 0)
                goto done;
            char current[TEXT_MAX];
            if (self_cgroup(m.pid, current, sizeof(current)) < 0 ||
                current[0] != '/' || strcmp(current + 1, c->name) != 0)
                goto done;
            if (pipe_byte(gates[0][1], 'S') < 0)
                goto done;
            born = true;
        } else if (m.type == MSG_HELLO) {
            if (!born || m.role != hello || check_hello(c, &m, s, controller_pid) < 0)
                goto done;
            if (m.role == 0) {
                if (m.pid != s->task[0].pid)
                    goto done;
            } else if (enroll(s, m.role, m.pid, &taskfd[m.role]) < 0) {
                goto done;
            }
            report_hello(c, &m);
            if (pipe_byte(gates[m.role][1], 'F') < 0)
                goto done;
            ++hello;
        } else {
            errno = EPROTO;
            goto done;
        }
    }
    if (hello != MAX_TASKS) {
        errno = ETIMEDOUT;
        goto done;
    }
    store_u(&s->armed, 1);
    if (member_guard(c, &g, s) < 0 || pipe_byte(gates[1][1], 'E') < 0)
        goto done;
    stage = "double_fork";
    bool orphan = false;
    int64_t orphan_deadline = now_ns() + MS(500);
    while (remaining_ms(orphan_deadline) > 0) {
        uint64_t start;
        pid_t parent;
        if (proc_stat(s->task[2].pid, &start, &parent) == 0 && start == s->task[2].start && parent == getpid()) {
            orphan = true;
            break;
        }
        delay_ms(5);
    }
    if (!orphan) {
        errno = ETIMEDOUT;
        goto done;
    }
    emit("{\"event\":\"double_fork\",\"adopted_by_observer\":true,\"max_app_processes\":3}\n");
    if (c->mode == MODE_BASELINE) {
        stage = "freeze";
        error = "freeze_failed";
        if (freeze_round(c, &g, s) < 0)
            goto done;
    }
    stage = "trigger";
    error = "watchdog_failed";
    if (c->mode == MODE_EOF) {
        (void)close(owner[1]); owner[1] = -1;
    } else if (pipe_byte(owner[1], c->mode == MODE_CRASH ? 'C' : 'B') < 0) {
        goto done;
    }
    triggered = true;
    while (load_u(&s->watchdog_done) == 0 && remaining_ms(c->start + MS(WATCH_LIMIT_MS + 100)) > 0)
        delay_ms(10);
    unsigned int expected = c->mode == MODE_BASELINE ? 1U : (c->mode == MODE_EOF ? 2U : 3U);
    emit("{\"event\":\"watchdog\",\"trigger\":%u,\"done\":%s,\"kill_written\":%s,"
         "\"empty_observed\":%s,\"errno\":%d}\n", load_u(&s->trigger),
         load_u(&s->watchdog_done) != 0 ? "true" : "false", load_u(&s->killed) != 0 ? "true" : "false",
         load_u(&s->empty) != 0 ? "true" : "false", s->watchdog_errno);
    if (load_u(&s->watchdog_done) == 0 || s->watchdog_errno != 0 || load_u(&s->trigger) != expected ||
        load_u(&s->killed) == 0 || load_u(&s->empty) == 0)
        goto done;
    if (!tasks_exited(taskfd)) {
        store_u(&s->unsafe, 1);
        errno = ESTALE;
        error = "live_registered_task";
        goto done;
    }
    completed = true;
    rc = 0;
    error = "none";
    stage = "complete";
done:
    saved = errno;
    if (s != MAP_FAILED && started) {
        if (owner[1] >= 0) { (void)close(owner[1]); owner[1] = -1; }
        if (lifetime[1] >= 0) { (void)close(lifetime[1]); lifetime[1] = -1; }
        if (!triggered && controllerfd >= 0 && load_u(&s->unsafe) == 0)
            (void)pidfd_signal_fixed(controllerfd, SIGKILL);
        while (load_u(&s->watchdog_done) == 0 && remaining_ms(c->start + MS(11800)) > 0)
            delay_ms(10);
        if (load_u(&s->unsafe) == 0 && g.ctl[CT_KILL].fd >= 0 &&
            load_u(&s->watchdog_done) != 0 && load_u(&s->empty) == 0) {
            if (kill_group(c, &g, s) == 0)
                (void)wait_empty(c, &g, s, c->start + MS(12300));
        }
        if (!completed && load_u(&s->unsafe) == 0) {
            for (int i = 0; i < MAX_TASKS; ++i)
                if (taskfd[i] >= 0)
                    (void)pidfd_signal_fixed(taskfd[i], SIGKILL);
            if (controllerfd >= 0)
                (void)pidfd_signal_fixed(controllerfd, SIGKILL);
        }
        if (watchdogfd >= 0 && load_u(&s->watchdog_done) == 0 && load_u(&s->unsafe) == 0)
            (void)pidfd_signal_fixed(watchdogfd, SIGKILL);
        reclaimed = reap_fixed(controller_pid, c->start + MS(13200), &controller_status);
        if (!reclaimed)
            store_u(&s->unsafe, 1);
        bool expected_status = controller_status >= 0 && (c->mode == MODE_CRASH ?
            (WIFSIGNALED(controller_status) && WTERMSIG(controller_status) == SIGKILL) :
            (WIFEXITED(controller_status) && WEXITSTATUS(controller_status) == 0));
        if (completed && !expected_status) {
            rc = 4;
            error = "controller_status";
            stage = "reap";
        }
        emit("{\"event\":\"reap\",\"known_children_reclaimed\":%s,\"controller_exited\":%s,"
             "\"controller_exit\":%d,\"controller_signal\":%d}\n", reclaimed ? "true" : "false",
             controller_status >= 0 && WIFEXITED(controller_status) ? "true" : "false",
             controller_status >= 0 && WIFEXITED(controller_status) ? WEXITSTATUS(controller_status) : -1,
             controller_status >= 0 && WIFSIGNALED(controller_status) ? WTERMSIG(controller_status) : 0);
    }
    if (g.created) {
        if (s != MAP_FAILED && (!started || reclaimed) && remove_empty(c, &g, s) == 0) {
            cleanup = "removed";
        } else {
            cleanup = "cleanup_unknown";
            rc = 5;
            error = "cleanup_unknown";
            stage = "cleanup";
        }
    }
    for (int i = 0; i < MAX_TASKS; ++i) {
        if (taskfd[i] >= 0) (void)close(taskfd[i]);
        for (int j = 0; j < 2; ++j)
            if (gates[i][j] >= 0) (void)close(gates[i][j]);
    }
    int fds[8] = { owner[0], owner[1], lifetime[0], lifetime[1], report[0], report[1], controllerfd, watchdogfd };
    for (size_t i = 0; i < sizeof(fds) / sizeof(fds[0]); ++i)
        if (fds[i] >= 0)
            (void)close(fds[i]);
    group_close(&g);
    if (s != MAP_FAILED)
        (void)munmap(s, sizeof(*s));
    if (rc != 0)
        emit("{\"event\":\"error\",\"errno\":%d}\n", saved);
    final_event(c, rc, error, stage, cleanup, g.created);
    return rc;
}

int main(int argc, char **argv)
{
    struct config c;
    int valid = parse_args(argc, argv, &c);
    (void)signal(SIGPIPE, SIG_IGN);
    int flags = fcntl(output_fd, F_GETFL);
    if (flags < 0 || fcntl(output_fd, F_SETFL, flags | O_NONBLOCK) < 0)
        return 3;
    if (valid < 0) {
        c.mode = MODE_NONE;
        final_event(&c, 2, "invalid_arguments", "arguments", "not_created", false);
        return 2;
    }
    if (c.mode == MODE_HELP) {
        emit("{\"event\":\"help\",\"interfaces\":[\"--help\",\"--inspect\","
             "\"--exercise <24-lowercase-hex> <uid>=10000..2147483647 <gid=uid> <-|3003> <baseline|eof|supervisor-crash>\"],"
             "\"max_app_processes\":3,\"deadline_ms\":14000}\n");
        final_event(&c, 0, "none", "help", "not_created", false);
        return 0;
    }
    int keep[1] = { STDOUT_FILENO };
    if (close_except(keep, 1) < 0) {
        final_event(&c, 3, "close_range_unavailable", "preflight", "not_created", false);
        return 3;
    }
    return c.mode == MODE_INSPECT ? inspect(&c) : exercise(&c);
}
