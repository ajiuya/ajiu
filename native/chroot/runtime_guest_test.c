#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <errno.h>
#include <grp.h>
#include <linux/capability.h>
#include <linux/resource.h>
#include <linux/securebits.h>
#include <poll.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static long guest_syscall(long number, ...);
static int guest_prctl(int option, ...);
static int guest_setgroups(size_t count, const gid_t *groups);
static int guest_setresuid(uid_t real, uid_t effective, uid_t saved);
static int guest_setresgid(gid_t real, gid_t effective, gid_t saved);
static int guest_getresuid(uid_t *real, uid_t *effective, uid_t *saved);
static int guest_getresgid(gid_t *real, gid_t *effective, gid_t *saved);
static int guest_getgroups(int size, gid_t *groups);
static pid_t guest_getppid(void);
static int guest_stat(const char *path, struct stat *st);
static ssize_t guest_pread(int fd, void *bytes, size_t size, off_t offset);
static int guest_close(int fd);
static int guest_poll(struct pollfd *fds, nfds_t count, int timeout);
#define main runtime_guest_production_main_not_called
#define syscall guest_syscall
#define prctl guest_prctl
#define setgroups guest_setgroups
#define setresuid guest_setresuid
#define setresgid guest_setresgid
#define getresuid guest_getresuid
#define getresgid guest_getresgid
#define getgroups guest_getgroups
#define getppid guest_getppid
#define stat(path, st) guest_stat(path, st)
#define pread guest_pread
#define close guest_close
#define poll guest_poll
#include "runtime_helper.c"
#undef poll
#undef close
#undef pread
#undef stat
#undef getppid
#undef getgroups
#undef getresgid
#undef getresuid
#undef setresgid
#undef setresuid
#undef setgroups
#undef prctl
#undef syscall
#undef main

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "Root guest mock line %d: %s (errno=%d)\n", __LINE__, #c, errno); exit(1); } ++checks; } while (0)
static unsigned int checks;
static struct {
    uid_t uid[3]; gid_t gid[3], groups[32]; size_t groupc;
    uint64_t caps[5]; int securebits, nnp, keepcaps;
    struct rlimit64 limit;
    bool calls_started, capset_seen, bad_drop, exited, bad_namespace, wrong_start, bad_group;
    bool leader_ns_missing, thread_bad_tgid, thread_bad_group, thread_start_changes, tgid_start_changes;
    int leader_ns_error;
    const char *thread_values;
    unsigned int thread_lists, thread_ns_reads, thread_stat_reads, process_stat_reads;
    unsigned int tid_stat_reads[2], tid_status_reads[2], leader_ns_reads, transient_events;
    bool transient_gone, leader_ns_after_first, namespace_missing_live;
    const char *transient_at;
    int transient_errno;
    pid_t parent, process;
    uint64_t start;
    const char *group;
    struct stat ns;
    const char *failed_call;
    unsigned int opens, closes, signals, ns_reads;
} mock;
static char proc_name[96], proc_value[16384];

static bool fail_call(const char *call)
{
    mock.calls_started = true;
    if (mock.failed_call && !strcmp(call, mock.failed_call)) { errno = EPERM; return true; }
    return false;
}

static void reset_mock(void)
{
    memset(&mock, 0, sizeof(mock));
    mock.caps[0] = mock.caps[1] = mock.caps[3] = (UINT64_C(1) << 41) - 1;
    mock.parent = 222; mock.process = 333; mock.start = 444; mock.group = "ajiu_task_fixture";
    mock.ns.st_dev = 17; mock.ns.st_ino = 888;
}

