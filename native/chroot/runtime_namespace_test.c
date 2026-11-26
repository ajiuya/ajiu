#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/mount.h>
#include <linux/openat2.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static int namespace_open(const char *path, int flags, ...);
static int namespace_close(int fd);
static int namespace_fstat(int fd, struct stat *st);
static int namespace_fcntl(int fd, int command, ...);
static ssize_t namespace_pread(int fd, void *data, size_t size, off_t offset);
static ssize_t namespace_readlink(const char *path, char *data, size_t size);
static long namespace_syscall(long number, ...);
#define main chroot_runtime_production_main_not_called
#define open namespace_open
#define close namespace_close
#define fstat namespace_fstat
#define fcntl namespace_fcntl
#define pread namespace_pread
#define readlink namespace_readlink
#define syscall namespace_syscall
#include "runtime_helper.c"
#undef syscall
#undef readlink
#undef pread
#undef fcntl
#undef fstat
#undef close
#undef open
#undef main

#define CHECK(value) do { if (!(value)) { fprintf(stderr, "Native namespace mock check line %d\n", __LINE__); exit(1); } ++checks; } while (0)
static unsigned int checks;
enum { N_ROOT, N_DATA, N_APP, N_SESSION, N_PACKAGE, N_LIB, N_NATIVE, N_RUNTIME,
    N_WORKER, N_RUNNING, N_DIRECTORY_SOURCE, N_FILE_SOURCE, N_TREE, NODES };
struct mock_fd { bool issued, live; unsigned int node, namespace_id; struct stat st; };
static struct mock_fd descriptors[256];
static struct stat nodes[NODES];
static int next_fd, fail_open, fail_stat, fail_pread;
static unsigned int current_namespace, open_calls, stat_calls, pread_calls, root_opens, worker_opens;
static bool fail_duplicate, invalid_worker_elf, invalid_runtime_elf;
static bool fail_attributes, fail_move;
static unsigned int cloned, moved, last_tree;
static struct mount_attr last_attributes;
static char executable_path[256];

static unsigned int live_fds(void)
{
    unsigned int count = 0;
    for (size_t i = 0; i < sizeof(descriptors) / sizeof(*descriptors); ++i)
        if (descriptors[i].live) ++count;
    return count;
}

static struct mock_fd *descriptor(int fd)
{
    CHECK(fd >= 0 && (size_t)fd < sizeof(descriptors) / sizeof(*descriptors));
    CHECK(descriptors[fd].issued && descriptors[fd].live);
    return &descriptors[fd];
}

static int allocate_fd(unsigned int node, unsigned int namespace_id)
{
    CHECK(node < NODES && next_fd < (int)(sizeof(descriptors) / sizeof(*descriptors)));
    int fd = next_fd++;
    descriptors[fd] = (struct mock_fd){ .issued = true, .live = true, .node = node,
        .namespace_id = namespace_id, .st = nodes[node] };
    return fd;
}

static void reset_world(void)
{
    CHECK(live_fds() == 0);
    memset(descriptors, 0, sizeof(descriptors));
    for (unsigned int i = 0; i < NODES; ++i)
        nodes[i] = (struct stat){ .st_dev = 17, .st_ino = 100 + i, .st_mode = S_IFDIR | 0755,
            .st_uid = 0, .st_gid = 0, .st_nlink = 1 };
    nodes[N_RUNTIME].st_mode = nodes[N_WORKER].st_mode = S_IFREG | 0555;
    nodes[N_RUNTIME].st_size = nodes[N_WORKER].st_size = sizeof(Elf64_Ehdr) + sizeof(Elf64_Phdr);
    nodes[N_RUNNING] = nodes[N_RUNTIME];
    nodes[N_DIRECTORY_SOURCE] = (struct stat){ .st_dev = 21, .st_ino = 9001, .st_mode = S_IFDIR | 0700,
        .st_uid = 10123, .st_gid = 10123 };
    nodes[N_FILE_SOURCE] = (struct stat){ .st_dev = 22, .st_ino = 9002, .st_mode = S_IFREG | 0600,
        .st_uid = 10123, .st_gid = 10123 };
    next_fd = 20;
    current_namespace = 2;
    open_calls = stat_calls = pread_calls = root_opens = worker_opens = 0;
    fail_open = fail_stat = fail_pread = 0;
    fail_duplicate = invalid_worker_elf = invalid_runtime_elf = false;
    fail_attributes = fail_move = false;
    cloned = moved = last_tree = 0;
    memset(&last_attributes, 0, sizeof(last_attributes));
    (void)snprintf(executable_path, sizeof(executable_path),
        "/data/app/~~session/com.ajiu.debug-install/lib/arm64/libajiu_chroot_runtime.so");
}

