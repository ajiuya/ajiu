#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/magic.h>
#include <linux/mount.h>
#include <linux/openat2.h>
#include <linux/stat.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define PREFIX "ajiu-chroot-mount-"
#define PACKET_MAGIC UINT32_C(0x414d5031)
#define DIR_FLAGS (O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
#define STEP(label, expr) do { stage = (label); if ((expr) < 0) return -1; } while (0)
#define NEED(label, cond) do { stage = (label); if (!(cond)) { errno = EPROTO; return -1; } } while (0)

enum { BASE, SOURCE, VIEW, TMP, PTS, SENTINEL, OBJECTS };
static const char *const names[OBJECTS] = { "base", "source", "view", "tmp", "pts", "sentinel" };
static const char sentinel[] = "ajiu private mount probe v1\n";
static const char *stage = "startup", *object = "none";
static int output_fd = -1;
struct result { uint32_t magic; int error; char stage[64], object[24]; };
struct anchor {
    int fd;
    bool made, valid;
    struct stat st;
    uint64_t mount_id;
    long fs_type;
};
struct probe {
    int root, data, local;
    char name[sizeof(PREFIX) + 24];
    struct stat ns;
    struct anchor a[OBJECTS];
    bool ready;
};

static int64_t milliseconds(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) < 0) return -1;
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void output(const char *text)
{
    if (output_fd >= 0) {
        ssize_t n = write(output_fd, text, strlen(text));
        (void)n;
    }
}

static void save_result(struct result *r, int error)
{
    memset(r, 0, sizeof(*r));
    r->magic = PACKET_MAGIC;
    r->error = error;
    (void)snprintf(r->stage, sizeof(r->stage), "%s", stage);
    (void)snprintf(r->object, sizeof(r->object), "%s", object);
}

static void event(const struct result *r)
{
    char text[320];
    (void)snprintf(text, sizeof(text),
        "{\"event\":\"probe_result\",\"stage\":\"%s\",\"object\":\"%s\",\"errno\":%d}\n",
        r->stage, r->object, r->error);
    output(text);
}

static void final(const struct probe *p, int rc, const char *code,
                  const struct result *r, const char *cleanup)
{
    char text[640], path[128] = "null";
    if (p->a[BASE].made)
        (void)snprintf(path, sizeof(path), "\"/data/local/%s\"", p->name);
    (void)snprintf(text, sizeof(text),
        "{\"passed\":%s,\"error_code\":\"%s\",\"stage\":\"%s\",\"object\":\"%s\","
        "\"errno\":%d,\"cleanup_state\":\"%s\",\"created_directory\":%s}\n",
        rc == 0 ? "true" : "false", code, r->stage, r->object, r->error, cleanup, path);
    output(text);
}

static bool same_inode(const struct stat *a, const struct stat *b)
{
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino;
}

static int snapshot(struct anchor *a)
{
    struct statx sx = {0};
    struct statfs fs;
    STEP("fstat_object", fstat(a->fd, &a->st));
    STEP("statx_mount_id", syscall(SYS_statx, a->fd, "", AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW,
                                   STATX_MNT_ID, &sx));
    NEED("statx_mount_id_present", sx.stx_mask & STATX_MNT_ID);
    STEP("fstatfs_object", fstatfs(a->fd, &fs));
    a->mount_id = sx.stx_mnt_id;
    a->fs_type = fs.f_type;
    a->valid = true;
    return 0;
}

static int secure_parent(int fd)
{
    struct stat st;
    STEP("fstat_parent", fstat(fd, &st));
    NEED("parent_not_app_writable", S_ISDIR(st.st_mode) && (st.st_uid == 0 || st.st_uid == 1000) &&
         !(st.st_mode & S_IWOTH) && (!(st.st_mode & S_IWGRP) || st.st_gid == 0 || st.st_gid == 1000));
    return 0;
}

static int parent_fd(const struct probe *p, int i)
{
    return i == BASE ? p->local : p->a[i == SENTINEL ? SOURCE : BASE].fd;
}