static int guest_prctl(int option, ...)
{
    va_list args; va_start(args, option);
    int rc = 0;
    if (option == PR_SET_DUMPABLE || option == PR_SET_PDEATHSIG) { if (fail_call("process_prctl")) rc = -1; }
    else if (option == PR_SET_KEEPCAPS) { mock.keepcaps = va_arg(args, int); if (fail_call("keepcaps")) rc = -1; }
    else if (option == PR_SET_SECUREBITS) {
        int bits = va_arg(args, int);
        if (fail_call("securebits")) rc = -1; else mock.securebits = bits;
    } else if (option == PR_GET_SECUREBITS) rc = mock.securebits;
    else if (option == PR_SET_NO_NEW_PRIVS) { mock.nnp = va_arg(args, int); if (fail_call("nnp")) rc = -1; }
    else if (option == PR_GET_NO_NEW_PRIVS) rc = mock.nnp;
    else if (option == PR_CAPBSET_DROP) {
        int cap = va_arg(args, int);
        if (fail_call("bounding")) rc = -1;
        else {
            CHECK(!mock.capset_seen && cap >= 0 && cap < 41);
            if (AJIU_GUEST_CAPS & (UINT64_C(1) << cap)) mock.bad_drop = true;
            mock.caps[3] &= ~(UINT64_C(1) << cap);
        }
    } else if (option == PR_CAPBSET_READ) {
        int cap = va_arg(args, int);
        rc = cap >= 0 && cap < 41 ? (int)((mock.caps[3] >> cap) & 1U) : -1;
        if (rc < 0) errno = EINVAL;
    } else if (option == PR_CAP_AMBIENT) {
        int operation = va_arg(args, int);
        if (operation == PR_CAP_AMBIENT_CLEAR_ALL) mock.caps[4] = 0;
        else { CHECK(operation == PR_CAP_AMBIENT_IS_SET); int cap = va_arg(args, int); rc = (int)((mock.caps[4] >> cap) & 1U); }
    } else { CHECK(false); rc = -1; }
    va_end(args); return rc;
}

static int guest_setgroups(size_t count, const gid_t *groups)
{
    if (fail_call("groups")) return -1;
    CHECK(count <= 32 && (!count || groups));
    if (count) memcpy(mock.groups, groups, count * sizeof(*groups));
    mock.groupc = count; return 0;
}
static int guest_setresuid(uid_t real, uid_t effective, uid_t saved)
{
    if (fail_call("uid")) return -1;
    bool root_before = !mock.uid[0] || !mock.uid[1] || !mock.uid[2];
    mock.uid[0] = real; mock.uid[1] = effective; mock.uid[2] = saved;
    /* Explicit Linux setuid semantics model, not a kernel proof. */
    if (!(mock.securebits & SECBIT_NO_SETUID_FIXUP) && root_before && real && effective && saved) {
        mock.caps[0] = 0;
        if (!mock.keepcaps) mock.caps[1] = 0;
        mock.caps[4] = 0;
    }
    return 0;
}
static int guest_setresgid(gid_t real, gid_t effective, gid_t saved)
{
    if (fail_call("gid")) return -1;
    mock.gid[0] = real; mock.gid[1] = effective; mock.gid[2] = saved; return 0;
}
static int guest_getresuid(uid_t *real, uid_t *effective, uid_t *saved)
{
    *real = mock.uid[0]; *effective = mock.uid[1]; *saved = mock.uid[2]; return 0;
}
static int guest_getresgid(gid_t *real, gid_t *effective, gid_t *saved)
{
    *real = mock.gid[0]; *effective = mock.gid[1]; *saved = mock.gid[2]; return 0;
}
static int guest_getgroups(int size, gid_t *groups)
{
    if (!size) return (int)mock.groupc;
    CHECK(size >= (int)mock.groupc);
    if (mock.groupc) memcpy(groups, mock.groups, mock.groupc * sizeof(*groups));
    return (int)mock.groupc;
}
static pid_t guest_getppid(void) { return mock.parent; }

static void proc_content(const char *name)
{
    proc_value[0] = 0;
    bool first = !strncmp(name, "/proc/334/", 10), second = !strncmp(name, "/proc/335/", 10);
    bool thread = first || second;
    pid_t process = first ? 334 : second ? 335 : mock.process;
    if (strstr(name, "/stat") && !strstr(name, "/status")) {
        if (thread) { ++mock.thread_stat_reads; ++mock.tid_stat_reads[second ? 1 : 0]; } else ++mock.process_stat_reads;
        size_t used = (size_t)snprintf(proc_value, sizeof(proc_value), "%ld (synthetic apt) S", (long)process);
        for (int field = 4; field <= 22; ++field) {
            unsigned long long value = field == 4 ? (unsigned long long)mock.parent :
                field == 22 ? mock.start + (first ? 10U : second ? 20U : 0U) + (mock.wrong_start ? 1U : 0U) +
                    (thread && mock.thread_start_changes ? mock.thread_stat_reads : 0U) +
                    (!thread && mock.tgid_start_changes && mock.process_stat_reads > 1 ? 1U : 0U) : 0;
            used += (size_t)snprintf(proc_value + used, sizeof(proc_value) - used, " %llu", value);
        }
        (void)snprintf(proc_value + used, sizeof(proc_value) - used, "\n");
    } else if (strstr(name, "/status")) {
        if (thread) ++mock.tid_status_reads[second ? 1 : 0];
        (void)snprintf(proc_value, sizeof(proc_value),
            "Tgid:\t%ld\nUid:\t%u %u %u %u\nGid:\t%u %u %u %u\nGroups:\t\n"
            "CapEff:\t%llx\nCapPrm:\t%llx\nCapInh:\t%llx\nCapBnd:\t%llx\nCapAmb:\t%llx\nNoNewPrivs:\t%d\n",
            (long)(thread && mock.thread_bad_tgid ? 999 : mock.process),
            mock.uid[0], mock.uid[1], mock.uid[2], mock.uid[1], mock.gid[0], mock.gid[1], mock.gid[2], mock.gid[1],
            (unsigned long long)mock.caps[0], (unsigned long long)mock.caps[1], (unsigned long long)mock.caps[2],
            (unsigned long long)mock.caps[3], (unsigned long long)mock.caps[4], mock.nnp);
    } else if (strstr(name, "/cgroup")) (void)snprintf(proc_value, sizeof(proc_value), "0::/%s%s\n", mock.group,
        mock.bad_group || (thread && mock.thread_bad_group) ? "-foreign" : "");
    else CHECK(false);
}