static bool opening_fails(void)
{
    ++open_calls;
    if (fail_open > 0 && open_calls == (unsigned int)fail_open) { errno = ENOENT; return true; }
    return false;
}

static int namespace_open(const char *path, int flags, ...)
{
    CHECK((flags & (O_CLOEXEC | O_NONBLOCK)) == (O_CLOEXEC | O_NONBLOCK));
    if (opening_fails()) return -1;
    if (!strcmp(path, "/")) {
        CHECK(flags == DF);
        ++root_opens;
        return allocate_fd(N_ROOT, current_namespace);
    }
    CHECK(!strcmp(path, "/proc/self/exe"));
    return allocate_fd(N_RUNNING, current_namespace);
}

static int namespace_close(int fd)
{
    descriptor(fd)->live = false;
    return 0;
}

static int namespace_fstat(int fd, struct stat *st)
{
    struct mock_fd *entry = descriptor(fd);
    ++stat_calls;
    if (fail_stat > 0 && stat_calls == (unsigned int)fail_stat) { errno = EACCES; return -1; }
    *st = entry->st;
    return 0;
}

static int namespace_fcntl(int fd, int command, ...)
{
    struct mock_fd *entry = descriptor(fd);
    va_list args;
    va_start(args, command);
    CHECK(command == F_DUPFD_CLOEXEC && va_arg(args, int) == 3);
    va_end(args);
    if (fail_duplicate) { errno = EMFILE; return -1; }
    int copy = allocate_fd(entry->node, entry->namespace_id);
    descriptors[copy].st = entry->st;
    return copy;
}

static ssize_t namespace_readlink(const char *path, char *data, size_t size)
{
    CHECK(!strcmp(path, "/proc/self/exe"));
    size_t length = strlen(executable_path);
    CHECK(length < size);
    memcpy(data, executable_path, length);
    return (ssize_t)length;
}

static ssize_t namespace_pread(int fd, void *data, size_t size, off_t offset)
{
    unsigned int node = descriptor(fd)->node;
    CHECK(node == N_RUNTIME || node == N_WORKER);
    ++pread_calls;
    if (fail_pread > 0 && pread_calls == (unsigned int)fail_pread) { errno = EIO; return -1; }
    if (!offset) {
        CHECK(size == sizeof(Elf64_Ehdr));
        Elf64_Ehdr elf = { .e_type = ET_DYN, .e_machine = EM_AARCH64, .e_phnum = 1,
            .e_phentsize = sizeof(Elf64_Phdr), .e_phoff = sizeof(Elf64_Ehdr) };
        memcpy(elf.e_ident, ELFMAG, SELFMAG);
        elf.e_ident[EI_CLASS] = ELFCLASS64;
        elf.e_ident[EI_DATA] = ELFDATA2LSB;
        memcpy(data, &elf, size);
    } else {
        CHECK(offset == sizeof(Elf64_Ehdr) && size == sizeof(Elf64_Phdr));
        bool invalid = node == N_WORKER ? invalid_worker_elf : invalid_runtime_elf;
        Elf64_Phdr ph = { .p_type = invalid ? PT_INTERP : PT_LOAD };
        memcpy(data, &ph, size);
    }
    return (ssize_t)size;
}

