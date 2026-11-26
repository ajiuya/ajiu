#define _GNU_SOURCE
#include "runtime_broker_protocol.h"

#include <dirent.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

struct broker_arguments {
    char name[AJIU_BROKER_MAX_SOCKET_NAME + 1U];
    unsigned char nonce[AJIU_BROKER_NONCE_SIZE];
    uint32_t uid, pid;
    uint64_t start;
};

struct broker_executable {
    int fd;
    struct stat identity;
};

struct broker_job {
    uint64_t id;
    pid_t pid;
    int pidfd, status;
    bool stopped;
};

static volatile sig_atomic_t broker_interrupted;

static uint16_t get16(const unsigned char *p)
{
    return (uint16_t)((uint16_t)p[0] << 8 | p[1]);
}

static uint32_t get32(const unsigned char *p)
{
    return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3];
}

static uint64_t get64(const unsigned char *p)
{
    return (uint64_t)get32(p) << 32 | get32(p + 4);
}

static void put16(unsigned char *p, uint16_t v)
{
    p[0] = (unsigned char)(v >> 8); p[1] = (unsigned char)v;
}

static void put32(unsigned char *p, uint32_t v)
{
    p[0] = (unsigned char)(v >> 24); p[1] = (unsigned char)(v >> 16);
    p[2] = (unsigned char)(v >> 8); p[3] = (unsigned char)v;
}

static void put64(unsigned char *p, uint64_t v)
{
    put32(p, (uint32_t)(v >> 32)); put32(p + 4, (uint32_t)v);
}

static int64_t broker_now(void)
{
    struct timespec t;
    if (clock_gettime(CLOCK_BOOTTIME, &t) < 0) return -1;
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

static int broker_deadline(int64_t deadline)
{
    int64_t now = broker_now();
    if (broker_interrupted || now < 0 || now >= deadline) { errno = ETIMEDOUT; return -1; }
    return 0;
}

static int broker_poll(int fd, short events, int64_t deadline)
{
    for (;;) {
        int64_t now = broker_now();
        if (broker_interrupted || now < 0 || now >= deadline) { errno = ETIMEDOUT; return -1; }
        int64_t left = deadline - now;
        struct pollfd p = { .fd = fd, .events = events };
        int rc = poll(&p, 1, left > 100 ? 100 : (int)left);
        if (rc < 0 && errno == EINTR) continue;
        if (rc < 0) return -1;
        if (!rc) continue;
        if (broker_deadline(deadline) < 0) return -1;
        if (p.revents & events) return 0;
        if (p.revents & (POLLHUP | POLLERR | POLLNVAL)) { errno = EPIPE; return -1; }
    }
}

static void close_fds(int *fds, size_t count)
{
    for (size_t i = 0; i < count; ++i) {
        if (fds[i] >= 0) (void)close(fds[i]);
        fds[i] = -1;
    }
}

static int close_from(unsigned int first)
{
#ifdef SYS_close_range
    if (syscall(SYS_close_range, first, UINT_MAX, 0) == 0) return 0;
    if (errno != ENOSYS && errno != EINVAL) return -1;
#endif
    DIR *directory = opendir("/proc/self/fd");
    if (!directory) return -1;
    int own = dirfd(directory), result = 0;
    struct dirent *entry;
    errno = 0;
    while ((entry = readdir(directory)) != NULL) {
        char *end = NULL;
        long number = strtol(entry->d_name, &end, 10);
        if (end == entry->d_name || *end || number < (long)first || number > INT_MAX || number == own) continue;
        if (close((int)number) < 0 && errno != EBADF) { result = -1; break; }
        errno = 0;
    }
    if (errno) result = -1;
    int saved = errno;
    if (closedir(directory) < 0) result = -1;
    if (result < 0) errno = saved ? saved : EIO;
    return result;
}

static bool decimal_value(const char *text, uint64_t maximum, uint64_t *value)
{
    if (!text || !*text || (text[0] == '0' && text[1])) return false;
    uint64_t v = 0;
    for (const unsigned char *p = (const unsigned char *)text; *p; ++p) {
        if (*p < '0' || *p > '9' || v > (maximum - (uint64_t)(*p - '0')) / 10) return false;
        v = v * 10 + (uint64_t)(*p - '0');
    }
    *value = v;
    return v != 0;
}

static int lower_hex(unsigned char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

static bool socket_name_valid(const char *name)
{
    size_t length = strnlen(name, AJIU_BROKER_MAX_SOCKET_NAME + 1U);
    if (!length || length > AJIU_BROKER_MAX_SOCKET_NAME) return false;
    for (size_t i = 0; i < length; ++i) {
        unsigned char c = (unsigned char)name[i];
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
              c == '_' || c == '-' || c == '.')) return false;
    }
    return true;
}