static const char *entry_name(const struct probe *p, int i)
{
    return i == BASE ? p->name : names[i];
}

static int setup(struct probe *p)
{
    stage = "root_observer";
    if (getuid() != 0 || geteuid() != 0) { errno = EPERM; return -1; }
    STEP("stat_observer_namespace", stat("/proc/self/ns/mnt", &p->ns));
    object = "root";
    STEP("open_root", p->root = open("/", DIR_FLAGS));
    if (secure_parent(p->root) < 0) return -1;
    object = "data";
    STEP("openat_data", p->data = openat(p->root, "data", DIR_FLAGS));
    if (secure_parent(p->data) < 0) return -1;
    object = "local";
    STEP("openat_local", p->local = openat(p->data, "local", DIR_FLAGS));
    if (secure_parent(p->local) < 0) return -1;
    (void)umask(0077);
    for (int i = 0; i < OBJECTS; ++i) {
        struct anchor *a = &p->a[i];
        object = names[i];
        if (i != SENTINEL) {
            STEP("mkdirat_exclusive", mkdirat(parent_fd(p, i), entry_name(p, i), 0700));
            a->made = true;
            STEP("openat_created_directory", a->fd = openat(parent_fd(p, i), entry_name(p, i), DIR_FLAGS));
        } else {
            STEP("openat_exclusive_sentinel", a->fd = openat(p->a[SOURCE].fd, "sentinel",
                 O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0400));
            a->made = true;
        }
        if (snapshot(a) < 0) return -1;
        NEED("created_object_owner_mode", a->st.st_uid == 0 &&
             (a->st.st_mode & 07777) == (i == SENTINEL ? 0400 : 0700) &&
             (i == SENTINEL ? S_ISREG(a->st.st_mode) && a->st.st_nlink == 1 : S_ISDIR(a->st.st_mode)));
        NEED("created_object_mount", a->mount_id == p->a[BASE].mount_id && a->st.st_dev == p->a[BASE].st.st_dev);
    }
    object = "sentinel";
    STEP("pwrite_initial_sentinel", pwrite(p->a[SENTINEL].fd, sentinel, sizeof(sentinel) - 1, 0));
    struct stat st;
    STEP("fstat_initial_sentinel", fstat(p->a[SENTINEL].fd, &st));
    NEED("initial_sentinel_size", st.st_size == (off_t)(sizeof(sentinel) - 1));
    p->ready = true;
    return 0;
}

static int resolved(int base, const char *path, int flags)
{
    struct open_how how = { .flags = (uint64_t)(flags | O_CLOEXEC | O_NOFOLLOW),
        .resolve = RESOLVE_IN_ROOT | RESOLVE_NO_MAGICLINKS | RESOLVE_NO_SYMLINKS };
    return (int)syscall(SYS_openat2, base, path, &how, sizeof(how));
}

static int read_sentinel(int fd)
{
    char buf[sizeof(sentinel)];
    ssize_t n;
    STEP("pread_sentinel", n = pread(fd, buf, sizeof(buf), 0));
    NEED("sentinel_data", n == (ssize_t)(sizeof(sentinel) - 1) && memcmp(buf, sentinel, sizeof(sentinel) - 1) == 0);
    return 0;
}