static long namespace_syscall(long number, ...)
{
    va_list args;
    va_start(args, number);
    long rc = -1;
    if (number == SYS_openat2) {
        int dir = va_arg(args, int);
        const char *path = va_arg(args, const char *);
        const struct open_how *how = va_arg(args, const struct open_how *);
        CHECK(va_arg(args, size_t) == sizeof(*how));
        CHECK((how->flags & (O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK)) == (O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK));
        CHECK((how->resolve & (RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS)) ==
            (RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS));
        unsigned int node;
        if (dir == AT_FDCWD) {
            CHECK(how->resolve == (RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS));
            if (!strcmp(path, "/storage/emulated/0/source-directory")) {
                CHECK(how->flags & O_DIRECTORY);
                node = N_DIRECTORY_SOURCE;
            } else {
                CHECK(!strcmp(path, "/storage/emulated/0/source-file"));
                CHECK(!(how->flags & O_DIRECTORY));
                node = N_FILE_SOURCE;
            }
        } else {
            struct mock_fd *parent = descriptor(dir);
            CHECK(parent->namespace_id == current_namespace);
            CHECK(how->resolve == (RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS));
            static const char *const components[] = { "data", "app", "~~session", "com.ajiu.debug-install", "lib", "arm64" };
            if (parent->node < N_NATIVE) {
                bool package = parent->node == N_SESSION && !strcmp(path, "com.ajiu.beta-install");
                CHECK(package || !strcmp(path, components[parent->node]));
                CHECK(how->flags & O_DIRECTORY);
                node = parent->node + 1;
            } else {
                CHECK(parent->node == N_NATIVE && !(how->flags & O_DIRECTORY));
                if (!strcmp(path, "libajiu_chroot_runtime.so")) node = N_RUNTIME;
                else {
                    CHECK(!strcmp(path, "libajiu_file_worker.so"));
                    ++worker_opens;
                    node = N_WORKER;
                }
            }
        }
        if (!opening_fails()) rc = allocate_fd(node, current_namespace);
    } else if (number == SYS_open_tree) {
        struct mock_fd *source = descriptor(va_arg(args, int));
        CHECK(!strcmp(va_arg(args, const char *), ""));
        unsigned int flags = va_arg(args, unsigned int);
        CHECK((flags & (OPEN_TREE_CLONE | OPEN_TREE_CLOEXEC | AT_EMPTY_PATH)) ==
            (OPEN_TREE_CLONE | OPEN_TREE_CLOEXEC | AT_EMPTY_PATH));
        CHECK(((flags & AT_RECURSIVE) != 0) == S_ISDIR(source->st.st_mode));
        if (source->namespace_id != current_namespace) errno = EINVAL;
        else { rc = allocate_fd(N_TREE, current_namespace); last_tree = (unsigned int)rc; ++cloned; }
    } else if (number == SYS_mount_setattr) {
        CHECK(descriptor(va_arg(args, int))->node == N_TREE);
        CHECK(!strcmp(va_arg(args, const char *), ""));
        CHECK(va_arg(args, int) == (AT_EMPTY_PATH | AT_RECURSIVE));
        const struct mount_attr *attributes = va_arg(args, const struct mount_attr *);
        CHECK(va_arg(args, size_t) == sizeof(*attributes));
        last_attributes = *attributes;
        if (fail_attributes) errno = EACCES;
        else rc = 0;
    } else if (number == SYS_move_mount) {
        CHECK(descriptor(va_arg(args, int))->node == N_TREE);
        CHECK(!strcmp(va_arg(args, const char *), ""));
        CHECK(va_arg(args, int) == 9);
        CHECK(!strcmp(va_arg(args, const char *), ""));
        CHECK(va_arg(args, int) == (MOVE_MOUNT_F_EMPTY_PATH | MOVE_MOUNT_T_EMPTY_PATH));
        if (fail_move) errno = EBUSY;
        else { ++moved; rc = 0; }
    } else CHECK(false);
    va_end(args);
    return rc;
}