static int parse_arguments(int argc, char **argv, struct broker_arguments *a)
{
    uint64_t uid, pid, start;
    if (argc != 7 || strcmp(argv[1], "--broker") || !socket_name_valid(argv[2]) ||
        strnlen(argv[3], 65) != 64 || !decimal_value(argv[4], INT_MAX, &uid) || uid < 10000 ||
        !decimal_value(argv[5], INT_MAX, &pid) || pid <= 1 || !decimal_value(argv[6], INT64_MAX, &start)) {
        errno = EINVAL; return -1;
    }
    memset(a, 0, sizeof(*a));
    for (size_t i = 0; i < AJIU_BROKER_NONCE_SIZE; ++i) {
        int high = lower_hex((unsigned char)argv[3][i * 2]), low = lower_hex((unsigned char)argv[3][i * 2 + 1]);
        if (high < 0 || low < 0) { errno = EINVAL; return -1; }
        a->nonce[i] = (unsigned char)(high * 16 + low);
    }
    memcpy(a->name, argv[2], strlen(argv[2]) + 1);
    a->uid = (uint32_t)uid; a->pid = (uint32_t)pid; a->start = start;
    return 0;
}

static int encode_header(unsigned char *out, uint16_t type, uint32_t length)
{
    if (type < AJIU_BROKER_HELLO || type > AJIU_BROKER_BYE || length > AJIU_BROKER_MAX_PAYLOAD) {
        errno = EPROTO; return -1;
    }
    put32(out, AJIU_BROKER_MAGIC); put16(out + 4, AJIU_BROKER_VERSION);
    put16(out + 6, type); put32(out + 8, length);
    return 0;
}

static int decode_header(const unsigned char *in, struct ajiu_broker_header *h)
{
    h->type = get16(in + 6); h->length = get32(in + 8);
    if (get32(in) != AJIU_BROKER_MAGIC || get16(in + 4) != AJIU_BROKER_VERSION ||
        h->type < AJIU_BROKER_HELLO || h->type > AJIU_BROKER_BYE || h->length > AJIU_BROKER_MAX_PAYLOAD) {
        errno = EPROTO; return -1;
    }
    return 0;
}

static int send_frame(int socket_fd, uint16_t type, const unsigned char *body, uint32_t length,
                      const int *fds, size_t count, int64_t deadline)
{
    unsigned char bytes[AJIU_BROKER_HEADER_SIZE + AJIU_BROKER_MAX_PAYLOAD];
    if (count > AJIU_BROKER_FD_COUNT || encode_header(bytes, type, length) < 0) { errno = EINVAL; return -1; }
    if (length) memcpy(bytes + AJIU_BROKER_HEADER_SIZE, body, length);
    size_t offset = 0, total = AJIU_BROKER_HEADER_SIZE + length;
    while (offset < total) {
        if (broker_deadline(deadline) < 0) return -1;
        struct iovec iov = { .iov_base = bytes + offset, .iov_len = total - offset };
        union { struct cmsghdr alignment; unsigned char bytes[CMSG_SPACE(sizeof(int) * AJIU_BROKER_FD_COUNT)]; } control;
        struct msghdr message = { .msg_iov = &iov, .msg_iovlen = 1 };
        if (!offset && count) {
            memset(&control, 0, sizeof(control));
            message.msg_control = control.bytes; message.msg_controllen = CMSG_SPACE(sizeof(int) * count);
            struct cmsghdr *c = CMSG_FIRSTHDR(&message);
            c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(int) * count);
            memcpy(CMSG_DATA(c), fds, sizeof(int) * count);
        }
        ssize_t sent = sendmsg(socket_fd, &message, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (sent < 0 && errno == EINTR) continue;
        if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (broker_poll(socket_fd, POLLOUT, deadline) < 0) return -1;
            continue;
        }
        if (sent <= 0) { if (!sent) errno = EPIPE; return -1; }
        offset += (size_t)sent;
    }
    return 0;
}

static ssize_t receive_piece(int socket_fd, unsigned char *bytes, size_t length, int *fds,
                             size_t expected, int64_t deadline)
{
    for (;;) {
        if (broker_deadline(deadline) < 0) return -1;
        struct iovec iov = { .iov_base = bytes, .iov_len = length };
        union { struct cmsghdr alignment; unsigned char bytes[CMSG_SPACE(sizeof(int) * 16U)]; } control;
        memset(&control, 0, sizeof(control));
        struct msghdr message = { .msg_iov = &iov, .msg_iovlen = 1,
            .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes) };
        ssize_t got = recvmsg(socket_fd, &message, MSG_DONTWAIT | MSG_CMSG_CLOEXEC);
        if (got < 0 && errno == EINTR) continue;
        if (got < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (broker_poll(socket_fd, POLLIN, deadline) < 0) return -1;
            continue;
        }
        if (got < 0) return -1;
        size_t count = 0;
        bool invalid = (message.msg_flags & (MSG_CTRUNC | MSG_TRUNC)) != 0;
        for (struct cmsghdr *c = CMSG_FIRSTHDR(&message); c; c = CMSG_NXTHDR(&message, c)) {
            if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS || c->cmsg_len < CMSG_LEN(0)) {
                invalid = true; continue;
            }
            size_t size = c->cmsg_len - CMSG_LEN(0);
            if (size % sizeof(int)) invalid = true;
            for (size_t i = 0; i < size / sizeof(int); ++i) {
                int fd;
                memcpy(&fd, (unsigned char *)CMSG_DATA(c) + i * sizeof(int), sizeof(fd));
                if (count < expected && !invalid) {
                    if (fcntl(fd, F_SETFD, FD_CLOEXEC) < 0) { (void)close(fd); invalid = true; }
                    else fds[count] = fd;
                } else (void)close(fd);
                ++count;
            }
        }
        if (invalid || count != expected || !got) {
            if (fds) close_fds(fds, expected);
            errno = !got && !count && !expected ? EPIPE : EPROTO;
            return -1;
        }
        return got;
    }
}