static int attach_fs(struct probe *p, bool pts)
{
    static const char *const tmp_keys[] = { "size", "nr_inodes", "mode" };
    static const char *const tmp_values[] = { "1M", "64", "0700" };
    static const char *const pts_keys[] = { "newinstance", "ptmxmode", "mode", "gid" };
    static const char *const pts_values[] = { NULL, "0666", "0600", "0" };
    int fs, tree, view;
    object = pts ? "pts" : "tmp";
    STEP("fsopen", fs = (int)syscall(SYS_fsopen, pts ? "devpts" : "tmpfs", FSOPEN_CLOEXEC));
    for (int i = 0; i < (pts ? 4 : 3); ++i) {
        const char *key = pts ? pts_keys[i] : tmp_keys[i];
        const char *value = pts ? pts_values[i] : tmp_values[i];
        stage = pts ? (i == 0 ? "fsconfig_newinstance" : i == 1 ? "fsconfig_ptmxmode" :
                      i == 2 ? "fsconfig_mode" : "fsconfig_gid") :
                      (i == 0 ? "fsconfig_size" : i == 1 ? "fsconfig_nr_inodes" : "fsconfig_mode");
        if (syscall(SYS_fsconfig, fs, value ? FSCONFIG_SET_STRING : FSCONFIG_SET_FLAG, key, value, 0) < 0) return -1;
    }
    STEP("fsconfig_create", syscall(SYS_fsconfig, fs, FSCONFIG_CMD_CREATE, NULL, NULL, 0));
    unsigned int attrs = MOUNT_ATTR_NOSUID | MOUNT_ATTR_NOEXEC | (pts ? 0 : MOUNT_ATTR_NODEV);
    STEP("fsmount", tree = (int)syscall(SYS_fsmount, fs, FSMOUNT_CLOEXEC, attrs));
    STEP("move_mount_filesystem", syscall(SYS_move_mount, tree, "", p->a[pts ? PTS : TMP].fd, "",
                                           MOVE_MOUNT_F_EMPTY_PATH | MOVE_MOUNT_T_EMPTY_PATH));
    STEP("openat2_filesystem", view = resolved(p->a[BASE].fd, object, O_RDONLY | O_DIRECTORY));
    struct statfs st;
    STEP("fstatfs_filesystem", fstatfs(view, &st));
    NEED("filesystem_type_flags", st.f_type == (pts ? DEVPTS_SUPER_MAGIC : TMPFS_MAGIC) &&
         (st.f_flags & (ST_NOSUID | ST_NOEXEC | (pts ? 0 : ST_NODEV))) ==
         (long)(ST_NOSUID | ST_NOEXEC | (pts ? 0 : ST_NODEV)));
    STEP("close_filesystem_view", close(view));
    STEP("close_filesystem_tree", close(tree));
    STEP("close_filesystem_context", close(fs));
    return 0;
}

static int child_reanchor(struct probe *p)
{
    int root, base;
    object = "base";
    STEP("open_private_root", root = open("/", DIR_FLAGS));
    char path[sizeof(p->name) + 12];
    (void)snprintf(path, sizeof(path), "data/local/%s", p->name);
    STEP("openat2_private_base", base = resolved(root, path, O_RDONLY | O_DIRECTORY));
    STEP("close_private_root", close(root));
    for (int i = BASE; i <= PTS; ++i) {
        int fd = base;
        struct stat st;
        object = names[i];
        if (i != BASE) STEP("openat2_private_directory", fd = resolved(base, names[i], O_RDONLY | O_DIRECTORY));
        STEP("fstat_private_directory", fstat(fd, &st));
        NEED("private_directory_identity", same_inode(&st, &p->a[i].st) && st.st_uid == 0 &&
             (st.st_mode & 07777) == 0700);
        STEP("close_observer_directory_copy", close(p->a[i].fd));
        p->a[i].fd = fd;
    }
    STEP("close_observer_sentinel_copy", close(p->a[SENTINEL].fd));
    p->a[SENTINEL].fd = -1;
    STEP("close_observer_local_copy", close(p->local));
    STEP("close_observer_data_copy", close(p->data));
    STEP("close_observer_root_copy", close(p->root));
    return 0;
}

