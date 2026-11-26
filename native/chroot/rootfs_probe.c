#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <limits.h>
#include <linux/capability.h>
#include <linux/magic.h>
#include <linux/memfd.h>
#include <linux/mount.h>
#include <linux/openat2.h>
#include <linux/securebits.h>
#include <linux/stat.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define DF (O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK)
#define TRY(label, expr) do { stage = (label); if ((expr) < 0) return -1; } while (0)
#define NEED(label, cond) do { stage = (label); if (!(cond)) { errno = EPROTO; return -1; } } while (0)
enum { LOCAL, BASE, ROOT, MANIFEST, VIEW, CGROOT, GROUP, PROCS, KILLCTL, EVENTS, TYPE, ANCHORS };
enum mode { STDIO, NETWORK, PTY };
static const char *const names[ANCHORS] = { NULL, NULL, "root", "manifest", "view", NULL, NULL,
    "cgroup.procs", "cgroup.kill", "cgroup.events", "cgroup.type" };
static const char *stage = "arguments";
static const char *const phases[] = { "child_stdio", "child_gate", "child_namespace", "child_instance",
    "child_mount", "child_chroot", "child_close_fds", "child_drop", "child_readonly", "child_exec",
    "child_private_pty", "child_pty_transfer" };
static const char script[] = "set -eu; /bin/busybox id -u; "
    "printf '%s' ajiu_probe > /tmp/ajiu_probe_sentinel; "
    "test \"$(/bin/busybox cat /tmp/ajiu_probe_sentinel)\" = ajiu_probe; "
    "/bin/busybox rm /tmp/ajiu_probe_sentinel; "
    "if /bin/busybox touch /etc/ajiu_probe_readonly 2>&-; then exit 90; fi; "
    "printf 'guest_shell_ok\\n'; printf 'guest_stderr_ok\\n' >&2; exit 37";
static const char err_marker[] = "guest_stderr_ok\n";
static const char network_script[] = "set -eu; "
    "/bin/busybox nslookup docs.kernel.org 223.5.5.5 > /tmp/ajiu_dns_out 2> /tmp/ajiu_dns_err; "
    "/bin/busybox grep -F 'Name:' /tmp/ajiu_dns_out > /tmp/ajiu_dns_check; "
    "/bin/busybox grep -F 'Address' /tmp/ajiu_dns_out >> /tmp/ajiu_dns_check; "
    "printf 'network_ok\\n'; printf 'network_stderr_ok\\n' >&2; exit 38";
static const char pty_script[] = "set -em; "
    "test \"$(/bin/busybox stty size)\" = '24 80'; printf 'pty_resize_ready\\n'; "
    "IFS= read -r resized; test \"$resized\" = resize; "
    "test \"$(/bin/busybox stty size)\" = '40 100'; printf 'pty_size_ok\\n'; "
    "/bin/busybox sh -c 'trap \"echo pty_interrupt_ok; exit 0\" INT; "
    "printf \"pty_interrupt_ready\\n\"; IFS= read -r line; exit 92'; "
    "printf 'pty_eof_ready\\n'; if IFS= read -r line; then exit 91; fi; "
    "printf 'pty_ok\\n'; exit 39";