static int receive_exact(int socket_fd, unsigned char *out, size_t length, int64_t deadline)
{
    size_t offset = 0;
    while (offset < length) {
        ssize_t n = receive_piece(socket_fd, out + offset, length - offset, NULL, 0, deadline);
        if (n <= 0) return -1;
        offset += (size_t)n;
    }
    return 0;
}

static int receive_frame(int socket_fd, struct ajiu_broker_header *h, unsigned char *body,
                         int *fds, size_t expected, int64_t deadline)
{
    if (expected > AJIU_BROKER_FD_COUNT || (expected && !fds)) { errno = EINVAL; return -1; }
    for (size_t i = 0; i < expected; ++i) fds[i] = -1;
    unsigned char wire[AJIU_BROKER_HEADER_SIZE];
    if (receive_piece(socket_fd, wire, 1, fds, expected, deadline) < 0) return -1;
    if (receive_exact(socket_fd, wire + 1, sizeof(wire) - 1, deadline) < 0 || decode_header(wire, h) < 0 ||
        receive_exact(socket_fd, body, h->length, deadline) < 0) {
        close_fds(fds, expected); return -1;
    }
    return 0;
}

static bool zero_bytes(const unsigned char *bytes, size_t length)
{
    unsigned char all = 0;
    for (size_t i = 0; i < length; ++i) all |= bytes[i];
    return all == 0;
}

static int parse_request(const struct ajiu_broker_header *h, const unsigned char *b,
                         uint64_t *next, struct ajiu_broker_request *r)
{
    memset(r, 0, sizeof(*r));
    uint32_t expected = h->type == AJIU_BROKER_START ? AJIU_BROKER_START_SIZE :
        h->type == AJIU_BROKER_STOP ? AJIU_BROKER_STOP_SIZE :
        h->type == AJIU_BROKER_SHUTDOWN ? AJIU_BROKER_SHUTDOWN_SIZE : 0;
    if (!expected || h->length != expected) { errno = EPROTO; return -1; }
    r->id = get64(b);
    if (!r->id || r->id > (uint64_t)AJIU_BROKER_MAX_REQUEST_ID || r->id != *next) { errno = EPROTO; return -1; }
    if (h->type == AJIU_BROKER_START) {
        r->operation = get32(b + 8); r->device = get64(b + 40); r->inode = get64(b + 48);
        if (get32(b + 12) || r->operation < AJIU_BROKER_RUNTIME || r->operation > AJIU_BROKER_REMOVE) {
            errno = EPROTO; return -1;
        }
        if (r->operation == AJIU_BROKER_RUNTIME) {
            if (!zero_bytes(b + 16, 40)) { errno = EPROTO; return -1; }
        } else {
            for (size_t i = 0; i < AJIU_BROKER_INSTANCE_SIZE; ++i) {
                if (lower_hex(b[16 + i]) < 0) { errno = EPROTO; return -1; }
                r->instance[i] = (char)b[16 + i];
            }
            if ((r->operation == AJIU_BROKER_INSTALL && (r->device || r->inode)) ||
                (r->operation == AJIU_BROKER_REMOVE && (!r->device || !r->inode))) { errno = EPROTO; return -1; }
        }
    } else if (h->type == AJIU_BROKER_STOP) {
        r->target = get64(b + 8);
        if (!r->target || r->target >= r->id) { errno = EPROTO; return -1; }
    }
    *next = r->id + 1;
    return 0;
}

static int pidfd_open_bound(pid_t pid)
{
#ifdef SYS_pidfd_open
    return (int)syscall(SYS_pidfd_open, pid, 0);
#else
    (void)pid; errno = ENOSYS; return -1;
#endif
}

static bool pidfd_alive(int fd)
{
    struct pollfd p = { .fd = fd, .events = POLLIN };
    return fd >= 0 && poll(&p, 1, 0) == 0;
}

static int process_start(uint32_t pid, uint64_t *start)
{
    char path[64], bytes[4096];
    int written = snprintf(path, sizeof(path), "/proc/%" PRIu32 "/stat", pid);
    if (written < 0 || (size_t)written >= sizeof(path)) { errno = EINVAL; return -1; }
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -1;
    ssize_t n;
    do { n = read(fd, bytes, sizeof(bytes) - 1); } while (n < 0 && errno == EINTR);
    int saved = errno;
    (void)close(fd);
    if (n <= 0 || n == (ssize_t)sizeof(bytes) - 1) { errno = n < 0 ? saved : EPROTO; return -1; }
    bytes[n] = '\0';
    char *p = strrchr(bytes, ')');
    if (!p || p[1] != ' ') { errno = EPROTO; return -1; }
    p += 2;
    for (unsigned int field = 3; field < 22; ++field) {
        p = strchr(p, ' ');
        if (!p) { errno = EPROTO; return -1; }
        while (*p == ' ') ++p;
    }
    char *end = strchr(p, ' ');
    if (!end) { errno = EPROTO; return -1; }
    *end = '\0';
    if (!decimal_value(p, INT64_MAX, start)) { errno = EPROTO; return -1; }
    return 0;
}