static int child_exercise(struct probe *p)
{
    struct stat ns, st;
    int tree, view, fd;
    object = "namespace";
    STEP("unshare_mount_namespace", unshare(CLONE_NEWNS));
    STEP("mount_recursive_private", mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL));
    STEP("stat_child_namespace", stat("/proc/self/ns/mnt", &ns));
    NEED("different_mount_namespace", !same_inode(&ns, &p->ns));
    if (child_reanchor(p) < 0) return -1;
    object = "source";
    STEP("open_tree_clone", tree = (int)syscall(SYS_open_tree, p->a[SOURCE].fd, "",
                                               OPEN_TREE_CLONE | OPEN_TREE_CLOEXEC | AT_EMPTY_PATH));
    struct mount_attr attrs = { .attr_set = MOUNT_ATTR_RDONLY | MOUNT_ATTR_NOSUID | MOUNT_ATTR_NODEV | MOUNT_ATTR_NOEXEC };
    STEP("mount_setattr_recursive", syscall(SYS_mount_setattr, tree, "", AT_EMPTY_PATH | AT_RECURSIVE,
                                            &attrs, sizeof(attrs)));
    object = "view";
    STEP("move_mount_view", syscall(SYS_move_mount, tree, "", p->a[VIEW].fd, "",
                                    MOVE_MOUNT_F_EMPTY_PATH | MOVE_MOUNT_T_EMPTY_PATH));
    STEP("openat2_view", view = resolved(p->a[BASE].fd, "view", O_RDONLY | O_DIRECTORY));
    struct statfs fs;
    STEP("fstatfs_view", fstatfs(view, &fs));
    NEED("view_mount_flags", (fs.f_flags & (ST_RDONLY | ST_NOSUID | ST_NODEV | ST_NOEXEC)) ==
                             (ST_RDONLY | ST_NOSUID | ST_NODEV | ST_NOEXEC));
    STEP("openat2_view_sentinel", fd = resolved(p->a[BASE].fd, "view/sentinel", O_RDONLY));
    STEP("fstat_view_sentinel", fstat(fd, &st));
    NEED("view_sentinel_inode", same_inode(&st, &p->a[SENTINEL].st));
    if (read_sentinel(fd) < 0) return -1;
    STEP("close_read_sentinel", close(fd));
    stage = "openat2_view_write_requires_erofs";
    fd = resolved(p->a[BASE].fd, "view/sentinel", O_WRONLY);
    if (fd >= 0) { (void)close(fd); errno = EPROTO; return -1; }
    if (errno != EROFS) return -1;
    object = "source";
    STEP("openat2_source_write", fd = resolved(p->a[BASE].fd, "source/sentinel", O_WRONLY));
    ssize_t n;
    STEP("pwrite_source_unchanged_data", n = pwrite(fd, sentinel, sizeof(sentinel) - 1, 0));
    NEED("source_write_length", n == (ssize_t)(sizeof(sentinel) - 1));
    STEP("close_source_write", close(fd));
    STEP("close_readonly_view", close(view));
    STEP("close_bind_tree", close(tree));
    if (attach_fs(p, false) < 0 || attach_fs(p, true) < 0) return -1;
    stage = "complete"; object = "none";
    return 0;
}

static int child_prepare(int gate, pid_t parent)
{
    object = "child";
    struct sigaction action = { .sa_handler = SIG_DFL };
    STEP("sigemptyset_child", sigemptyset(&action.sa_mask));
    for (int s = 1; s < NSIG; ++s) {
        if (s == SIGKILL || s == SIGSTOP || (s >= 32 && s < SIGRTMIN)) continue;
        stage = "sigaction_child_reset";
        if (sigaction(s, &action, NULL) < 0) return -1;
    }
    STEP("sigprocmask_child_reset", sigprocmask(SIG_SETMASK, &action.sa_mask, NULL));
    (void)alarm(6);
    STEP("prctl_parent_death_signal", prctl(PR_SET_PDEATHSIG, SIGKILL));
    NEED("observer_still_parent", getppid() == parent);
    struct pollfd pollfd = { .fd = gate, .events = POLLIN };
    STEP("poll_child_gate", poll(&pollfd, 1, 5000));
    char token = 0;
    STEP("read_child_gate", read(gate, &token, 1));
    NEED("child_gate_token", token == 'G');
    STEP("close_child_gate", close(gate));
    return 0;
}