static void empty_runtime(struct runtime *r)
{
    memset(r, 0, sizeof(*r));
    r->native_dir = r->worker_fd = r->app_fd = -1;
    for (int i = 0; i < ANCHORS; ++i) r->a[i].fd = -1;
    for (size_t i = 0; i < AJIU_LAUNCH_MAX_MOUNTS; ++i) r->source[i] = -1;
    (void)snprintf(r->package, sizeof(r->package), "com.ajiu.debug");
}

static void worker_runtime(struct runtime *r)
{
    reset_world();
    empty_runtime(r);
    r->native_dir = allocate_fd(N_NATIVE, 1);
    r->worker_fd = allocate_fd(N_WORKER, 1);
}

static void check_sources(void)
{
    for (unsigned int directory = 0; directory < 2; ++directory) {
        struct ajiu_launch_mount mount = { .kind = directory ? AJIU_MOUNT_DIRECTORY : AJIU_MOUNT_FILE,
            .source_dev = directory ? 21 : 22, .source_ino = directory ? 9001 : 9002,
            .source_path = directory ? "/storage/emulated/0/source-directory" : "/storage/emulated/0/source-file" };
        unsigned int node = directory ? N_DIRECTORY_SOURCE : N_FILE_SOURCE;
        reset_world();
        int inherited = allocate_fd(node, 1);
        CHECK(attach_tree(inherited, 9, true, directory != 0, true) < 0 && errno == EINVAL);
        CHECK(!cloned && !moved && live_fds() == 1);
        int fresh = open_mount_source(&mount);
        CHECK(fresh >= 0 && descriptors[fresh].namespace_id == current_namespace);
        CHECK(same(&descriptors[fresh].st, &descriptors[inherited].st));
        CHECK(attach_tree(fresh, 9, true, directory != 0, true) == 0);
        CHECK(cloned == 1 && moved == 1 && !descriptors[last_tree].live);
        CHECK(last_attributes.attr_set == (MOUNT_ATTR_NOSUID | MOUNT_ATTR_NODEV | MOUNT_ATTR_RDONLY));
        CHECK(last_attributes.attr_clr == MOUNT_ATTR_NOEXEC);
        CHECK(attach_tree(fresh, 9, false, directory != 0, true) == 0);
        CHECK(last_attributes.attr_set == (MOUNT_ATTR_NOSUID | MOUNT_ATTR_NODEV));
        CHECK(last_attributes.attr_clr == (MOUNT_ATTR_NOEXEC | MOUNT_ATTR_RDONLY));
        CHECK(cloned == 2 && moved == 2 && !descriptors[last_tree].live);
        CHECK(namespace_close(fresh) == 0 && namespace_close(inherited) == 0 && !live_fds());
        for (unsigned int failure = 0; failure < 4; ++failure) {
            reset_world();
            if (!failure) fail_open = 1;
            else if (failure == 1) fail_stat = 1;
            else if (failure == 2) ++nodes[node].st_dev;
            else ++nodes[node].st_ino;
            CHECK(open_mount_source(&mount) < 0);
            CHECK(errno == (!failure ? ENOENT : failure == 1 ? EACCES : ESTALE));
            CHECK(!live_fds() && !cloned && !moved);
        }
        reset_world();
        nodes[node].st_mode = directory ? S_IFREG | 0600 : S_IFDIR | 0700;
        CHECK(open_mount_source(&mount) < 0 && errno == ESTALE);
        CHECK(!live_fds());
        reset_world();
        fresh = open_mount_source(&mount);
        CHECK(fresh >= 0);
        for (unsigned int failure = 0; failure < 2; ++failure) {
            fail_attributes = !failure;
            fail_move = failure != 0;
            CHECK(attach_tree(fresh, 9, false, directory != 0, true) < 0);
            CHECK(errno == (!failure ? EACCES : EBUSY));
            CHECK(!descriptors[last_tree].live && live_fds() == 1);
        }
        CHECK(namespace_close(fresh) == 0 && !live_fds());
    }
}