static bool peer_matches(const struct ucred *peer, const struct broker_arguments *a)
{
    return peer->pid == (pid_t)a->pid && peer->uid == (uid_t)a->uid && peer->gid == (gid_t)a->uid;
}

static int validate_app(int socket_fd, const struct broker_arguments *a, int app_pidfd)
{
    struct ucred peer;
    socklen_t length = sizeof(peer);
    uint64_t start;
    if (getsockopt(socket_fd, SOL_SOCKET, SO_PEERCRED, &peer, &length) < 0 || length != sizeof(peer) ||
        !peer_matches(&peer, a) || !pidfd_alive(app_pidfd) || process_start(a->pid, &start) < 0 ||
        start != a->start || !pidfd_alive(app_pidfd)) { errno = EACCES; return -1; }
    return 0;
}

static int connect_app(const struct broker_arguments *a, int app_pidfd)
{
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    size_t length = strlen(a->name);
    memcpy(address.sun_path + 1, a->name, length);
    int rc = connect(fd, (struct sockaddr *)&address, (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + length));
    if (rc < 0 && errno == EINPROGRESS) {
        rc = broker_poll(fd, POLLOUT, broker_now() + AJIU_BROKER_IO_TIMEOUT_MS);
        if (rc == 0) {
            int error = 0;
            socklen_t size = sizeof(error);
            rc = getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size);
            if (!rc && error) { errno = error; rc = -1; }
        }
    }
    if (rc < 0 || validate_app(fd, a, app_pidfd) < 0) {
        int saved = errno; (void)close(fd); errno = saved; return -1;
    }
    return fd;
}

static void hello_body(unsigned char *bytes, const struct broker_arguments *a)
{
    memcpy(bytes, a->nonce, AJIU_BROKER_NONCE_SIZE);
    put32(bytes + 32, a->uid); put32(bytes + 36, a->pid); put64(bytes + 40, a->start);
    put32(bytes + 48, (uint32_t)getpid()); put32(bytes + 52, 0);
}

static int handshake(int fd, const struct broker_arguments *a, int app_pidfd)
{
    unsigned char body[AJIU_BROKER_HELLO_SIZE], reply[AJIU_BROKER_MAX_PAYLOAD];
    struct ajiu_broker_header h;
    int64_t deadline = broker_now() + AJIU_BROKER_IO_TIMEOUT_MS;
    hello_body(body, a);
    if (send_frame(fd, AJIU_BROKER_HELLO, body, sizeof(body), NULL, 0, deadline) < 0 ||
        receive_frame(fd, &h, reply, NULL, 0, deadline) < 0 ||
        h.type != AJIU_BROKER_HELLO_ACK || h.length != sizeof(body)) { errno = EACCES; return -1; }
    unsigned char difference = 0;
    for (size_t i = 0; i < sizeof(body); ++i) difference |= body[i] ^ reply[i];
    if (difference || validate_app(fd, a, app_pidfd) < 0) { errno = EACCES; return -1; }
    return 0;
}

static bool trusted_stat(const struct stat *s, bool directory)
{
    return (directory ? S_ISDIR(s->st_mode) : S_ISREG(s->st_mode)) &&
        (s->st_uid == 0 || s->st_uid == 1000) && !(s->st_mode & S_IWOTH) &&
        (!(s->st_mode & S_IWGRP) || (directory && (s->st_gid == 0 || s->st_gid == 1000)));
}

static bool same_identity(const struct stat *a, const struct stat *b)
{
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino && a->st_size == b->st_size &&
        a->st_uid == b->st_uid && a->st_gid == b->st_gid && a->st_mode == b->st_mode &&
        a->st_mtim.tv_sec == b->st_mtim.tv_sec && a->st_mtim.tv_nsec == b->st_mtim.tv_nsec &&
        a->st_ctim.tv_sec == b->st_ctim.tv_sec && a->st_ctim.tv_nsec == b->st_ctim.tv_nsec;
}