static int supervise(struct probe *p, int64_t deadline, bool *reaped, struct result *r)
{
    int pipefd[2] = {-1, -1}, gate[2] = {-1, -1}, pidfd = -1, status = 0;
    pid_t pid, parent = getpid();
    object = "child";
    STEP("pipe2_result", pipe2(pipefd, O_CLOEXEC | O_NONBLOCK));
    if (pipe2(gate, O_CLOEXEC | O_NONBLOCK) < 0) {
        int e = errno; close(pipefd[0]); close(pipefd[1]); stage = "pipe2_gate"; errno = e; return -1;
    }
    stage = "fork_child";
    pid = fork();
    if (pid == 0) {
        close(pipefd[0]); close(gate[1]);
        if (output_fd >= 0) close(output_fd);
        struct result child;
        int rc = child_prepare(gate[0], parent);
        if (rc == 0) rc = child_exercise(p);
        save_result(&child, rc < 0 ? errno : 0);
        ssize_t n = write(pipefd[1], &child, sizeof(child));
        _exit(n == (ssize_t)sizeof(child) && rc == 0 ? 0 : 3);
    }
    int first_error = pid < 0 ? errno : 0;
    if (pid < 0) save_result(r, first_error);
    close(pipefd[1]); close(gate[0]);
    if (pid > 0) {
        *reaped = false;
        stage = "pidfd_open_child";
        pidfd = (int)syscall(SYS_pidfd_open, pid, 0);
        if (pidfd < 0) { first_error = errno; save_result(r, first_error); }
        if (!first_error) {
            stage = "write_child_gate";
            if (write(gate[1], "G", 1) != 1) { first_error = errno ? errno : EIO; save_result(r, first_error); }
        }
    }
    close(gate[1]);
    bool killed = false;
    while (pid > 0 && !*reaped) {
        stage = "waitpid_child";
        pid_t w = waitpid(pid, &status, WNOHANG);
        if (w == pid) { *reaped = true; break; }
        if (w < 0 && errno != EINTR) { if (!first_error) { first_error = errno; save_result(r, first_error); } break; }
        int64_t now = milliseconds();
        if (now < 0) {
            if (!first_error) { stage = "clock_monotonic_wait"; first_error = errno; save_result(r, first_error); }
            break;
        }
        if (now >= deadline) break;
        if ((first_error || now >= deadline - 500) && !killed && pidfd >= 0) {
            if (!first_error) { stage = "child_deadline"; first_error = ETIMEDOUT; save_result(r, first_error); }
            stage = "pidfd_send_signal_child";
            if (syscall(SYS_pidfd_send_signal, pidfd, SIGKILL, NULL, 0) < 0 && errno != ESRCH) {
                struct result failure; save_result(&failure, errno); event(&failure);
            }
            killed = true;
        }
        int remaining = (int)((killed || pidfd < 0 ? deadline : deadline - 500) - now);
        if (remaining < 1) remaining = 1;
        struct pollfd pf = { .fd = pidfd, .events = POLLIN };
        stage = "poll_child_pidfd";
        if (poll(pidfd >= 0 ? &pf : NULL, pidfd >= 0 ? 1 : 0, pidfd >= 0 ? remaining : 10) < 0 && errno != EINTR) {
            if (!first_error) { first_error = errno; save_result(r, first_error); }
        }
    }
    if (pid > 0 && !*reaped && !first_error) {
        stage = "child_not_reaped"; first_error = ETIMEDOUT; save_result(r, first_error);
    }
    if (pid > 0 && *reaped && !first_error) {
        unsigned char bytes[sizeof(*r) + 1];
        stage = "read_child_result";
        ssize_t n = read(pipefd[0], bytes, sizeof(bytes));
        if (!WIFEXITED(status)) {
            stage = WIFSIGNALED(status) && WTERMSIG(status) == SIGALRM ? "child_alarm" : "child_signal";
            first_error = WIFSIGNALED(status) && WTERMSIG(status) == SIGALRM ? ETIMEDOUT : ECHILD;
            save_result(r, first_error);
        } else if (n != (ssize_t)sizeof(*r)) { first_error = n < 0 ? errno : EPROTO; save_result(r, first_error); }
        else {
            memcpy(r, bytes, sizeof(*r));
            if (r->magic != PACKET_MAGIC || r->error < 0 || r->error > 4095 ||
                !memchr(r->stage, 0, sizeof(r->stage)) || !memchr(r->object, 0, sizeof(r->object))) {
                stage = "child_result_protocol"; first_error = EPROTO; save_result(r, first_error);
            } else if (!WIFEXITED(status) || WEXITSTATUS(status) != (r->error ? 3 : 0)) {
                stage = "child_exit_status"; first_error = WIFSIGNALED(status) && WTERMSIG(status) == SIGALRM ? ETIMEDOUT : EPROTO;
                save_result(r, first_error);
            } else first_error = r->error;
        }
    }
    close(pipefd[0]);
    if (pidfd >= 0) close(pidfd);
    errno = first_error;
    return first_error ? -2 : 0;
}