static void worker_failed(struct runtime *r, int old_native, int old_worker)
{
    CHECK(reopen_worker(r) < 0 && errno > 0);
    CHECK(r->native_dir == old_native && r->worker_fd == old_worker);
    CHECK(descriptors[old_native].live && descriptors[old_worker].live && live_fds() == 2);
    CHECK(!strcmp(r->package, "com.ajiu.debug"));
    close_runtime(r);
    CHECK(!live_fds() && r->native_dir == -1 && r->worker_fd == -1);
    close_runtime(r);
    CHECK(!live_fds());
}

static void check_worker(void)
{
    struct runtime r;
    worker_runtime(&r);
    int old_native = r.native_dir, old_worker = r.worker_fd;
    struct runtime original = r;
    CHECK(attach_tree(r.worker_fd, 9, true, false, true) < 0 && errno == EINVAL);
    CHECK(reopen_worker(&r) == 0);
    CHECK(r.native_dir != old_native && r.worker_fd != old_worker);
    CHECK(!descriptors[old_native].live && !descriptors[old_worker].live && live_fds() == 2);
    CHECK(descriptors[r.native_dir].namespace_id == current_namespace && descriptors[r.worker_fd].namespace_id == current_namespace);
    CHECK(root_opens == 1 && worker_opens == 1 && !strcmp(r.package, original.package));
    CHECK(original.native_dir == old_native && original.worker_fd == old_worker);
    unsigned int successful_opens = open_calls, successful_stats = stat_calls, successful_preads = pread_calls;
    CHECK(attach_tree(r.worker_fd, 9, true, false, true) == 0);
    CHECK(cloned == 1 && moved == 1 && !descriptors[last_tree].live && live_fds() == 2);
    close_runtime(&r);
    close_runtime(&r);
    CHECK(!live_fds());
    for (unsigned int i = 1; i <= successful_opens; ++i) {
        worker_runtime(&r);
        fail_open = (int)i;
        worker_failed(&r, r.native_dir, r.worker_fd);
    }
    for (unsigned int i = 1; i <= successful_stats; ++i) {
        worker_runtime(&r);
        fail_stat = (int)i;
        worker_failed(&r, r.native_dir, r.worker_fd);
    }
    for (unsigned int i = 1; i <= successful_preads; ++i) {
        worker_runtime(&r);
        fail_pread = (int)i;
        worker_failed(&r, r.native_dir, r.worker_fd);
    }
    for (unsigned int failure = 0; failure < 9; ++failure) {
        worker_runtime(&r);
        switch (failure) {
        case 0: ++nodes[N_WORKER].st_ino; break;
        case 1: ++nodes[N_NATIVE].st_ino; break;
        case 2: ++nodes[N_RUNTIME].st_ino; break;
        case 3: invalid_worker_elf = true; break;
        case 4: invalid_runtime_elf = true; break;
        case 5: nodes[N_DATA].st_mode |= 0002; break;
        case 6: nodes[N_WORKER].st_mode |= 0020; break;
        case 7: fail_duplicate = true; break;
        default:
            (void)snprintf(executable_path, sizeof(executable_path),
                "/data/app/~~session/com.ajiu.beta-install/lib/arm64/libajiu_chroot_runtime.so");
            break;
        }
        worker_failed(&r, r.native_dir, r.worker_fd);
    }
    reset_world();
    empty_runtime(&r);
    r.native_dir = allocate_fd(N_NATIVE, 1);
    CHECK(reopen_worker(&r) == 0 && !open_calls && !stat_calls && !pread_calls && live_fds() == 1);
    close_runtime(&r);
    CHECK(!live_fds());
}

struct source_body { const char *begin, *end; };

static struct source_body function_body(const char *text, const char *signature)
{
    const char *begin = strstr(text, signature);
    CHECK(begin != NULL);
    begin = strchr(begin, '{');
    CHECK(begin != NULL);
    const char *end = strstr(begin, "\n}\n");
    CHECK(end != NULL);
    return (struct source_body){ begin, end };
}

static void ordered(struct source_body body, const char *const *tokens, size_t count)
{
    const char *position = body.begin;
    for (size_t i = 0; i < count; ++i) {
        position = strstr(position, tokens[i]);
        CHECK(position != NULL && position < body.end);
        position += strlen(tokens[i]);
    }
}