static int static_elf(int fd, const struct stat *identity)
{
    Elf64_Ehdr e;
    if (identity->st_size < (off_t)sizeof(e) || identity->st_size > 64 * 1024 * 1024 ||
        pread(fd, &e, sizeof(e), 0) != (ssize_t)sizeof(e) || memcmp(e.e_ident, ELFMAG, SELFMAG) ||
        e.e_ident[EI_CLASS] != ELFCLASS64 || e.e_ident[EI_DATA] != ELFDATA2LSB ||
        e.e_ident[EI_VERSION] != EV_CURRENT || e.e_version != EV_CURRENT || e.e_type != ET_DYN ||
        e.e_machine != EM_AARCH64 || e.e_ehsize != sizeof(e) || e.e_phentsize != sizeof(Elf64_Phdr) ||
        !e.e_phnum || e.e_phnum > 128 || e.e_phoff < sizeof(e) ||
        e.e_phoff > (uint64_t)identity->st_size ||
        (uint64_t)e.e_phnum * sizeof(Elf64_Phdr) > (uint64_t)identity->st_size - e.e_phoff) {
        errno = ENOEXEC; return -1;
    }
    unsigned int loads = 0, stacks = 0, dynamic = 0;
    bool entry = false, relro = false, pie = false;
    for (uint16_t i = 0; i < e.e_phnum; ++i) {
        Elf64_Phdr p;
        if (pread(fd, &p, sizeof(p), (off_t)(e.e_phoff + (uint64_t)i * sizeof(p))) != (ssize_t)sizeof(p) ||
            p.p_offset > (uint64_t)identity->st_size || p.p_filesz > (uint64_t)identity->st_size - p.p_offset ||
            p.p_type == PT_INTERP) { errno = ENOEXEC; return -1; }
        if (p.p_type == PT_LOAD) {
            if (p.p_memsz < p.p_filesz || p.p_align < 16384 || (p.p_align & (p.p_align - 1)) ||
                p.p_offset % p.p_align != p.p_vaddr % p.p_align ||
                (p.p_flags & (PF_W | PF_X)) == (PF_W | PF_X)) { errno = ENOEXEC; return -1; }
            ++loads;
            if ((p.p_flags & PF_X) && e.e_entry >= p.p_vaddr && e.e_entry - p.p_vaddr < p.p_memsz) entry = true;
        } else if (p.p_type == PT_GNU_STACK) {
            if (p.p_flags & PF_X) { errno = ENOEXEC; return -1; }
            ++stacks;
        } else if (p.p_type == PT_GNU_RELRO) relro = true;
        else if (p.p_type == PT_DYNAMIC) {
            if (++dynamic != 1 || p.p_filesz % sizeof(Elf64_Dyn)) { errno = ENOEXEC; return -1; }
            bool terminated = false;
            for (uint64_t o = 0; o < p.p_filesz; o += sizeof(Elf64_Dyn)) {
                Elf64_Dyn d;
                if (pread(fd, &d, sizeof(d), (off_t)(p.p_offset + o)) != (ssize_t)sizeof(d) || d.d_tag == DT_NEEDED) {
                    errno = ENOEXEC; return -1;
                }
                if (d.d_tag == DT_NULL) { terminated = true; break; }
                if (d.d_tag == DT_FLAGS_1 && (d.d_un.d_val & DF_1_PIE)) pie = true;
            }
            if (!terminated) { errno = ENOEXEC; return -1; }
        }
    }
    if (!loads || stacks != 1 || dynamic != 1 || !entry || !relro || !pie) { errno = ENOEXEC; return -1; }
    return 0;
}

static int pin_executable(int directory, const char *name, struct broker_executable *executable)
{
    int fd = openat(directory, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) return -1;
    struct stat s;
    if (fstat(fd, &s) < 0 || !trusted_stat(&s, false) || !(s.st_mode & S_IXUSR) || static_elf(fd, &s) < 0) {
        int saved = errno ? errno : EACCES; (void)close(fd); errno = saved; return -1;
    }
    executable->fd = fd; executable->identity = s;
    return 0;
}