static long guest_syscall(long number, ...)
{
    va_list args; va_start(args, number); long rc = 0;
    if (number == SYS_capset || number == SYS_capget) {
        const struct __user_cap_header_struct *header = va_arg(args, const struct __user_cap_header_struct *);
        struct __user_cap_data_struct *caps = va_arg(args, struct __user_cap_data_struct *);
        CHECK(header->version == _LINUX_CAPABILITY_VERSION_3);
        if (number == SYS_capset) {
            if (fail_call("capset")) rc = -1;
            else {
                mock.capset_seen = true;
                mock.caps[0] = ((uint64_t)caps[1].effective << 32) | caps[0].effective;
                mock.caps[1] = ((uint64_t)caps[1].permitted << 32) | caps[0].permitted;
                mock.caps[2] = ((uint64_t)caps[1].inheritable << 32) | caps[0].inheritable;
            }
        } else {
            memset(caps, 0, 2 * sizeof(*caps));
            caps[0].effective = (uint32_t)mock.caps[0]; caps[1].effective = (uint32_t)(mock.caps[0] >> 32);
            caps[0].permitted = (uint32_t)mock.caps[1]; caps[1].permitted = (uint32_t)(mock.caps[1] >> 32);
            caps[0].inheritable = (uint32_t)mock.caps[2]; caps[1].inheritable = (uint32_t)(mock.caps[2] >> 32);
        }
    } else if (number == SYS_prlimit64) {
        CHECK(va_arg(args, int) == 0 && va_arg(args, int) == RLIMIT_NPROC);
        const struct rlimit64 *limit = va_arg(args, const struct rlimit64 *);
        struct rlimit64 *old = va_arg(args, struct rlimit64 *);
        if (limit) mock.limit = *limit;
        if (old) *old = mock.limit;
    } else if (number == SYS_openat2) {
        int parent = va_arg(args, int);
        const char *name = va_arg(args, const char *);
        const struct open_how *how = va_arg(args, const struct open_how *);
        CHECK(va_arg(args, size_t) == sizeof(*how));
        if (parent == 1000) {
            CHECK(mock.leader_ns_missing && !strcmp(name, "cgroup.threads") &&
                how->resolve == (RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS));
            ++mock.thread_lists; rc = 902;
        } else {
            CHECK(parent == AT_FDCWD && how->resolve == (RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS));
            CHECK(strncmp(name, "/proc/333/", 10) == 0 || (mock.leader_ns_missing &&
                (!strncmp(name, "/proc/334/", 10) || !strncmp(name, "/proc/335/", 10))));
            bool first = !strncmp(name, "/proc/334/", 10);
            bool stat_file = strstr(name, "/stat") && !strstr(name, "/status");
            bool trigger = first && mock.transient_at &&
                ((!strcmp(mock.transient_at, "cgroup") && strstr(name, "/cgroup")) ||
                 (!strcmp(mock.transient_at, "identity_end") && stat_file && mock.tid_stat_reads[0] == 1) ||
                 (!strcmp(mock.transient_at, "second_identity") && stat_file && mock.tid_stat_reads[0] >= 2));
            if (first && (mock.transient_gone || trigger)) {
                int error = mock.transient_errno ? mock.transient_errno : ENOENT;
                if (trigger && !mock.transient_gone) ++mock.transient_events;
                mock.transient_gone = error == ENOENT || error == ESRCH;
                errno = error; rc = -1;
            } else { CHECK(strlen(name) < sizeof(proc_name)); strcpy(proc_name, name); proc_content(name); rc = 900; }
        }
    } else if (number == SYS_pidfd_open) {
        CHECK(va_arg(args, int) == mock.process && va_arg(args, int) == 0); ++mock.opens; rc = 901;
    } else if (number == SYS_pidfd_send_signal) {
        CHECK(va_arg(args, int) == 901); (void)va_arg(args, int); ++mock.signals;
    } else { CHECK(false); rc = -1; }
    va_end(args); return rc;
}
static int guest_stat(const char *path, struct stat *st)
{
    bool leader = !strcmp(path, "/proc/333/ns/mnt");
    bool first = !strcmp(path, "/proc/334/ns/mnt"), second = !strcmp(path, "/proc/335/ns/mnt");
    CHECK(leader || (mock.leader_ns_missing && (first || second))); ++mock.ns_reads;
    if (leader) {
        ++mock.leader_ns_reads;
        if (mock.leader_ns_after_first && mock.leader_ns_reads >= 2) mock.leader_ns_missing = true;
        if (mock.leader_ns_missing) { errno = mock.leader_ns_error ? mock.leader_ns_error : ENOENT; return -1; }
    }
    if (first && (mock.transient_gone || (mock.transient_at && !strcmp(mock.transient_at, "namespace")))) {
        int error = mock.transient_errno ? mock.transient_errno : ENOENT;
        if (!mock.transient_gone) ++mock.transient_events;
        mock.transient_gone = !mock.namespace_missing_live && (error == ENOENT || error == ESRCH);
        errno = error; return -1;
    }
    if (!leader) ++mock.thread_ns_reads;
    *st = mock.ns; if (mock.bad_namespace) ++st->st_ino; return 0;
}
static ssize_t guest_pread(int fd, void *bytes, size_t size, off_t offset)
{
    CHECK((fd == 900 || (mock.leader_ns_missing && fd == 902)) && offset >= 0);
    if (fd == 902 && offset == 0) ++mock.thread_lists;
    const char *value = fd == 902 ? (mock.thread_values ? mock.thread_values : "334\n") : proc_value;
    size_t length = strlen(value), begin = (size_t)offset;
    if (begin >= length) return 0;
    if (size > length - begin) size = length - begin;
    memcpy(bytes, value + begin, size); return (ssize_t)size;
}
static int guest_close(int fd) { CHECK(fd == 900 || fd == 901 || fd == 902); if (fd == 901) ++mock.closes; return 0; }
static int guest_poll(struct pollfd *fds, nfds_t count, int timeout)
{
    CHECK(count == 1 && fds[0].fd == 901 && timeout == 0);
    fds[0].revents = mock.exited ? POLLIN : 0; return mock.exited ? 1 : 0;
}