static void absent(struct source_body body, const char *token)
{
    const char *found = strstr(body.begin, token);
    CHECK(!found || found >= body.end);
}

static void check_static_order(const char *path)
{
    FILE *input = fopen(path, "rb");
    CHECK(input != NULL && fseek(input, 0, SEEK_END) == 0);
    long length = ftell(input);
    CHECK(length > 0 && length < 262144 && fseek(input, 0, SEEK_SET) == 0);
    char *text = malloc((size_t)length + 1);
    CHECK(text != NULL && fread(text, 1, (size_t)length, input) == (size_t)length);
    CHECK(fclose(input) == 0);
    text[length] = 0;
    struct source_body main_body = function_body(text, "int main(int argc, char **argv)");
    absent(main_body, "open_sources(");
    static const char *const main_order[] = { "prepare(&r)", "supervisor(&r," };
    ordered(main_body, main_order, sizeof(main_order) / sizeof(*main_order));
    struct source_body mount_body = function_body(text, "static int mount_namespace(");
    static const char *const mount_order[] = { "unshare(CLONE_NEWNS)", "MS_REC | MS_PRIVATE",
        "new_mount_namespace", "instance(r, true)", "namespace_rootfs_identity", "close(old[i].fd)",
        "protected_walk(true)", "namespace_cgroup_identity", "close(old[i].fd)", "r->namespace = ns", "group_guard(r)",
        "attach_tree(r->a[ROOT].fd, r->a[VIEW].fd, false, true, true)", "*view = resolve_target(", "fstatfs(*view, &fs)", "writable_executable_os",
        "(ST_NOSUID | ST_NODEV));", "open_sources(r)", "reopen_worker(r)",
        "for (int pass = 0; pass < 2; ++pass)", "attach_tree(r->source[i]" };
    ordered(mount_body, mount_order, sizeof(mount_order) / sizeof(*mount_order));
    struct source_body child = function_body(text, "static void open_sources_child(");
    static const char *const child_order[] = { "close_except(keep, 1)", "drop_identity(r, parent)", "open_mount_source(", "sendmsg(" };
    ordered(child, child_order, sizeof(child_order) / sizeof(*child_order));
    struct source_body opener = function_body(text, "static int open_sources(");
    static const char *const opener_order[] = { "source_paths_match(r)", "socketpair(", "SO_PASSCRED", "fork()",
        "open_sources_child(", "SCM_RIGHTS", "SCM_CREDENTIALS", "cred.uid != r->launch->uid",
        "m->source_dev", "m->source_ino", "wait_pidfd(pidfd, 2500)", "r->source[i] = received[i]" };
    ordered(opener, opener_order, sizeof(opener_order) / sizeof(*opener_order));
    absent(function_body(text, "static int supervisor("), "close(r->source[");
    struct source_body guest_body = function_body(text, "static void guest(");
    static const char *const guest_order[] = { "chroot(\".\")", "guest_credentials(r, parent)", "execve(\"/bin/sh\"" };
    ordered(guest_body, guest_order, sizeof(guest_order) / sizeof(*guest_order));
    absent(guest_body, "drop_identity(");
    struct source_body instance_body = function_body(text, "static int instance(");
    static const char *const instance_order[] = { "installed_layout", "pread(r->a[MANIFEST].fd", "guest_manifest_version(header[0])" };
    ordered(instance_body, instance_order, sizeof(instance_order) / sizeof(*instance_order));
    free(text);
}

int main(int argc, char **argv)
{
    CHECK(argc == 1 || argc == 2);
    check_sources();
    check_worker();
    check_static_order(argc == 2 ? argv[1] : "native/chroot/runtime_helper.c");
    printf("Runtime namespace mock checks passed: %u; production source/worker acquisition and attach_tree use controlled syscall mocks; call ordering is static; no real unshare, mount, chroot, root or device operations; not a kernel semantics proof.\n", checks);
    return 0;
}