static int apk_directory(void)
{
    char path[PATH_MAX];
    ssize_t size = readlink("/proc/self/exe", path, sizeof(path) - 1);
    if (size <= 0 || size >= (ssize_t)sizeof(path) - 1) { errno = EACCES; return -1; }
    path[size] = '\0';
    if (strncmp(path, "/data/app/", 10)) { errno = EACCES; return -1; }
    char *last = strrchr(path, '/');
    if (!last || strcmp(last + 1, AJIU_BROKER_NAME)) { errno = EACCES; return -1; }
    *last = '\0';
    int directory = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (directory < 0) return -1;
    struct stat s;
    if (fstat(directory, &s) < 0 || !trusted_stat(&s, true)) { (void)close(directory); errno = EACCES; return -1; }
    char *component = path + 1;
    while (*component) {
        char *slash = strchr(component, '/');
        if (slash) *slash = '\0';
        if (!*component || !strcmp(component, ".") || !strcmp(component, "..")) {
            (void)close(directory); errno = EACCES; return -1;
        }
        int next = openat(directory, component, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        (void)close(directory);
        if (next < 0) return -1;
        directory = next;
        if (fstat(directory, &s) < 0 || !trusted_stat(&s, true)) { (void)close(directory); errno = EACCES; return -1; }
        if (!slash) break;
        component = slash + 1;
    }
    struct broker_executable self = { .fd = -1 };
    struct stat running;
    if (pin_executable(directory, AJIU_BROKER_NAME, &self) < 0 || stat("/proc/self/exe", &running) < 0 ||
        !same_identity(&self.identity, &running)) {
        if (self.fd >= 0) (void)close(self.fd);
        (void)close(directory); errno = EACCES; return -1;
    }
    (void)close(self.fd);
    return directory;
}

static bool executable_current(const struct broker_executable *e)
{
    struct stat s;
    return e->fd >= 0 && fstat(e->fd, &s) == 0 && trusted_stat(&s, false) && same_identity(&e->identity, &s);
}

static int signal_job(struct broker_job *job)
{
    if (job->stopped) return 0;
    int rc;
#ifdef SYS_pidfd_send_signal
    rc = job->pidfd >= 0 ? (int)syscall(SYS_pidfd_send_signal, job->pidfd, SIGTERM, NULL, 0) : kill(job->pid, SIGTERM);
#else
    rc = kill(job->pid, SIGTERM);
#endif
    if (!rc || errno == ESRCH) { job->stopped = true; return 0; }
    return -1;
}

static void write_exit(struct broker_job *job, int status, bool known)
{
    unsigned char bytes[AJIU_BROKER_EXIT_SIZE] = { 0 };
    uint16_t outcome = AJIU_BROKER_UNKNOWN;
    uint32_t value = 0;
    if (known && WIFEXITED(status)) { outcome = AJIU_BROKER_EXITED; value = (uint32_t)WEXITSTATUS(status); }
    else if (known && WIFSIGNALED(status)) { outcome = AJIU_BROKER_SIGNAL; value = (uint32_t)WTERMSIG(status); }
    put32(bytes, AJIU_BROKER_EXIT_MAGIC); put16(bytes + 4, AJIU_BROKER_VERSION);
    put16(bytes + 6, outcome); put32(bytes + 8, value);
    ssize_t n;
    do { n = write(job->status, bytes, sizeof(bytes)); } while (n < 0 && errno == EINTR);
    (void)n;
    (void)close(job->status);
    if (job->pidfd >= 0) (void)close(job->pidfd);
    *job = (struct broker_job){ .pidfd = -1, .status = -1 };
}

static size_t reap_jobs(struct broker_job *jobs)
{
    size_t alive = 0;
    for (size_t i = 0; i < AJIU_BROKER_MAX_JOBS; ++i) {
        if (!jobs[i].id) continue;
        int status = 0;
        pid_t result;
        do { result = waitpid(jobs[i].pid, &status, WNOHANG); } while (result < 0 && errno == EINTR);
        if (result == jobs[i].pid) write_exit(jobs + i, status, true);
        else if (result < 0) write_exit(jobs + i, 0, false);
        else ++alive;
    }
    return alive;
}

static void child_exec(const struct broker_executable *e, const struct ajiu_broker_request *r, int pipes[4][2])
{
    (void)signal(SIGTERM, SIG_DFL); (void)signal(SIGINT, SIG_DFL);
    (void)signal(SIGHUP, SIG_DFL); (void)signal(SIGPIPE, SIG_DFL);
    if (dup2(pipes[0][0], STDIN_FILENO) < 0 || dup2(pipes[1][1], STDOUT_FILENO) < 0 ||
        dup2(pipes[2][1], STDERR_FILENO) < 0) _exit(126);
    int executable = e->fd;
    if (executable != 3) {
        if (dup3(executable, 3, O_CLOEXEC) < 0) _exit(126);
        executable = 3;
    } else if (fcntl(executable, F_SETFD, FD_CLOEXEC) < 0) _exit(126);
    if (close_from(4) < 0) _exit(126);
    char device[32], inode[32];
    (void)snprintf(device, sizeof(device), "%" PRIu64, r->device);
    (void)snprintf(inode, sizeof(inode), "%" PRIu64, r->inode);
    char *runtime[] = { (char *)AJIU_BROKER_RUNTIME_NAME, (char *)"--runtime", NULL };
    char *install[] = { (char *)AJIU_BROKER_INSTALLER_NAME, (char *)"--install", (char *)r->instance, NULL };
    char *remove[] = { (char *)AJIU_BROKER_INSTALLER_NAME, (char *)"--remove", (char *)r->instance, device, inode, NULL };
    char *environment[] = { (char *)"PATH=/system/bin", (char *)"LANG=C", NULL };
    char **argv = r->operation == AJIU_BROKER_RUNTIME ? runtime : r->operation == AJIU_BROKER_INSTALL ? install : remove;
#ifdef SYS_execveat
    (void)syscall(SYS_execveat, executable, "", argv, environment, AT_EMPTY_PATH);
#else
    (void)executable; (void)argv; (void)environment;
#endif
    _exit(127);
}

static int send_failure(int fd, uint64_t id, uint32_t code, int error)
{
    unsigned char bytes[AJIU_BROKER_FAILED_SIZE];
    put64(bytes, id); put32(bytes + 8, code); put32(bytes + 12, (uint32_t)(error > 0 ? error : EIO));
    return send_frame(fd, AJIU_BROKER_FAILED, bytes, sizeof(bytes), NULL, 0, broker_now() + AJIU_BROKER_IO_TIMEOUT_MS);
}

static int spawn_job(int fd, const struct ajiu_broker_request *r, struct broker_job *jobs,
                     const struct broker_executable *runtime, const struct broker_executable *installer)
{
    struct broker_job *job = NULL;
    for (size_t i = 0; i < AJIU_BROKER_MAX_JOBS; ++i) if (!jobs[i].id) { job = jobs + i; break; }
    if (!job) return send_failure(fd, r->id, AJIU_BROKER_FAIL_CAPACITY, EBUSY);
    const struct broker_executable *e = r->operation == AJIU_BROKER_RUNTIME ? runtime : installer;
    if (!executable_current(e)) return send_failure(fd, r->id, AJIU_BROKER_FAIL_IDENTITY, ESTALE);
    int pipes[4][2];
    for (size_t i = 0; i < 4; ++i) pipes[i][0] = pipes[i][1] = -1;
    for (size_t i = 0; i < 4; ++i) {
        if (pipe2(pipes[i], O_CLOEXEC) < 0 ||
            (i == AJIU_BROKER_STATUS && fcntl(pipes[i][1], F_SETFL, O_NONBLOCK) < 0)) {
            int saved = errno;
            for (size_t j = 0; j < 4; ++j) close_fds(pipes[j], 2);
            return send_failure(fd, r->id, AJIU_BROKER_FAIL_PIPE, saved);
        }
    }
    pid_t child = fork();
    if (!child) child_exec(e, r, pipes);
    if (child < 0) {
        int saved = errno;
        for (size_t j = 0; j < 4; ++j) close_fds(pipes[j], 2);
        return send_failure(fd, r->id, AJIU_BROKER_FAIL_FORK, saved);
    }
    *job = (struct broker_job){ .id = r->id, .pid = child, .pidfd = pidfd_open_bound(child), .status = pipes[3][1] };
    pipes[3][1] = -1;
    int offered[AJIU_BROKER_FD_COUNT] = { pipes[0][1], pipes[1][0], pipes[2][0], pipes[3][0] };
    (void)close(pipes[0][0]); (void)close(pipes[1][1]); (void)close(pipes[2][1]);
    unsigned char bytes[AJIU_BROKER_STARTED_SIZE] = { 0 };
    put64(bytes, r->id); put32(bytes + 8, (uint32_t)child);
    int rc = job->pidfd < 0 ? -1 : send_frame(fd, AJIU_BROKER_STARTED, bytes, sizeof(bytes), offered,
        AJIU_BROKER_FD_COUNT, broker_now() + AJIU_BROKER_IO_TIMEOUT_MS);
    int saved = errno;
    close_fds(offered, AJIU_BROKER_FD_COUNT);
    if (rc < 0) (void)signal_job(job);
    errno = saved;
    return rc;
}

static int serve_request(int fd, const struct ajiu_broker_header *h, const struct ajiu_broker_request *r,
                         struct broker_job *jobs, const struct broker_executable *runtime,
                         const struct broker_executable *installer)
{
    if (h->type == AJIU_BROKER_START) return spawn_job(fd, r, jobs, runtime, installer);
    if (h->type == AJIU_BROKER_STOP) {
        struct broker_job *job = NULL;
        for (size_t i = 0; i < AJIU_BROKER_MAX_JOBS; ++i) if (jobs[i].id == r->target) { job = jobs + i; break; }
        if (!job) return send_failure(fd, r->id, AJIU_BROKER_FAIL_TARGET, ENOENT);
        if (signal_job(job) < 0) return -1;
        unsigned char body[AJIU_BROKER_STOP_SIZE];
        put64(body, r->id); put64(body + 8, r->target);
        return send_frame(fd, AJIU_BROKER_STOPPED, body, sizeof(body), NULL, 0, broker_now() + AJIU_BROKER_IO_TIMEOUT_MS);
    }
    unsigned char body[AJIU_BROKER_SHUTDOWN_SIZE];
    put64(body, r->id);
    if (send_frame(fd, AJIU_BROKER_BYE, body, sizeof(body), NULL, 0, broker_now() + AJIU_BROKER_IO_TIMEOUT_MS) < 0) return -1;
    return 1;
}

static void stop_jobs(struct broker_job *jobs)
{
    for (size_t i = 0; i < AJIU_BROKER_MAX_JOBS; ++i) if (jobs[i].id) (void)signal_job(jobs + i);
    int64_t end = broker_now() + AJIU_BROKER_SHUTDOWN_TIMEOUT_MS;
    while (reap_jobs(jobs)) {
        int64_t now = broker_now();
        if (now < 0 || now >= end) break;
        struct pollfd descriptors[AJIU_BROKER_MAX_JOBS];
        nfds_t count = 0;
        for (size_t i = 0; i < AJIU_BROKER_MAX_JOBS; ++i)
            if (jobs[i].id && jobs[i].pidfd >= 0) descriptors[count++] = (struct pollfd){ .fd = jobs[i].pidfd, .events = POLLIN };
        int wait = end - now > 100 ? 100 : (int)(end - now);
        (void)poll(descriptors, count, wait);
    }
    for (size_t i = 0; i < AJIU_BROKER_MAX_JOBS; ++i) if (jobs[i].id) {
        if (jobs[i].status >= 0) (void)close(jobs[i].status);
        if (jobs[i].pidfd >= 0) (void)close(jobs[i].pidfd);
        jobs[i] = (struct broker_job){ .pidfd = -1, .status = -1 };
    }
}

static int broker_loop(int fd, int app_pidfd, const struct broker_arguments *a,
                       const struct broker_executable *runtime, const struct broker_executable *installer)
{
    struct broker_job jobs[AJIU_BROKER_MAX_JOBS];
    for (size_t i = 0; i < AJIU_BROKER_MAX_JOBS; ++i) jobs[i] = (struct broker_job){ .pidfd = -1, .status = -1 };
    uint64_t next = 1;
    int64_t end = broker_now() + AJIU_BROKER_LIFETIME_MS;
    int result = -1;
    for (;;) {
        int64_t now = broker_now();
        if (broker_interrupted || now < 0 || now >= end || !pidfd_alive(app_pidfd)) break;
        (void)reap_jobs(jobs);
        struct pollfd p[AJIU_BROKER_MAX_JOBS + 2U] = {
            { .fd = fd, .events = POLLIN }, { .fd = app_pidfd, .events = POLLIN }
        };
        nfds_t count = 2;
        for (size_t i = 0; i < AJIU_BROKER_MAX_JOBS; ++i)
            if (jobs[i].id && jobs[i].pidfd >= 0) p[count++] = (struct pollfd){ .fd = jobs[i].pidfd, .events = POLLIN };
        int ready = poll(p, count, 100);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0 || p[1].revents) break;
        if (!(p[0].revents & POLLIN)) {
            if (p[0].revents & (POLLHUP | POLLERR | POLLNVAL)) break;
            continue;
        }
        unsigned char bytes[AJIU_BROKER_MAX_PAYLOAD];
        struct ajiu_broker_header h;
        struct ajiu_broker_request r;
        if (receive_frame(fd, &h, bytes, NULL, 0, broker_now() + AJIU_BROKER_IO_TIMEOUT_MS) < 0 ||
            parse_request(&h, bytes, &next, &r) < 0 || validate_app(fd, a, app_pidfd) < 0) break;
        now = broker_now();
        if (broker_interrupted || now < 0 || now >= end) break;
        int rc = serve_request(fd, &h, &r, jobs, runtime, installer);
        if (rc) { if (rc > 0) result = 0; break; }
    }
    (void)shutdown(fd, SHUT_RDWR);
    stop_jobs(jobs);
    return result;
}