static struct identity mock_identity(void)
{
    struct identity id = { .start = mock.start, .parent = mock.parent, .tgid = mock.process, .nnp = mock.nnp };
    for (size_t i = 0; i < 4; ++i) { id.uid[i] = mock.uid[i < 3 ? i : 1]; id.gid[i] = mock.gid[i < 3 ? i : 1]; }
    id.groupc = (uint32_t)mock.groupc;
    for (uint32_t i = 0; i < id.groupc; ++i) id.groups[i] = (uint32_t)mock.groups[i];
    memcpy(id.caps, mock.caps, sizeof(id.caps)); return id;
}
static void model_ordinary_exec(void)
{
    /* Linux root compatibility is bounded; no ambient/inheritable grants and NNP never increases P. */
    uint64_t candidate = (!mock.uid[0] || !mock.uid[1]) && !(mock.securebits & SECBIT_NOROOT) ? mock.caps[3] : mock.caps[4];
    if (mock.nnp) candidate &= mock.caps[1];
    mock.caps[1] = candidate; mock.caps[0] = candidate;
}

static void check_guest_setup_exec_and_drop(void)
{
    struct ajiu_launch launch = { .uid = 10445, .gid = 10445 };
    struct runtime runtime = { .launch = &launch, .cap_last = 40 };
    reset_mock();
    CHECK(AJIU_GUEST_CAPS == UINT64_C(0x800000fb));
    CHECK(guest_credentials(&runtime, mock.parent) == 0);
    CHECK(!mock.bad_drop && ajiu_guest_initial_caps(mock.caps) && mock.groupc == 0);
    CHECK(mock.securebits == AJIU_GUEST_SECUREBITS && !mock.keepcaps && mock.nnp == 1);
    CHECK(!(mock.caps[0] & ((UINT64_C(1) << CAP_SYS_ADMIN) | (UINT64_C(1) << CAP_SYS_CHROOT) |
        (UINT64_C(1) << CAP_MKNOD) | (UINT64_C(1) << CAP_SYS_PTRACE) | (UINT64_C(1) << CAP_SETPCAP))));
    for (unsigned int exec = 0; exec < 3; ++exec) {
        model_ordinary_exec(); struct identity id = mock_identity(); CHECK(guest_initial_identity(&runtime, &id));
        CHECK(mock.caps[0] & (UINT64_C(1) << CAP_CHOWN));
        CHECK(mock.caps[0] & (UINT64_C(1) << CAP_SETFCAP));
    }
    CHECK(guest_setgroups(0, NULL) == 0 && guest_setresgid(42,42,42) == 0 && guest_setresuid(42,42,42) == 0);
    CHECK(!mock.caps[0] && !mock.caps[1] && !mock.caps[4]);
    model_ordinary_exec(); struct identity apt = mock_identity();
    CHECK(guest_identity(&apt) && !guest_initial_identity(&runtime, &apt));
    CHECK(!apt.caps[0] && !apt.caps[1] && apt.caps[3] == AJIU_GUEST_CAPS);
    /* Even a filecap exec candidate cannot add P with NNP; nosuid also suppresses that candidate.
     * This is an explicit policy model, not a setxattr/execve kernel probe. */
    uint64_t filecap_candidate = (UINT64_C(1) << CAP_CHOWN) & mock.caps[3];
    if (mock.nnp) filecap_candidate &= mock.caps[1];
    CHECK(filecap_candidate == 0);
    apt.caps[0] = apt.caps[1] = UINT64_C(1) << CAP_CHOWN; CHECK(!guest_identity(&apt));
    reset_mock(); launch.group_count = 2; launch.groups[0] = 3003; launch.groups[1] = 9997;
    CHECK(guest_credentials(&runtime, mock.parent) == 0 && mock.groupc == 1 && mock.groups[0] == 3003);
    model_ordinary_exec(); struct identity network = mock_identity(); CHECK(guest_initial_identity(&runtime, &network));
    network.groups[0] = 9997; CHECK(!guest_initial_identity(&runtime, &network));
    CHECK(guest_setgroups(0, NULL) == 0 && guest_setresgid(42,42,42) == 0 && guest_setresuid(42,42,42) == 0);
    model_ordinary_exec(); network = mock_identity(); CHECK(guest_identity(&network) && !network.groupc && !network.caps[0] && !network.caps[1]);
    launch.group_count = 0;
    const char *const failures[] = { "groups", "uid", "gid", "securebits", "bounding", "capset", "nnp" };
    for (size_t i = 0; i < sizeof(failures) / sizeof(*failures); ++i) {
        reset_mock(); mock.failed_call = failures[i]; CHECK(guest_credentials(&runtime, mock.parent) == -1);
    }
    reset_mock(); runtime.cap_last = CAP_SETFCAP - 1; CHECK(guest_credentials(&runtime, mock.parent) == -1 && !mock.calls_started);
    runtime.cap_last = 40;
    reset_mock(); CHECK(drop_identity(&runtime, mock.parent) == 0);
    CHECK(mock.uid[0] == 10445 && mock.uid[1] == 10445 && mock.uid[2] == 10445);
    for (size_t i = 0; i < 5; ++i) CHECK(mock.caps[i] == 0);
    struct identity opener = mock_identity(); CHECK(app_identity(&runtime, &opener));
    opener.uid[1] = 0; CHECK(!app_identity(&runtime, &opener));
}