struct anchor { int fd; bool valid; struct stat st; uint64_t mount_id; };
struct probe {
    struct anchor a[ANCHORS];
    struct stat ns;
    char base_name[48], group_name[40];
    uint64_t device, inode;
    uid_t uid;
    enum mode mode;
    int cap_last;
    bool view_made, group_made;
};
struct failure { uint32_t magic; int phase, error; };
struct stream { int fd; size_t used; bool eof; unsigned char bytes[512]; };
struct terminal { struct stream stream; int step; bool received; };
struct pty_packet { uint32_t magic, number; };
static int child_error_fd = -1, phase;
static volatile sig_atomic_t expired;
static void deadline_signal(int sig)
{
    (void)sig;
    if (!expired) { expired = 1; (void)alarm(2); return; }
    static const char json[] = "{\"passed\":false,\"stage\":\"hard_deadline\",\"error_code\":\"cleanup_unknown\",\"errno\":110,\"guest_exit\":-1,\"cleanup_state\":\"cleanup_unknown\"}\n";
    ssize_t n = write(STDOUT_FILENO, json, sizeof(json)-1); (void)n;
    _exit(5);
}
static int64_t now_ms(void)
{
    struct timespec t; if (clock_gettime(CLOCK_MONOTONIC, &t) < 0) return -1;
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
static int put(int fd, const void *data, size_t size)
{
    ssize_t n = write(fd, data, size);
    if (n == (ssize_t)size) return 0;
    if (n >= 0) errno = EIO;
    return -1;
}
static bool number(const char *s, uint64_t *out)
{
    uint64_t v = 0; size_t n = strnlen(s, 21);
    if (!n || n > 20 || (n > 1 && *s == '0')) return false;
    for (size_t i = 0; i < n; ++i) {
        if (s[i] < '0' || s[i] > '9' || v > (UINT64_MAX - (unsigned int)(s[i] - '0')) / 10) return false;
        v = v * 10 + (unsigned int)(s[i] - '0');
    }
    *out = v;
    return v != 0;
}
static bool arguments(int argc, char **argv, struct probe *p)
{
    if (argc != 7 || strnlen(argv[2], 25) != 24) return false;
    if (!strcmp(argv[1], "--exercise")) p->mode = STDIO;
    else if (!strcmp(argv[1], "--network")) p->mode = NETWORK;
    else if (!strcmp(argv[1], "--pty")) p->mode = PTY;
    else return false;
    for (int i = 0; i < 24; ++i)
        if (!((argv[2][i] >= '0' && argv[2][i] <= '9') || (argv[2][i] >= 'a' && argv[2][i] <= 'f'))) return false;
    uint64_t uid, gid;
    if (!number(argv[3], &p->device) || !number(argv[4], &p->inode) || !number(argv[5], &uid) ||
        !number(argv[6], &gid) || uid < 10000 || uid > INT_MAX || gid != uid) return false;
    p->uid = (uid_t)uid;
    (void)snprintf(p->base_name, sizeof(p->base_name), "ajiu-chroot-rootfs-%s", argv[2]);
    (void)snprintf(p->group_name, sizeof(p->group_name), "ajiu_chroot_%s", argv[2]);
    return true;
}
static int reset_signals(bool child)
{
    struct sigaction sa = { .sa_handler = SIG_DFL };
    if (sigemptyset(&sa.sa_mask) < 0 || sigprocmask(SIG_SETMASK, &sa.sa_mask, NULL) < 0) return -1;
    for (int s = 1; s < NSIG; ++s) {
        if (s == SIGKILL || s == SIGSTOP || (s >= 32 && s < SIGRTMIN)) continue;
        sa.sa_handler = !child && s == SIGPIPE ? SIG_IGN : SIG_DFL;
        if (sigaction(s, &sa, NULL) < 0) return -1;
    }
    return 0;
}
static bool same(const struct stat *a, const struct stat *b)
{
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino && a->st_mode == b->st_mode &&
           a->st_uid == b->st_uid && a->st_gid == b->st_gid;
}
static int snapshot(struct anchor *a)
{
    struct statx sx = {0};
    TRY("fstat_anchor", fstat(a->fd, &a->st));
    TRY("statx_anchor", syscall(SYS_statx, a->fd, "", AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW, STATX_MNT_ID, &sx));
    NEED("mount_id_available", sx.stx_mask & STATX_MNT_ID);
    a->mount_id = sx.stx_mnt_id; a->valid = true;
    return 0;
}
static int anchored(struct anchor *a, int parent, const char *name, int flags)
{
    TRY("openat_anchor", a->fd = openat(parent, name, flags | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK));
    return snapshot(a);
}
static int current(const struct anchor *a, int parent, const char *name)
{
    struct anchor fd = { .fd = a->fd }, path = { .fd = -1 };
    NEED("known_anchor", a->valid);
    if (snapshot(&fd) < 0 || anchored(&path, parent, name, O_PATH) < 0) {
        int e = errno; if (path.fd >= 0) close(path.fd); errno = e; return -1;
    }
    bool valid = same(&a->st, &fd.st) && same(&a->st, &path.st) &&
                 a->mount_id == fd.mount_id && a->mount_id == path.mount_id;
    TRY("close_identity_path", close(path.fd)); NEED("unchanged_anchor", valid); return 0;
}
static int protected_walk(bool cgroup)
{
    const char *const parts[] = { cgroup ? "sys" : "data", cgroup ? "fs" : "local", "cgroup" };
    int fd = open("/", DF); if (fd < 0) return -1;
    for (int i = 0; i <= (cgroup ? 3 : 2); ++i) {
        struct stat st;
        if (fstat(fd, &st) < 0 || !S_ISDIR(st.st_mode) ||
            (st.st_uid != 0 && st.st_uid != 1000) || (st.st_mode & 0002) ||
            ((st.st_mode & 0020) && st.st_gid != 0 && st.st_gid != 1000)) {
            close(fd); errno = EPERM; return -1;
        }
        if (i == (cgroup ? 3 : 2)) return fd;
        int next = openat(fd, parts[i], DF), e = errno;
        if (close(fd) < 0) { if (next >= 0) close(next); return -1; }
        if (next < 0) { errno = e; return -1; }
        fd = next;
    }
    errno = EPROTO; return -1;
}
static int instance(struct probe *p)
{
    TRY("protected_rootfs_parent", p->a[LOCAL].fd = protected_walk(false));
    if (snapshot(&p->a[LOCAL]) < 0 || anchored(&p->a[BASE], p->a[LOCAL].fd, p->base_name, DF) < 0) return -1;
    const struct stat *b = &p->a[BASE].st;
    NEED("expected_instance", b->st_uid == 0 && b->st_gid == 0 && (b->st_mode & 07777) == 0700 &&
         (uint64_t)b->st_dev == p->device && (uint64_t)b->st_ino == p->inode);
    if (anchored(&p->a[ROOT], p->a[BASE].fd, "root", DF) < 0 ||
        anchored(&p->a[MANIFEST], p->a[BASE].fd, "manifest", O_RDONLY) < 0) return -1;
    const struct stat *r = &p->a[ROOT].st, *m = &p->a[MANIFEST].st;
    NEED("installed_root_manifest", r->st_uid == 0 && r->st_gid == 0 && (r->st_mode & 07777) == 0755 &&
         S_ISREG(m->st_mode) && m->st_uid == 0 && m->st_gid == 0 && m->st_nlink == 1 &&
         (m->st_mode & 07777) == 0400 && m->st_size >= 0 && m->st_size <= 19398656 &&
         p->a[ROOT].mount_id == p->a[BASE].mount_id && p->a[MANIFEST].mount_id == p->a[BASE].mount_id);
    return 0;
}
static int text(int fd, char *buf, size_t size)
{
    ssize_t n = pread(fd, buf, size - 1, 0);
    if (n < 0) return -1;
    if ((size_t)n == size - 1 || memchr(buf, 0, (size_t)n)) { errno = EOVERFLOW; return -1; }
    buf[n] = 0; return 0;
}
static int observer_outside(const struct probe *p)
{
    char buf[4096], target[48];
    int fd = open("/proc/self/cgroup", O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK); if (fd < 0) return -1;
    int rc = text(fd, buf, sizeof(buf)), e = errno;
    if (close(fd) < 0 && rc == 0) return -1;
    if (rc < 0) { errno = e; return -1; }
    (void)snprintf(target, sizeof(target), "0::/%s", p->group_name);
    bool found = false;
    for (char *s = buf; *s;) {
        char *end = strchr(s, '\n');
        NEED("observer_cgroup_format", end != NULL);
        *end = 0;
        if (!strncmp(s, "0::/", 4)) {
            NEED("observer_not_payload", !found && (strncmp(s, target, strlen(target)) ||
                 (s[strlen(target)] != 0 && s[strlen(target)] != '/')));
            found = true;
        }
        s = end + 1;
    }
    NEED("observer_cgroup_v2", found); return 0;
}
static int group_guard(struct probe *p)
{
    struct stat ns;
    TRY("observer_namespace", stat("/proc/self/ns/mnt", &ns));
    NEED("observer_namespace_unchanged", ns.st_dev == p->ns.st_dev && ns.st_ino == p->ns.st_ino);
    int fd = protected_walk(true);
    if (fd < 0) return -1;
    struct anchor root = { .fd = fd };
    int rc = snapshot(&root), e = errno;
    if (close(fd) < 0 && rc == 0) return -1;
    if (rc < 0) { errno = e; return -1; }
    NEED("cgroup_root_unchanged", same(&root.st, &p->a[CGROOT].st) && root.mount_id == p->a[CGROOT].mount_id);
    struct statfs fs;
    TRY("cgroup2_filesystem", fstatfs(p->a[CGROOT].fd, &fs));
    NEED("cgroup2_type", fs.f_type == CGROUP2_SUPER_MAGIC && p->group_made);
    for (int i = GROUP; i <= TYPE; ++i) {
        const char *name = i == GROUP ? p->group_name : names[i];
        if (current(&p->a[i], p->a[i == GROUP ? CGROOT : GROUP].fd, name) < 0) return -1;
        const struct stat *s = &p->a[i].st;
        NEED("protected_group_controls", s->st_uid == 0 && s->st_gid == 0 && !(s->st_mode & 0022) &&
             (i == GROUP ? S_ISDIR(s->st_mode) && (s->st_mode & 07777) == 0700 : S_ISREG(s->st_mode)) &&
             s->st_dev == p->a[CGROOT].st.st_dev && p->a[i].mount_id == p->a[CGROOT].mount_id);
    }
    char type[32];
    TRY("read_cgroup_type", text(p->a[TYPE].fd, type, sizeof(type)));
    NEED("domain_not_threaded", !strcmp(type, "domain\n"));
    return observer_outside(p);
}
static int populated(struct probe *p)
{
    char buf[128]; if (text(p->a[EVENTS].fd, buf, sizeof(buf)) < 0) return -1;
    if (!strcmp(buf, "populated 0\nfrozen 0\n")) return 0;
    if (!strcmp(buf, "populated 1\nfrozen 0\n")) return 1;
    errno = EPROTO; return -1;
}
static int setup(struct probe *p)
{
    NEED("root_observer", getuid() == 0 && geteuid() == 0 && getgid() == 0 && getegid() == 0);
    TRY("observer_namespace_initial", stat("/proc/self/ns/mnt", &p->ns));
    TRY("observer_subreaper", prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0));
    for (int c = 0; c <= 64; ++c) {
        int n = prctl(PR_CAPBSET_READ, c, 0, 0, 0);
        if (n < 0 && errno == EINVAL && c > CAP_SETPCAP) { p->cap_last = c - 1; break; }
        NEED("bounded_capability_range", n >= 0 && c < 64);
    }
    if (instance(p) < 0) return -1;
    TRY("protected_cgroup_parent", p->a[CGROOT].fd = protected_walk(true));
    if (snapshot(&p->a[CGROOT]) < 0) return -1;
    struct statfs fs;
    TRY("cgroup2_preflight", fstatfs(p->a[CGROOT].fd, &fs));
    NEED("cgroup2_required", fs.f_type == CGROUP2_SUPER_MAGIC);
    struct stat st;
    stage = "reject_existing_group";
    if (fstatat(p->a[CGROOT].fd, p->group_name, &st, AT_SYMLINK_NOFOLLOW) == 0) { errno = EEXIST; return -1; }
    if (errno != ENOENT) return -1;
    TRY("exclusive_view", mkdirat(p->a[BASE].fd, "view", 0700)); p->view_made = true;
    if (anchored(&p->a[VIEW], p->a[BASE].fd, "view", DF) < 0) return -1;
    NEED("view_owner_mode", p->a[VIEW].st.st_uid == 0 && p->a[VIEW].st.st_gid == 0 &&
         (p->a[VIEW].st.st_mode & 07777) == 0700 && p->a[VIEW].mount_id == p->a[BASE].mount_id);
    TRY("exclusive_cgroup", mkdirat(p->a[CGROOT].fd, p->group_name, 0700)); p->group_made = true;
    if (anchored(&p->a[GROUP], p->a[CGROOT].fd, p->group_name, DF) < 0) return -1;
    TRY("cgroup_root_owner", fchown(p->a[GROUP].fd, 0, 0));
    TRY("cgroup_root_mode", fchmod(p->a[GROUP].fd, 0700));
    if (snapshot(&p->a[GROUP]) < 0) return -1;
    for (int i = PROCS; i <= TYPE; ++i)
        if (anchored(&p->a[i], p->a[GROUP].fd, names[i], i == PROCS ? O_RDWR : i == KILLCTL ? O_WRONLY : O_RDONLY) < 0) return -1;
    if (group_guard(p) < 0) return -1;
    char procs[64];
    TRY("initial_group_procs", text(p->a[PROCS].fd, procs, sizeof(procs)));
    NEED("initial_group_empty", !*procs && populated(p) == 0); return 0;
}
static int resolved(int fd, const char *name)
{
    struct open_how how = { .flags = DF, .resolve = RESOLVE_IN_ROOT | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS };
    return (int)syscall(SYS_openat2, fd, name, &how, sizeof(how));
}
static int filesystem_at(int view, const char *name, const char *mode, const char *gid)
{
    int target, fs, tree;
    TRY("tmpfs_target", target = resolved(view, name));
    TRY("filesystem_context", fs = (int)syscall(SYS_fsopen, gid ? "devpts" : "tmpfs", FSOPEN_CLOEXEC));
    const char *const keys[] = { gid ? "newinstance" : "size", gid ? "ptmxmode" : "nr_inodes", "mode", "gid" };
    const char *const values[] = { gid ? NULL : "1M", gid ? "0666" : "64", mode, gid };
    for (int i = 0; i < (gid ? 4 : 3); ++i) TRY("filesystem_options", syscall(SYS_fsconfig, fs,
        values[i] ? FSCONFIG_SET_STRING : FSCONFIG_SET_FLAG, keys[i], values[i], 0));
    TRY("tmpfs_create", syscall(SYS_fsconfig, fs, FSCONFIG_CMD_CREATE, NULL, NULL, 0));
    unsigned int attrs = MOUNT_ATTR_NOSUID | MOUNT_ATTR_NOEXEC | (gid || !strcmp(name, "dev") ? 0 : MOUNT_ATTR_NODEV);
    TRY("filesystem_mount", tree = (int)syscall(SYS_fsmount, fs, FSMOUNT_CLOEXEC, attrs));
    TRY("tmpfs_attach", syscall(SYS_move_mount, tree, "", target, "", MOVE_MOUNT_F_EMPTY_PATH | MOVE_MOUNT_T_EMPTY_PATH));
    TRY("close_tmpfs_target", close(target)); TRY("close_tmpfs_tree", close(tree)); TRY("close_tmpfs_context", close(fs));
    int check;
    TRY("tmpfs_reopen", check = resolved(view, name));
    struct statfs st;
    TRY("tmpfs_verify", fstatfs(check, &st));
    unsigned long flags = ST_NOSUID | ST_NOEXEC | (gid || !strcmp(name, "dev") ? 0 : ST_NODEV);
    NEED("private_filesystem_flags", st.f_type == (gid ? DEVPTS_SUPER_MAGIC : TMPFS_MAGIC) &&
         (st.f_flags & (ST_NOSUID | ST_NODEV | ST_NOEXEC | ST_RDONLY)) == flags);
    TRY("close_tmpfs_check", close(check)); return 0;
}
static int close_except(int first, int second, int third)
{
    int keep[] = { first, second, third };
    unsigned int from = 3;
    for (;;) {
        unsigned int next = UINT_MAX;
        for (int i = 0; i < 3; ++i) if (keep[i] >= 3 && (unsigned int)keep[i] >= from && (unsigned int)keep[i] < next) next = (unsigned int)keep[i];
        if (next > from && syscall(SYS_close_range, from, next - 1, 0) < 0) return -1;
        if (next == UINT_MAX) return (int)syscall(SYS_close_range, UINT_MAX, UINT_MAX, 0);
        from = next + 1;
    }
}
static int private_pty(int view, uid_t uid, int *master, int *slave)
{
    phase = 10;
    if (filesystem_at(view, "dev", "0755", NULL) < 0) return -1;
    int dev;
    TRY("private_dev", dev = resolved(view, "dev"));
    TRY("private_pts_target", mkdirat(dev, "pts", 0755));
    TRY("private_ptmx_link", symlinkat("pts/ptmx", dev, "ptmx"));
    TRY("private_null", mknodat(dev, "null", S_IFCHR | 0666, makedev(1, 3)));
    TRY("private_null_mode", fchmodat(dev, "null", 0666, 0));
    TRY("private_tty", mknodat(dev, "tty", S_IFCHR | 0666, makedev(5, 0)));
    TRY("private_tty_mode", fchmodat(dev, "tty", 0666, 0));
    char gid[16]; (void)snprintf(gid, sizeof(gid), "%u", (unsigned int)uid);
    if (filesystem_at(dev, "pts", "0600", gid) < 0) return -1;
    int pts;
    TRY("private_pts", pts = resolved(dev, "pts"));
    TRY("private_ptmx", *master = openat(pts, "ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK));
    int unlock = 0;
    unsigned int number;
    TRY("unlock_pty", ioctl(*master, TIOCSPTLCK, &unlock));
    TRY("pty_number", ioctl(*master, TIOCGPTN, &number));
    TRY("pty_peer", *slave = ioctl(*master, TIOCGPTPEER, O_RDWR | O_NOCTTY | O_CLOEXEC));
    struct stat st;
    TRY("pty_slave_owner", fchown(*slave, uid, uid)); TRY("pty_slave_mode", fchmod(*slave, 0600));
    TRY("pty_slave_stat", fstat(*slave, &st));
    NEED("pty_slave_identity", S_ISCHR(st.st_mode) && st.st_uid == uid && st.st_gid == uid && (st.st_mode & 07777) == 0600);
    struct winsize size = { .ws_row = 24, .ws_col = 80 };
    TRY("initial_pty_size", ioctl(*master, TIOCSWINSZ, &size));
    struct termios t = { .c_iflag = ICRNL, .c_oflag = OPOST | ONLCR, .c_cflag = CS8 | CREAD | CLOCAL, .c_lflag = ICANON | ISIG };
    t.c_cc[VINTR] = 3; t.c_cc[VEOF] = 4; t.c_cc[VQUIT] = 28; t.c_cc[VSUSP] = 26;
    t.c_cc[VERASE] = 127; t.c_cc[VKILL] = 21; t.c_cc[VMIN] = 1;
    TRY("pty_input_speed", cfsetispeed(&t, B38400)); TRY("pty_output_speed", cfsetospeed(&t, B38400));
    TRY("pty_termios", tcsetattr(*slave, TCSAFLUSH, &t));
    TRY("pty_new_session", setsid()); TRY("pty_controlling_terminal", ioctl(*slave, TIOCSCTTY, 0));
    TRY("pty_foreground", tcsetpgrp(*slave, getpid()));
    pid_t sid = -1;
    TRY("pty_session", ioctl(*slave, TIOCGSID, &sid)); NEED("pty_session_identity", sid == getpid());
    TRY("close_private_pts", close(pts)); TRY("close_private_dev", close(dev)); return 0;
}
static int send_master(int socket, int master)
{
    struct pty_packet packet = { .magic = UINT32_C(0x41505431) };
    TRY("send_pty_number", ioctl(master, TIOCGPTN, &packet.number));
    union { struct cmsghdr alignment; unsigned char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    struct iovec iov = { .iov_base = &packet, .iov_len = sizeof(packet) };
    struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes) };
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
    c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(c), &master, sizeof(master));
    ssize_t n;
    TRY("send_pty_master", n = sendmsg(socket, &msg, MSG_NOSIGNAL)); NEED("send_one_master", n == (ssize_t)sizeof(packet)); return 0;
}
static int receive_master(int socket, pid_t child_pid, uid_t uid, struct terminal *terminal)
{
    struct pty_packet packet = {0};
    union { struct cmsghdr alignment; unsigned char bytes[CMSG_SPACE(sizeof(int) * 4) + CMSG_SPACE(sizeof(struct ucred))]; } control = {0};
    struct iovec iov = { .iov_base = &packet, .iov_len = sizeof(packet) };
    struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes) };
    stage = "receive_pty_master";
    ssize_t n = recvmsg(socket, &msg, MSG_CMSG_CLOEXEC | MSG_DONTWAIT);
    if (n < 0) return errno == EAGAIN ? 0 : -1;
    if (n == 0) { if (terminal->received) return 0; errno = EPROTO; return -1; }
    int fds[4], count = 0, rights = 0, credentials = 0;
    bool valid = !terminal->received && n == (ssize_t)sizeof(packet) && !(msg.msg_flags & (MSG_CTRUNC | MSG_TRUNC)) && packet.magic == UINT32_C(0x41505431);
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS && c->cmsg_len >= CMSG_LEN(0)) {
            size_t bytes = c->cmsg_len - CMSG_LEN(0), got = bytes / sizeof(int);
            ++rights; if (bytes != sizeof(int)) valid = false;
            for (size_t i = 0; i < got; ++i) {
                int fd; memcpy(&fd, (unsigned char *)CMSG_DATA(c) + i * sizeof(int), sizeof(fd));
                if (count < 4) fds[count++] = fd; else { close(fd); valid = false; }
            }
        } else if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_CREDENTIALS && c->cmsg_len == CMSG_LEN(sizeof(struct ucred))) {
            struct ucred cred; memcpy(&cred, CMSG_DATA(c), sizeof(cred)); ++credentials;
            if (cred.pid != child_pid || cred.uid != uid || cred.gid != uid) valid = false;
        } else valid = false;
    }
    valid = valid && rights == 1 && credentials == 1 && count == 1;
    int error = EPROTO, peer = -1;
    if (valid) {
        unsigned int number = UINT_MAX; struct statfs fs; struct stat st;
        int flags = fcntl(fds[0], F_GETFL), fdflags = fcntl(fds[0], F_GETFD);
        if (flags < 0 || fdflags < 0 || fstatfs(fds[0], &fs) < 0 || fstat(fds[0], &st) < 0 || ioctl(fds[0], TIOCGPTN, &number) < 0) { error = errno; valid = false; }
        else if (number != packet.number || !S_ISCHR(st.st_mode) || fs.f_type != DEVPTS_SUPER_MAGIC ||
                 (flags & (O_ACCMODE | O_NONBLOCK)) != (O_RDWR | O_NONBLOCK) || !(fdflags & FD_CLOEXEC)) valid = false;
        if (valid) {
            peer = ioctl(fds[0], TIOCGPTPEER, O_RDWR | O_NOCTTY | O_CLOEXEC | O_NONBLOCK);
            if (peer < 0 || fstat(peer, &st) < 0) { error = errno; valid = false; }
            else if (!S_ISCHR(st.st_mode) || st.st_uid != uid || st.st_gid != uid || (st.st_mode & 07777) != 0600) valid = false;
        }
    }
    if (peer >= 0 && close(peer) < 0) { error = errno; valid = false; }
    if (!valid) { for (int i = 0; i < count; ++i) close(fds[i]); errno = error; return -1; }
    terminal->stream.fd = fds[0]; terminal->received = true; return 0;
}
static int guest_mount(struct probe *p, int *view)
{
    phase = 2;
    TRY("unshare_mount", unshare(CLONE_NEWNS));
    TRY("recursive_private", mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL));
    struct stat ns;
    TRY("child_namespace", stat("/proc/self/ns/mnt", &ns));
    NEED("private_namespace", ns.st_dev != p->ns.st_dev || ns.st_ino != p->ns.st_ino);
    phase = 3;
    struct probe q = *p;
    for (int i = 0; i < ANCHORS; ++i) { q.a[i].fd = -1; q.a[i].valid = false; }
    if (instance(&q) < 0 || anchored(&q.a[VIEW], q.a[BASE].fd, "view", DF) < 0) return -1;
    for (int i = LOCAL; i <= VIEW; ++i) {
        NEED("reopened_instance_identity", same(&q.a[i].st, &p->a[i].st));
        TRY("close_inherited_instance", close(p->a[i].fd)); p->a[i].fd = -1;
    }
    for (int i = CGROOT; i < ANCHORS; ++i) { TRY("close_inherited_cgroup", close(p->a[i].fd)); p->a[i].fd = -1; }
    NEED("private_view_mount_identity", q.a[VIEW].mount_id == q.a[BASE].mount_id);
    phase = 4;
    int tree;
    TRY("clone_root", tree = (int)syscall(SYS_open_tree, q.a[ROOT].fd, "", OPEN_TREE_CLONE | OPEN_TREE_CLOEXEC | AT_EMPTY_PATH));
    struct mount_attr attr = { .attr_set = MOUNT_ATTR_RDONLY | MOUNT_ATTR_NOSUID | MOUNT_ATTR_NODEV, .attr_clr = MOUNT_ATTR_NOEXEC };
    TRY("readonly_os", syscall(SYS_mount_setattr, tree, "", AT_EMPTY_PATH | AT_RECURSIVE, &attr, sizeof(attr)));
    TRY("attach_view", syscall(SYS_move_mount, tree, "", q.a[VIEW].fd, "", MOVE_MOUNT_F_EMPTY_PATH | MOVE_MOUNT_T_EMPTY_PATH));
    TRY("open_guest_view", *view = resolved(q.a[BASE].fd, "view"));
    struct statfs fs;
    TRY("readonly_flags", fstatfs(*view, &fs));
    NEED("executable_readonly_os", (fs.f_flags & (ST_RDONLY | ST_NOSUID | ST_NODEV | ST_NOEXEC)) ==
         (ST_RDONLY | ST_NOSUID | ST_NODEV));
    return filesystem_at(*view, "tmp", "1777", NULL) < 0 ? -1 : filesystem_at(*view, "run", "0755", NULL);
}
static int drop(struct probe *p, pid_t parent)
{
    TRY("drop_dumpable", prctl(PR_SET_DUMPABLE, 0, 0, 0, 0));
    TRY("drop_keepcaps", prctl(PR_SET_KEEPCAPS, 0, 0, 0, 0));
    TRY("drop_ambient", prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL, 0, 0, 0));
    TRY("lock_noroot", prctl(PR_SET_SECUREBITS, SECBIT_NOROOT | SECBIT_NOROOT_LOCKED, 0, 0, 0));
    for (int c = 0; c <= p->cap_last; ++c) TRY("drop_bounding", prctl(PR_CAPBSET_DROP, c, 0, 0, 0));
    gid_t group = 3003;
    TRY("fixed_groups", setgroups(1, &group)); TRY("drop_gid", setresgid(p->uid, p->uid, p->uid));
    TRY("drop_uid", setresuid(p->uid, p->uid, p->uid));
    struct __user_cap_header_struct h = { .version = _LINUX_CAPABILITY_VERSION_3 };
    struct __user_cap_data_struct d[2] = {{0}};
    TRY("clear_caps", syscall(SYS_capset, &h, d));
    TRY("nnp", prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    TRY("redrop_dumpable", prctl(PR_SET_DUMPABLE, 0, 0, 0, 0));
    TRY("rearm_pdeathsig", prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0));
    NEED("parent_alive_after_drop", getppid() == parent);
    uid_t u[3]; gid_t g[3], groups[2];
    TRY("verify_uids", getresuid(&u[0], &u[1], &u[2])); TRY("verify_gids", getresgid(&g[0], &g[1], &g[2]));
    for (int i = 0; i < 3; ++i) NEED("all_ids_dropped", u[i] == p->uid && g[i] == p->uid);
    NEED("only_inet_group", getgroups(2, groups) == 1 && groups[0] == 3003);
    TRY("verify_caps", syscall(SYS_capget, &h, d));
    for (int i = 0; i < 2; ++i) NEED("zero_caps", !d[i].effective && !d[i].permitted && !d[i].inheritable);
    for (int c = 0; c <= p->cap_last; ++c) {
        int b = prctl(PR_CAPBSET_READ, c, 0, 0, 0), a = prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_IS_SET, c, 0, 0);
        NEED("zero_bounding_ambient", b == 0 && a == 0);
    }
    NEED("verify_nnp", prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) == 1); return 0;
}
static void child_failed(void)
{
    struct failure f = { UINT32_C(0x41525031), phase, errno > 0 ? errno : EIO };
    ssize_t n = write(child_error_fd, &f, sizeof(f)); (void)n;
    _exit(3);
}
static void child(struct probe *p, pid_t parent, int input, int out, int err, int gate, int socket)
{
    child_error_fd = err;
    if (dup2(err, STDERR_FILENO) < 0) child_failed();
    child_error_fd = STDERR_FILENO;
    if (dup2(out, STDOUT_FILENO) < 0 || dup2(input, STDIN_FILENO) < 0 || reset_signals(true) < 0) child_failed();
    (void)alarm(8);
    phase = 1;
    if (prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0) < 0) child_failed();
    if (getppid() != parent) { errno = ECHILD; child_failed(); }
    struct pollfd pf = { .fd = gate, .events = POLLIN };
    char token = 0;
    if (poll(&pf, 1, 7000) <= 0 || read(gate, &token, 1) != 1 || token != 'G') { errno = EPROTO; child_failed(); }
    if (close(gate) < 0) child_failed();
    int view = -1;
    if (guest_mount(p, &view) < 0) child_failed();
    int master = -1, slave = -1;
    if (p->mode == PTY) {
        if (private_pty(view, p->uid, &master, &slave) < 0) child_failed();
        int report = fcntl(STDERR_FILENO, F_DUPFD_CLOEXEC, 3);
        if (report < 0) child_failed();
        child_error_fd = report;
        if (dup2(slave, STDIN_FILENO) < 0 || dup2(slave, STDOUT_FILENO) < 0 || dup2(slave, STDERR_FILENO) < 0) child_failed();
    }
    phase = 5;
    if (fchdir(view) < 0 || chroot(".") < 0 || chdir("/") < 0) child_failed();
    phase = 6;
    if ((p->mode == PTY ? close_except(master, socket, child_error_fd) : syscall(SYS_close_range, 3U, UINT_MAX, 0)) < 0) child_failed();
    phase = 7;
    if (drop(p, parent) < 0) child_failed();
    phase = 8;
    int fd = open("/etc/ajiu_probe_readonly", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK, 0600);
    if (fd >= 0) { close(fd); errno = EPROTO; child_failed(); }
    if (errno != EROFS) child_failed();
    if (p->mode == PTY) {
        phase = 11;
        if (send_master(socket, master) < 0 || close(master) < 0 || close(socket) < 0) child_failed();
    }
    phase = 9;
    const char *literal = p->mode == NETWORK ? network_script : p->mode == PTY ? pty_script : script;
    char *const argv[] = { "busybox", "sh", "-c", (char *)literal, NULL };
    char *const env[] = { "PATH=/bin:/usr/bin", "HOME=/tmp", "LANG=C", NULL };
    execve("/bin/busybox", argv, env); child_failed();
}
static int drain(struct stream *s)
{
    if (s->fd < 0 || s->eof) return 0;
    unsigned char buf[129];
    ssize_t n = read(s->fd, buf, sizeof(buf));
    if (n < 0 && errno == EAGAIN) return 0;
    if (n < 0) return -1;
    if (n == 0) { s->eof = true; return 0; }
    if ((size_t)n > 128 - s->used) { errno = EOVERFLOW; return -1; }
    memcpy(s->bytes + s->used, buf, (size_t)n); s->used += (size_t)n; return 0;
}
static int terminal_step(struct terminal *terminal, bool reaped, pid_t child_pid)
{
    struct stream *s = &terminal->stream;
    if (!terminal->received || s->eof) return 0;
    static const char *const expected[] = { "pty_resize_ready\r\n", "pty_resize_ready\r\npty_size_ok\r\npty_interrupt_ready\r\n",
        "pty_resize_ready\r\npty_size_ok\r\npty_interrupt_ready\r\npty_interrupt_ok\r\npty_eof_ready\r\n" };
    if (terminal->step < 3) {
        const char *prefix = expected[terminal->step]; size_t size = strlen(prefix);
        NEED("pty_handshake_prefix", s->used <= size && !memcmp(s->bytes, prefix, s->used));
        if (s->used == size) {
            pid_t foreground = -1;
            TRY("pty_foreground_group", ioctl(s->fd, TIOCGPGRP, &foreground));
            NEED("pty_foreground_identity", foreground > 1 && (terminal->step == 1 ? foreground != child_pid : foreground == child_pid));
            if (terminal->step == 0) {
                struct winsize size = { .ws_row = 40, .ws_col = 100 };
                TRY("pty_resize_100x40", ioctl(s->fd, TIOCSWINSZ, &size));
            }
            const char *input = terminal->step == 0 ? "resize\n" : terminal->step == 1 ? "\003" : "\004";
            TRY("pty_input_bytes", put(s->fd, input, terminal->step == 0 ? 7 : 1)); ++terminal->step;
        }
    }
    unsigned char buf[129];
    ssize_t n = read(s->fd, buf, sizeof(buf));
    if (n < 0 && errno == EAGAIN) return 0;
    if (n < 0 && errno == EIO && terminal->step == 3) { s->eof = reaped; return 0; }
    if (n < 0) return -1;
    if (n == 0) { s->eof = true; return 0; }
    NEED("pty_output_bounded", (size_t)n <= sizeof(s->bytes) - s->used);
    memcpy(s->bytes + s->used, buf, (size_t)n); s->used += (size_t)n; return 0;
}
static int supervise(struct probe *p, int64_t start, int *guest_exit, bool *settled)
{
    int out[2] = {-1,-1}, err[2] = {-1,-1}, gate[2] = {-1,-1}, sockets[2] = {-1,-1}, input = -1, pidfd = -1;
    struct terminal terminal = { .stream = { .fd = -1 } };
    pid_t pid = -1, parent = getpid();
    int error = 0, status = 0; const char *failed_stage = NULL;
    bool killed = false, reaped = false, no_children = false, empty = false;
    struct stream streams[2] = {{ .fd = -1 }, { .fd = -1 }};
    stage = "supervisor_pipes";
    if (pipe2(out, O_CLOEXEC | O_NONBLOCK) < 0 || pipe2(err, O_CLOEXEC | O_NONBLOCK) < 0 ||
        pipe2(gate, O_CLOEXEC | O_NONBLOCK) < 0) goto done;
    if (p->mode == PTY) {
        stage = "private_pty_socket";
        if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, sockets) < 0) goto done;
        int pass = 1;
        if (setsockopt(sockets[0], SOL_SOCKET, SO_PASSCRED, &pass, sizeof(pass)) < 0) goto done;
    }
    stage = "sealed_empty_stdin";
    input = (int)syscall(SYS_memfd_create, "ajiu-probe-stdin", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (input < 0 || fcntl(input, F_SETFL, O_NONBLOCK) < 0 || fcntl(input, F_ADD_SEALS,
        F_SEAL_SEAL | F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK) < 0) goto done;
    stage = "fork_root_child"; pid = fork();
    if (pid == 0) {
        if (sockets[0] >= 0 && close(sockets[0]) < 0) {
            child_error_fd = err[1]; child_failed();
        }
        child(p, parent, input, out[1], err[1], gate[0], sockets[1]);
    }
    if (pid < 0) goto done;
    *settled = false;
    streams[0].fd = out[0]; streams[1].fd = err[0];
    int *ends[] = { &out[1], &err[1], &gate[0] };
    for (int i = 0; i < 3; ++i) { int fd = *ends[i]; *ends[i] = -1;
        if (close(fd) < 0) { stage = "close_parent_pipe_end"; goto monitor_error; }
    }
    if (sockets[1] >= 0) { int fd = sockets[1]; sockets[1] = -1;
        if (close(fd) < 0) { stage = "close_parent_pty_peer"; goto monitor_error; }
    }
    stage = "pidfd_before_gate"; pidfd = (int)syscall(SYS_pidfd_open, pid, 0);
    if (pidfd < 0) goto monitor_error;
    if (group_guard(p) < 0) goto monitor_error;
    char target[32], members[64];
    int size = snprintf(target, sizeof(target), "%ld\n", (long)pid);
    stage = "move_gated_child";
    if (put(p->a[PROCS].fd, target, (size_t)size) < 0) goto monitor_error;
    if (text(p->a[PROCS].fd, members, sizeof(members)) < 0) goto monitor_error;
    if (strcmp(target, members)) { errno = EPROTO; goto monitor_error; }
    stage = "release_gated_child";
    if (put(gate[1], "G", 1) < 0) goto monitor_error;
    goto monitor;
monitor_error:
    error = errno ? errno : EIO; failed_stage = stage;
monitor:
    if (close(gate[1]) < 0 && !error) { error = errno; failed_stage = "close_parent_gate"; }
    gate[1] = -1;
    for (;;) {
        stage = "reap_payload_tree";
        for (int i = 0; i < 128; ++i) {
            int st;
            pid_t w = waitpid(-1, &st, WNOHANG);
            if (w > 0) { if (w == pid) { status = st; reaped = true; } continue; }
            if (w < 0 && errno == ECHILD) no_children = true;
            else if (w < 0 && !error) { error = errno; failed_stage = stage; }
            break;
        }
        for (int i = 0; i < 2; ++i) if (drain(&streams[i]) < 0 && !error) {
            error = errno; failed_stage = "bounded_guest_stream";
        }
        if (p->mode == PTY && !error) {
            if (!terminal.received && receive_master(sockets[0], pid, p->uid, &terminal) < 0) { error = errno; failed_stage = stage; }
            if (terminal.received && sockets[0] >= 0) { int fd = sockets[0]; sockets[0] = -1;
                if (close(fd) < 0) { error = errno; failed_stage = "close_pty_transfer"; }
            }
            if (!error && terminal_step(&terminal, reaped, pid) < 0) { error = errno; failed_stage = stage; }
        }
        int64_t now = now_ms();
        if (now < 0) { if (!error) { error = errno; failed_stage = "clock_supervise"; } now = start + 11000; }
        if (!error && (expired || (!reaped && now >= start + 8500))) { error = ETIMEDOUT; failed_stage = "guest_deadline"; }
        if (!killed && (reaped || error)) {
            if (group_guard(p) < 0) { if (!error) { error = errno; failed_stage = stage; } break; }
            stage = "kill_payload_cgroup";
            if (put(p->a[KILLCTL].fd, "1\n", 2) < 0) { if (!error) { error = errno; failed_stage = stage; } break; }
            killed = true;
        }
        if (killed) {
            if (group_guard(p) < 0) { if (!error) { error = errno; failed_stage = stage; } break; }
            int pop = populated(p);
            if (pop < 0) { if (!error) { error = errno; failed_stage = "cleanup_populated"; } break; }
            empty = pop == 0;
        }
        bool io_done = streams[0].eof && streams[1].eof && (p->mode != PTY || terminal.stream.eof);
        if (killed && reaped && no_children && empty && (error || io_done)) { *settled = true; break; }
        if (now >= start + 11000) { if (!error) { error = ETIMEDOUT; failed_stage = "cleanup_deadline"; } break; }
        struct pollfd pf[4] = { { streams[0].eof ? -1 : streams[0].fd, POLLIN, 0 },
            { streams[1].eof ? -1 : streams[1].fd, POLLIN, 0 }, { reaped ? -1 : pidfd, POLLIN, 0 },
            { p->mode != PTY || terminal.stream.eof ? -1 : terminal.received ? terminal.stream.fd : sockets[0], POLLIN, 0 } };
        if (poll(pf, 4, 10) < 0 && !error) { error = errno; failed_stage = "poll_guest"; }
    }
    if (reaped && WIFEXITED(status)) *guest_exit = WEXITSTATUS(status);
    if (reaped && WIFSIGNALED(status) && !error) {
        error = WTERMSIG(status) == SIGALRM ? ETIMEDOUT : ECHILD;
        failed_stage = WTERMSIG(status) == SIGALRM ? "guest_alarm" : "guest_signal";
    }
    if (*guest_exit == 3 && streams[1].used == sizeof(struct failure)) {
        struct failure f; memcpy(&f, streams[1].bytes, sizeof(f));
        if (f.magic == UINT32_C(0x41525031) && f.phase >= 0 && (size_t)f.phase < sizeof(phases)/sizeof(*phases) && f.error > 0 && f.error < 4096) {
            error = f.error; failed_stage = phases[f.phase];
        }
    }
    if (!error) {
        char expected[48];
        int size = p->mode == STDIO ? snprintf(expected, sizeof(expected), "%u\nguest_shell_ok\n", (unsigned int)p->uid) :
                   snprintf(expected, sizeof(expected), "%s", p->mode == NETWORK ? "network_ok\n" : "");
        const char *expected_err = p->mode == STDIO ? err_marker : p->mode == NETWORK ? "network_stderr_ok\n" : "";
        static const char expected_pty[] = "pty_resize_ready\r\npty_size_ok\r\npty_interrupt_ready\r\npty_interrupt_ok\r\npty_eof_ready\r\npty_ok\r\n";
        int expected_exit = p->mode == STDIO ? 37 : p->mode == NETWORK ? 38 : 39;
        if (!error && (!*settled || *guest_exit != expected_exit || size < 0 || (size_t)size >= sizeof(expected) || streams[0].used != (size_t)size ||
            memcmp(streams[0].bytes, expected, (size_t)size) || streams[1].used != strlen(expected_err) ||
            memcmp(streams[1].bytes, expected_err, strlen(expected_err)) || (p->mode == PTY && (!terminal.received || terminal.step != 3 ||
            terminal.stream.used != sizeof(expected_pty)-1 || memcmp(terminal.stream.bytes, expected_pty, sizeof(expected_pty)-1))))) {
            error = EPROTO; failed_stage = p->mode == NETWORK ? "network_markers_exit" : p->mode == PTY ? "pty_markers_exit" : "guest_markers_exit";
        }
    }
done:
    if (!error && pid < 0) { error = errno ? errno : EIO; failed_stage = stage; }
    int fds[] = { out[0], out[1], err[0], err[1], gate[0], gate[1], input, pidfd, sockets[0], sockets[1], terminal.stream.fd };
    for (size_t i = 0; i < sizeof(fds)/sizeof(*fds); ++i) if (fds[i] >= 0 && close(fds[i]) < 0 && !error) { error = errno; failed_stage = "close_supervisor_fd"; }
    stage = error ? failed_stage : "guest_complete"; errno = error;
    return error ? -1 : 0;
}
static int cleanup(struct probe *p)
{
    struct stat ns;
    TRY("final_mount_namespace", stat("/proc/self/ns/mnt", &ns));
    NEED("final_namespace_unchanged", ns.st_dev == p->ns.st_dev && ns.st_ino == p->ns.st_ino);
    if (p->group_made) {
        if (group_guard(p) < 0) return -1;
        char buf[64];
        TRY("final_group_procs", text(p->a[PROCS].fd, buf, sizeof(buf)));
        NEED("final_group_empty", !*buf && populated(p) == 0);
    }
    if (p->view_made) {
        int local = protected_walk(false); if (local < 0) return -1;
        int rc = current(&p->a[BASE], local, p->base_name), e = errno;
        if (close(local) < 0 && rc == 0) return -1;
        if (rc < 0) { errno = e; return -1; }
        for (int i = ROOT; i <= VIEW; ++i) if (current(&p->a[i], p->a[BASE].fd, names[i]) < 0) return -1;
        int fd = openat(p->a[VIEW].fd, ".", DF); if (fd < 0) return -1;
        DIR *dir = fdopendir(fd);
        if (!dir) { int saved = errno; close(fd); errno = saved; return -1; }
        int error = 0;
        for (int i = 0; i < 4; ++i) {
            errno = 0; struct dirent *entry = readdir(dir);
            if (!entry) { error = errno; break; }
            if (strcmp(entry->d_name, ".") && strcmp(entry->d_name, "..")) { error = EPROTO; break; }
            if (i == 3) error = EOVERFLOW;
        }
        if (closedir(dir) < 0 && !error) error = errno;
        if (error) { errno = error; return -1; }
        if (current(&p->a[VIEW], p->a[BASE].fd, "view") < 0) return -1;
        TRY("remove_empty_view", unlinkat(p->a[BASE].fd, "view", AT_REMOVEDIR));
    }
    if (p->group_made) {
        if (group_guard(p) < 0) return -1;
        TRY("remove_empty_group", unlinkat(p->a[CGROOT].fd, p->group_name, AT_REMOVEDIR));
    }
    return 0;
}
int main(int argc, char **argv)
{
    struct probe p = {0}; for (int i = 0; i < ANCHORS; ++i) p.a[i].fd = -1;
    bool help = argc == 2 && !strcmp(argv[1], "--help"), valid = arguments(argc, argv, &p);
    int rc = help ? 0 : valid ? 3 : 2, saved = valid || help ? 0 : EINVAL, guest_exit = -1;
    const char *state = "not_created", *failed_stage = help ? "help" : "arguments";
    struct sigaction ignore = { .sa_handler = SIG_IGN };
    int io_error = sigemptyset(&ignore.sa_mask) < 0 || sigaction(SIGPIPE, &ignore, NULL) < 0 ? errno : 0;
    int flags = fcntl(STDOUT_FILENO, F_GETFL);
    if ((flags < 0 || fcntl(STDOUT_FILENO, F_SETFL, flags | O_NONBLOCK) < 0) && !io_error) io_error = errno;
    if (help) return io_error ? 3 : puts("--help | (--exercise | --network | --pty) <instance24hex> <expected_base_dev> <expected_base_ino> <uid:10000..INT_MAX> <gid:uid>; groups:3003; fixed literals only") < 0 ? 3 : 0;
    bool settled = true;
    if (valid) {
        int64_t start = now_ms();
        stage = io_error ? "parent_output_setup" : start < 0 ? "clock_initial" : "parent_signal_reset";
        int result = io_error || start < 0 ? -1 : reset_signals(false);
        if (io_error) errno = io_error;
        if (!result) {
            (void)umask(0077);
            for (int i = 0; i < 3; ++i) if (fcntl(i, F_GETFD) < 0) { stage = "parent_stdio"; result = -1; break; }
        }
        if (!result) {
            struct sigaction sa = { .sa_handler = deadline_signal };
            stage = "parent_deadline_signal";
            result = sigemptyset(&sa.sa_mask) < 0 ? -1 : sigaction(SIGALRM, &sa, NULL);
            if (!result) (void)alarm(9);
        }
        if (!result) result = setup(&p);
        if (!result) {
            int64_t now = now_ms();
            if (now < 0 || expired || now >= start + 8500) { stage = "setup_deadline"; errno = now < 0 ? errno : ETIMEDOUT; result = -1; }
        }
        if (!result) result = supervise(&p, start, &guest_exit, &settled);
        saved = result < 0 ? errno : 0; failed_stage = stage; rc = result < 0 ? 3 : 0;
        if (p.view_made || p.group_made) {
            if (!settled) { rc = 5; state = "cleanup_unknown"; }
            else if (cleanup(&p) < 0) { saved = errno; failed_stage = stage; rc = 5; state = "cleanup_unknown"; }
            else state = "removed";
        }
        for (int i = 0; i < ANCHORS; ++i) if (p.a[i].fd >= 0 && close(p.a[i].fd) < 0 && rc == 0) {
            saved = errno; failed_stage = "close_observer_fd"; rc = 3;
        }
        (void)alarm(0);
    }
    char json[384];
    int n = snprintf(json, sizeof(json), "{\"passed\":%s,\"stage\":\"%s\",\"error_code\":\"%s\",\"errno\":%d,"
        "\"guest_exit\":%d,\"cleanup_state\":\"%s\"}\n", rc == 0 ? "true" : "false", failed_stage,
        rc == 0 ? "none" : rc == 2 ? "invalid_arguments" : rc == 5 ? "cleanup_unknown" : "capability_failed", saved, guest_exit, state);
    if (io_error || n <= 0 || (size_t)n >= sizeof(json) || put(STDOUT_FILENO, json, (size_t)n) < 0) return rc ? rc : 3;
    return rc;
}