static void on_broker_signal(int signal_number)
{
    (void)signal_number;
    broker_interrupted = 1;
}

int main(int argc, char **argv)
{
    struct broker_arguments arguments;
    if (parse_arguments(argc, argv, &arguments) < 0 || getuid() != 0 || geteuid() != 0 || getgid() != 0 || getegid() != 0) return 2;
    if (close_from(3) < 0) return 3;
    int null_fd = open("/dev/null", O_RDWR | O_CLOEXEC | O_NOFOLLOW);
    if (null_fd < 0) return 3;
    for (int i = 0; i < 3; ++i) if (dup2(null_fd, i) < 0) { (void)close(null_fd); return 3; }
    if (null_fd > 2) (void)close(null_fd);
    struct sigaction action = { .sa_handler = on_broker_signal };
    sigemptyset(&action.sa_mask);
    if (sigprocmask(SIG_SETMASK, &action.sa_mask, NULL) < 0 ||
        sigaction(SIGTERM, &action, NULL) < 0 || sigaction(SIGINT, &action, NULL) < 0 ||
        sigaction(SIGHUP, &action, NULL) < 0 || signal(SIGCHLD, SIG_DFL) == SIG_ERR ||
        signal(SIGPIPE, SIG_IGN) == SIG_ERR) return 3;
    (void)umask(0077);
    struct broker_executable runtime = { .fd = -1 }, installer = { .fd = -1 };
    int directory = apk_directory(), app_pidfd = -1, fd = -1, result = 4;
    if (directory < 0) goto done;
    if (pin_executable(directory, AJIU_BROKER_RUNTIME_NAME, &runtime) < 0 ||
        pin_executable(directory, AJIU_BROKER_INSTALLER_NAME, &installer) < 0) goto done;
    app_pidfd = pidfd_open_bound((pid_t)arguments.pid);
    if (app_pidfd < 0 || !pidfd_alive(app_pidfd)) goto done;
    fd = connect_app(&arguments, app_pidfd);
    if (fd < 0 || handshake(fd, &arguments, app_pidfd) < 0) goto done;
    result = broker_loop(fd, app_pidfd, &arguments, &runtime, &installer) == 0 ? 0 : 5;
done:
    if (fd >= 0) (void)close(fd);
    if (app_pidfd >= 0) (void)close(app_pidfd);
    if (runtime.fd >= 0) (void)close(runtime.fd);
    if (installer.fd >= 0) (void)close(installer.fd);
    if (directory >= 0) (void)close(directory);
    return result;
}