static void check_membership_snapshot(void)
{
    struct ajiu_launch launch = { .uid = 10445, .gid = 10445 };
    struct shared shared = {0};
    struct runtime runtime = { .launch = &launch, .cap_last = 40, .shared = &shared };
    (void)snprintf(runtime.group_name, sizeof(runtime.group_name), "%s", "ajiu_task_fixture");
    reset_mock(); CHECK(guest_credentials(&runtime, mock.parent) == 0);
    shared.guest_ns_dev = mock.ns.st_dev; shared.guest_ns_ino = mock.ns.st_ino;
    store(&shared.guest_ns_ready, 1); store(&shared.dropped, 1);
    struct identity id;
    CHECK(member_snapshot(&runtime, mock.process, mock.start, false, &id) == 0);
    CHECK(track(&runtime, mock.process, mock.start, false) == 0 && runtime.registry.count == 1);
    CHECK(guest_setresgid(42,42,42) == 0 && guest_setresuid(42,42,42) == 0);
    CHECK(member_snapshot(&runtime, mock.process, mock.start, false, &id) == 0 && id.uid[0] == 42);
    mock.bad_namespace = true; CHECK(member_snapshot(&runtime, mock.process, mock.start, false, &id) == -1); mock.bad_namespace = false;
    mock.wrong_start = true; CHECK(member_snapshot(&runtime, mock.process, mock.start, false, &id) == -1); mock.wrong_start = false;
    mock.bad_group = true; CHECK(member_snapshot(&runtime, mock.process, mock.start, false, &id) == -1); mock.bad_group = false;
    store(&shared.guest_ns_ready, 0); CHECK(member_snapshot(&runtime, mock.process, mock.start, false, &id) == -1); store(&shared.guest_ns_ready, 1);
    mock.caps[0] = UINT64_C(1) << CAP_SYS_ADMIN;
    CHECK(member_snapshot(&runtime, mock.process, mock.start, false, &id) == -1); mock.caps[0] = 0;
    for (size_t i = 0; i < runtime.registry.count; ++i) guest_close(runtime.registry.tasks[i].fd);
    free(runtime.registry.tasks); runtime.registry = (struct registry){0};
    mock.bad_namespace = true;
    CHECK(track(&runtime, mock.process, mock.start, false) == -1 && runtime.registry.count == 0);
    CHECK(mock.opens == mock.closes && mock.signals == 0 && mock.ns_reads > 0);
    CHECK(guest_manifest_version(AJIU_MANIFEST_V1) == -1 && errno == ENOTSUP && !strcmp(stage, "legacy_readonly_instance"));
    CHECK(guest_manifest_version(AJIU_MANIFEST_V2) == 0);
    CHECK(guest_manifest_version(UINT64_C(0x414a494d00000003)) == -1 && errno == ESTALE);
}