static int verify_object(struct probe *p, int i)
{
    struct anchor current = { .fd = -1 };
    struct anchor *a = &p->a[i];
    object = names[i];
    NEED("cleanup_known_identity", a->valid);
    STEP("openat_observer_object", current.fd = openat(parent_fd(p, i), entry_name(p, i),
         i == SENTINEL ? O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK : DIR_FLAGS));
    int rc = snapshot(&current), e = errno;
    close(current.fd);
    errno = e;
    if (rc < 0) return -1;
    NEED("observer_identity_mount_unchanged", same_inode(&a->st, &current.st) && current.st.st_uid == 0 &&
         current.st.st_gid == a->st.st_gid && current.st.st_mode == a->st.st_mode &&
         (i != SENTINEL || current.st.st_nlink == 1) && current.mount_id == a->mount_id && current.fs_type == a->fs_type);
    return 0;
}

static int known_contents(struct probe *p, int i)
{
    int fd;
    object = names[i];
    STEP("openat_observer_listing", fd = openat(p->a[i].fd, ".", DIR_FLAGS));
    stage = "fdopendir_observer";
    DIR *dir = fdopendir(fd);
    if (!dir) { int e = errno; close(fd); errno = e; return -1; }
    unsigned int seen = 0;
    int error = 0;
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(dir);
        if (!entry) { error = errno; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        int found = -1;
        for (int j = SOURCE; j < OBJECTS; ++j)
            if (p->a[j].made && (j == SENTINEL ? i == SOURCE : i == BASE) && !strcmp(entry->d_name, names[j])) found = j;
        if (found < 0 || (seen & (1U << found))) { error = EPROTO; break; }
        seen |= 1U << found;
    }
    stage = "readdir_known_contents";
    if (closedir(dir) < 0 && !error) { stage = "closedir_observer"; error = errno; }
    if (error) { errno = error; return -1; }
    unsigned int expected = 0;
    for (int j = SOURCE; j < OBJECTS; ++j)
        if (p->a[j].made && (j == SENTINEL ? i == SOURCE : i == BASE)) expected |= 1U << j;
    NEED("observer_exact_contents", seen == expected);
    return 0;
}

static int cleanup(struct probe *p)
{
    struct stat ns;
    object = "namespace";
    STEP("stat_final_observer_namespace", stat("/proc/self/ns/mnt", &ns));
    NEED("observer_namespace_unchanged", same_inode(&ns, &p->ns));
    for (int i = 0; i < OBJECTS; ++i)
        if (p->a[i].made && verify_object(p, i) < 0) return -1;
    for (int i = BASE; i <= PTS; ++i)
        if (p->a[i].made && known_contents(p, i) < 0) return -1;
    if (p->ready) {
        object = "sentinel";
        if (read_sentinel(p->a[SENTINEL].fd) < 0) return -1;
    }
    for (int i = OBJECTS - 1; i >= BASE; --i) {
        if (!p->a[i].made) continue;
        if (verify_object(p, i) < 0) return -1;
        STEP("unlinkat_known_object", unlinkat(parent_fd(p, i), entry_name(p, i), i == SENTINEL ? 0 : AT_REMOVEDIR));
    }
    return 0;
}