static void check_review_regression_dead_leader_namespace(void)
{
    /* Controlled /proc + cgroup.threads model only; no real pthread/Root/pidfd/kill is run.
     * TGID pidfd is still live after its leader exits. TID 334 is the bounded, same-cgroup
     * member with Tgid=333, distinct stable startticks and the original guest mntns. */
    struct ajiu_launch launch = { .uid = 10445, .gid = 10445 };
    struct shared shared = {0};
    struct runtime runtime = { .launch = &launch, .cap_last = 40, .shared = &shared };
    runtime.a[GROUP].fd = 1000;
    runtime.a[THREADS].fd = 902; runtime.a[THREADS].valid = true;
    (void)snprintf(runtime.group_name, sizeof(runtime.group_name), "%s", "ajiu_task_fixture");
    reset_mock(); CHECK(guest_credentials(&runtime, mock.parent) == 0);
    shared.guest_ns_dev = mock.ns.st_dev; shared.guest_ns_ino = mock.ns.st_ino;
    store(&shared.guest_ns_ready, 1); store(&shared.dropped, 1);
    mock.leader_ns_missing = true;
    CHECK(pidfd_dead(901) == 0);
    uint64_t original_start; pid_t original_parent;
    CHECK(proc_start(mock.process, &original_start, &original_parent) == 0 && original_start == mock.start);
    struct identity id;
    /* Old member_snapshot rejects ENOENT before looking for a live same-group TID. */
    CHECK(member_snapshot(&runtime, mock.process, original_start, false, &id) == 0);
    CHECK(mock.thread_lists > 0 && mock.thread_ns_reads > 0 && !load(&shared.unsafe));
    CHECK(id.start == original_start && pidfd_dead(901) == 0);
    struct task task = { .pid = mock.process, .start = original_start, .fd = 901 };
    CHECK(signal_task(&runtime, &task, SIGTERM) == 0 && mock.signals == 1 && !load(&shared.unsafe));
    mock.thread_bad_tgid = true;
    CHECK(member_snapshot(&runtime, mock.process, original_start, false, &id) == -1); mock.thread_bad_tgid = false;
    mock.thread_bad_group = true;
    CHECK(member_snapshot(&runtime, mock.process, original_start, false, &id) == -1); mock.thread_bad_group = false;
    mock.bad_namespace = true;
    CHECK(member_snapshot(&runtime, mock.process, original_start, false, &id) == -1); mock.bad_namespace = false;
    mock.thread_start_changes = true;
    CHECK(member_snapshot(&runtime, mock.process, original_start, false, &id) == -1); mock.thread_start_changes = false;
    mock.process_stat_reads = 0; mock.tgid_start_changes = true;
    CHECK(member_snapshot(&runtime, mock.process, original_start, false, &id) == -1); mock.tgid_start_changes = false;
    const char *const malformed[] = { "", "0\n", "-1\n", "334", "334\n334\n", "2147483648\n" };
    for (size_t i = 0; i < sizeof(malformed) / sizeof(*malformed); ++i) {
        mock.thread_values = malformed[i]; CHECK(member_snapshot(&runtime, mock.process, original_start, false, &id) == -1);
    }
    char *overbudget = malloc(MAX_TASKS * 12U + 2); CHECK(overbudget != NULL);
    memset(overbudget, '1', MAX_TASKS * 12U + 1); overbudget[MAX_TASKS * 12U + 1] = 0;
    mock.thread_values = overbudget; CHECK(member_snapshot(&runtime, mock.process, original_start, false, &id) == -1); free(overbudget);
    mock.thread_values = NULL;
    mock.leader_ns_error = EACCES; unsigned int lists = mock.thread_lists;
    CHECK(member_snapshot(&runtime, mock.process, original_start, false, &id) == -1 && mock.thread_lists == lists); mock.leader_ns_error = 0;
    runtime.a[THREADS].valid = false;
    CHECK(member_snapshot(&runtime, mock.process, original_start, false, &id) == -1); runtime.a[THREADS].valid = true;
    mock.exited = true;
    CHECK(member_snapshot(&runtime, mock.process, original_start, false, &id) == -1); mock.exited = false;
    mock.thread_bad_group = true;
    CHECK(signal_task(&runtime, &task, SIGKILL) == -1 && mock.signals == 1 && load(&shared.unsafe));
}