int main(int argc, char **argv)
{
    struct probe p = { .root = -1, .data = -1, .local = -1 };
    for (int i = 0; i < OBJECTS; ++i) p.a[i].fd = -1;
    bool help = argc == 2 && !strcmp(argv[1], "--help");
    bool valid = argc == 3 && !strcmp(argv[1], "--exercise") && strnlen(argv[2], 25) == 24;
    if (valid) for (int i = 0; i < 24; ++i)
        if (!((argv[2][i] >= '0' && argv[2][i] <= '9') || (argv[2][i] >= 'a' && argv[2][i] <= 'f'))) valid = false;
    stage = "fcntl_stdout_duplicate";
    output_fd = fcntl(STDOUT_FILENO, F_DUPFD_CLOEXEC, 3);
    int output_error = output_fd < 0 ? errno : 0;
    if (output_fd >= 0) {
        stage = "fcntl_stdout_get_flags";
        int flags = fcntl(output_fd, F_GETFL);
        if (flags < 0) output_error = errno;
        else {
            stage = "fcntl_stdout_nonblock";
            if (fcntl(output_fd, F_SETFL, flags | O_NONBLOCK) < 0) output_error = errno;
        }
        if (output_error) { close(output_fd); output_fd = -1; }
    }
    const char *output_stage = stage;
    struct sigaction ignore = { .sa_handler = SIG_IGN }, normal = { .sa_handler = SIG_DFL };
    sigemptyset(&ignore.sa_mask); sigemptyset(&normal.sa_mask);
    int signal_error = sigaction(SIGPIPE, &ignore, NULL) < 0 ? errno : 0;
    struct result result;
    if (help || !valid) {
        stage = help ? "help" : "arguments";
        save_result(&result, help ? 0 : EINVAL);
        if (help) output("{\"usage\":[\"mount_probe --help\",\"mount_probe --exercise <24 lowercase hex nonce>\"],\"scope\":\"fixed mount capability only; no chroot, guest, PTY I/O or network test\"}\n");
        final(&p, help ? 0 : 2, help ? "none" : "invalid_arguments", &result, "not_created");
        return help ? 0 : 2;
    }
    if (output_error || signal_error) {
        stage = output_error ? output_stage : "sigaction_parent_pipe";
        save_result(&result, output_error ? output_error : signal_error);
        final(&p, 3, "capability_failed", &result, "not_created");
        return 3;
    }
    (void)snprintf(p.name, sizeof(p.name), "%s%s", PREFIX, argv[2]);
    int64_t start = milliseconds();
    stage = "clock_monotonic";
    int rc = start < 0 ? -1 : 0;
    if (!rc) { stage = "sigaction_parent_chld"; rc = sigaction(SIGCHLD, &normal, NULL); }
    if (!rc) rc = setup(&p);
    bool reaped = true;
    if (!rc) rc = supervise(&p, start + 8000, &reaped, &result);
    if (rc == -1) save_result(&result, errno);
    event(&result);
    int exit_code = rc < 0 ? 3 : 0;
    const char *state = "not_created", *code = exit_code ? "capability_failed" : "none";
    if (p.a[BASE].made) {
        if (!reaped) { stage = "child_not_reaped"; object = "child"; errno = ETIMEDOUT; }
        if (!reaped || cleanup(&p) < 0) {
            save_result(&result, errno); exit_code = 5; code = state = "cleanup_unknown";
        } else state = "removed";
    }
    final(&p, exit_code, code, &result, state);
    for (int i = OBJECTS - 1; i >= 0; --i) if (p.a[i].fd >= 0) close(p.a[i].fd);
    if (p.local >= 0) close(p.local);
    if (p.data >= 0) close(p.data);
    if (p.root >= 0) close(p.root);
    if (output_fd >= 0) close(output_fd);
    return exit_code;
}