static void check_review_regression_transient_thread_exit(void)
{
    /* A bounded two-TID lifecycle model, not a real pthread/kernel stop test. TID 335 remains
     * valid throughout; TID 334 naturally exits at a precise read after its first stat succeeds. */
    const char *const windows[] = { "cgroup", "namespace", "second_identity", "identity_end" };
    const int errors[] = { ENOENT, ESRCH };
    for (size_t window = 0; window < sizeof(windows) / sizeof(*windows); ++window)
        for (unsigned int order = 0; order < 2; ++order)
            for (size_t error = 0; error < sizeof(errors) / sizeof(*errors); ++error) {
                struct ajiu_launch launch = { .uid = 10445, .gid = 10445 };
                struct shared shared = {0};
                struct runtime runtime = { .launch = &launch, .cap_last = 40, .shared = &shared };
                runtime.a[THREADS].fd = 902; runtime.a[THREADS].valid = true;
                (void)snprintf(runtime.group_name, sizeof(runtime.group_name), "%s", "ajiu_task_fixture");
                reset_mock(); CHECK(guest_credentials(&runtime, mock.parent) == 0);
                shared.guest_ns_dev = mock.ns.st_dev; shared.guest_ns_ino = mock.ns.st_ino;
                store(&shared.guest_ns_ready, 1); store(&shared.dropped, 1);
                mock.leader_ns_missing = true; mock.transient_at = windows[window]; mock.transient_errno = errors[error];
                mock.thread_values = order ? "335\n334\n" : "334\n335\n";
                struct identity id;
                CHECK(member_snapshot(&runtime, mock.process, mock.start, false, &id) == 0);
                CHECK(mock.transient_events == 1 && mock.transient_gone && mock.tid_status_reads[1] > 0);
                CHECK(id.tgid == mock.process && id.start == mock.start && !load(&shared.unsafe) && pidfd_dead(901) == 0);
                struct task task = { .pid = mock.process, .fd = 901, .start = mock.start };
                CHECK(signal_task(&runtime, &task, SIGTERM) == 0 && mock.signals == 1 && !load(&shared.unsafe));
            }
    /* The leader can disappear between the two namespace samples, not only before sample one. */
    for (size_t error = 0; error < sizeof(errors) / sizeof(*errors); ++error) {
        struct ajiu_launch launch = { .uid = 10445, .gid = 10445 };
        struct shared shared = {0};
        struct runtime runtime = { .launch = &launch, .cap_last = 40, .shared = &shared };
        runtime.a[THREADS].fd = 902; runtime.a[THREADS].valid = true;
        (void)snprintf(runtime.group_name, sizeof(runtime.group_name), "%s", "ajiu_task_fixture");
        reset_mock(); CHECK(guest_credentials(&runtime, mock.parent) == 0);
        shared.guest_ns_dev = mock.ns.st_dev; shared.guest_ns_ino = mock.ns.st_ino;
        store(&shared.guest_ns_ready, 1); store(&shared.dropped, 1);
        mock.leader_ns_after_first = true; mock.leader_ns_error = errors[error]; mock.thread_values = "334\n335\n";
        struct identity id;
        CHECK(member_snapshot(&runtime, mock.process, mock.start, false, &id) == 0);
        CHECK(mock.leader_ns_reads >= 2 && mock.thread_lists > 0 && mock.thread_ns_reads > 0 && !load(&shared.unsafe));
    }
    /* No stable witness after bounded disappearance scans must stay UNKNOWN, not become success. */
    {
        struct ajiu_launch launch = { .uid = 10445, .gid = 10445 };
        struct shared shared = {0};
        struct runtime runtime = { .launch = &launch, .cap_last = 40, .shared = &shared };
        runtime.a[THREADS].fd = 902; runtime.a[THREADS].valid = true;
        (void)snprintf(runtime.group_name, sizeof(runtime.group_name), "%s", "ajiu_task_fixture");
        reset_mock(); CHECK(guest_credentials(&runtime, mock.parent) == 0);
        shared.guest_ns_dev = mock.ns.st_dev; shared.guest_ns_ino = mock.ns.st_ino;
        store(&shared.guest_ns_ready, 1); store(&shared.dropped, 1);
        mock.leader_ns_missing = true; mock.transient_at = "cgroup"; mock.thread_values = "334\n";
        struct identity id;
        CHECK(member_snapshot(&runtime, mock.process, mock.start, false, &id) == -1 && errno == EAGAIN);
        CHECK(mock.thread_lists == 3 && mock.transient_gone);
        struct task task = { .pid = mock.process, .fd = 901, .start = mock.start };
        CHECK(signal_task(&runtime, &task, SIGTERM) == -1 && mock.signals == 0 && load(&shared.unsafe));
    }
    /* A live but wrong/unreadable candidate must never be treated as a naturally vanished TID. */
    for (unsigned int bad = 0; bad < 7; ++bad) {
        struct ajiu_launch launch = { .uid = 10445, .gid = 10445 };
        struct shared shared = {0};
        struct runtime runtime = { .launch = &launch, .cap_last = 40, .shared = &shared };
        runtime.a[THREADS].fd = 902; runtime.a[THREADS].valid = true;
        (void)snprintf(runtime.group_name, sizeof(runtime.group_name), "%s", "ajiu_task_fixture");
        reset_mock(); CHECK(guest_credentials(&runtime, mock.parent) == 0);
        shared.guest_ns_dev = mock.ns.st_dev; shared.guest_ns_ino = mock.ns.st_ino;
        store(&shared.guest_ns_ready, 1); store(&shared.dropped, 1);
        mock.leader_ns_missing = true; mock.thread_values = "334\n335\n";
        if (bad == 0) mock.thread_bad_group = true;
        else if (bad == 1) mock.bad_namespace = true;
        else if (bad == 2) mock.thread_bad_tgid = true;
        else if (bad == 3) mock.caps[0] |= UINT64_C(1) << CAP_SYS_ADMIN;
        else if (bad == 4) mock.thread_start_changes = true;
        else if (bad == 5) { mock.transient_at = "cgroup"; mock.transient_errno = EACCES; }
        else { mock.transient_at = "namespace"; mock.namespace_missing_live = true; }
        struct identity id;
        CHECK(member_snapshot(&runtime, mock.process, mock.start, false, &id) == -1 && mock.signals == 0);
        if (bad == 5 || bad == 6) CHECK(!mock.transient_gone);
    }
}

int main(int argc, char **argv)
{
    if (argc == 2) {
        if (!strcmp(argv[1], "reviewRegressionTransientThreadExit")) check_review_regression_transient_thread_exit();
        else { CHECK(!strcmp(argv[1], "reviewRegressionDeadLeaderNamespace")); check_review_regression_dead_leader_namespace(); }
        printf("Dead leader namespace checks: %u; proc/cgroup.threads bounded model, not real kernel thread/stop proof.\n", checks);
        return 0;
    }
    CHECK(argc == 1);
    check_guest_setup_exec_and_drop();
    check_membership_snapshot();
    check_review_regression_dead_leader_namespace();
    check_review_regression_transient_thread_exit();
    printf("Root guest checks: %u; controlled capability/UID/proc/namespace mocks and explicit exec/setuid semantics model only. No production main, Root, exec, chroot, mount, device or kernel capability proof.\n", checks);
    return 0;
}
