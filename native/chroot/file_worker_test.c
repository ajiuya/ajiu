#define _GNU_SOURCE
#include <stdarg.h>
#include <stdint.h>
#include <stdbool.h>
#include <dirent.h>
#include <sys/vfs.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static long test_syscall(long number, ...);
static int test_unlinkat(int directory, const char *name, int flags);
static uid_t test_geteuid(void);
static gid_t test_getegid(void);
static int test_getresuid(uid_t *real, uid_t *effective, uid_t *saved);
static int test_getresgid(gid_t *real, gid_t *effective, gid_t *saved);
static int test_getgroups(int size, gid_t *groups);
static int test_prctl(int option, ...);
static int test_fchown(int fd, uid_t uid, gid_t gid);
static int test_fchmod(int fd, mode_t mode);
static int test_mkdirat(int parent, const char *name, mode_t mode);
static int test_fstatfs(int fd, struct statfs *fs);
static struct dirent *test_readdir(DIR *dir);

#define fstatfs test_fstatfs
#define readdir test_readdir
#define mkdirat test_mkdirat
#define geteuid test_geteuid
#define getegid test_getegid
#define getresuid test_getresuid
#define getresgid test_getresgid
#define getgroups test_getgroups
#define prctl test_prctl
#define fchown test_fchown
#define fchmod test_fchmod
#define syscall test_syscall
#define unlinkat test_unlinkat
#define main ajfw_production_main_not_called
#include "file_worker.c"
#undef main
#undef readdir
#undef fstatfs
#undef mkdirat
#undef unlinkat
#undef syscall
#undef fchmod
#undef fchown
#undef prctl
#undef getgroups
#undef getresgid
#undef getresuid
#undef getegid
#undef geteuid

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "File worker check line %d: %s; errno=%d\n", __LINE__, #c, errno); exit(1); } ++checks; } while (0)

static unsigned long checks;

struct test_faults {
    const char *open_name, *watch_name;
    int open_error, stat_error, stat_fd, special_fd, directory;
    bool special, begin_swap;
    size_t watched_opens, watched_unlinks;
    char created[64];
};
static struct test_faults faults;
static int synthetic_root = -1;
static struct {
    char path[FW_PATH + 1];
    uint64_t id;
    long fs_type;
    bool synthetic_metadata;
    int fs_error;
    size_t fs_queries;
} config_mounts[4];
static size_t config_mount_count;

static uint64_t test_config_mount_id(int fd)
{
    if (!config_mount_count) return 0;
    char number[64], path[FW_PATH + 1];
    CHECK(snprintf(number, sizeof(number), "/proc/self/fd/%d", fd) > 0);
    ssize_t size = readlink(number, path, FW_PATH);
    CHECK(size > 0 && size < FW_PATH); path[size] = 0;
    for (size_t i = config_mount_count; i > 0; --i)
        if (fw_below(path, config_mounts[i - 1].path)) return config_mounts[i - 1].id;
    return 0;
}

static struct {
    bool active, all_chowns_mocked, metadata_fault;
    uid_t uid[3]; gid_t gid[3];
    uint64_t caps[5];
    int nnp, securebits, fchown_error, fchmod_error, groupc;
    gid_t group;
    size_t chowns, chmods;
    int last_chown_fd, order;
    uid_t last_uid; gid_t last_gid;
    int virtual_fd;
    dev_t virtual_dev; ino_t virtual_ino;
    uid_t virtual_uid; gid_t virtual_gid; mode_t virtual_mode;
    dev_t original_dev; ino_t original_ino;
    uid_t original_uid; gid_t original_gid; mode_t original_mode;
} root_mock;
static struct {
    bool active, swap_requested, compete_on_publish, swap_temporary;
    int chown_error;
    size_t publish_attempts;
    dev_t home_dev, workspace_dev, foreign_dev;
    ino_t home_ino, workspace_ino, foreign_ino;
    uint64_t home_mount, workspace_mount;
    size_t mkdir_calls, swaps, foreign_chowns;
    char temporary[64];
    dev_t issued_dev[4]; ino_t issued_ino[4]; size_t issued;
} mkdir_race;
static struct {
    bool active, nonempty_directory, swap_directory, compete_directory, mutate_on_empty, replaced_temporary;
    bool insert_on_empty;
    size_t empty_scans;
    unsigned int directory_nlink;
    int readdir_error;
    size_t directory_reads, publishes, replacements;
    char temporary[64];
    dev_t created_dev, changed_dev;
    ino_t created_ino, changed_ino;
    uint32_t changed_mask, creation_mask;
    uint32_t uid, gid, nlink;
    uint16_t mode;
    uint64_t size, mount;
} shared_mock;

static int test_fstatfs(int fd, struct statfs *fs)
{
    uint64_t id = test_config_mount_id(fd);
    for (size_t i = 0; i < config_mount_count; ++i) if (id == config_mounts[i].id) {
        ++config_mounts[i].fs_queries;
        if (config_mounts[i].fs_error) return fw_fail(config_mounts[i].fs_error);
        if (config_mounts[i].fs_type) {
            memset(fs, 0, sizeof(*fs)); fs->f_type = config_mounts[i].fs_type; return 0;
        }
    }
    return fstatfs(fd, fs);
}

static struct dirent *test_readdir(DIR *dir)
{
    struct stat st;
    if (shared_mock.active && fstat(dirfd(dir), &st) == 0 &&
        st.st_dev == shared_mock.created_dev && st.st_ino == shared_mock.created_ino) {
        ++shared_mock.directory_reads;
        if (shared_mock.readdir_error) { errno = shared_mock.readdir_error; return NULL; }
        struct dirent *entry = readdir(dir);
        if (!entry && !errno) {
            ++shared_mock.empty_scans;
            if (shared_mock.mutate_on_empty) {
                shared_mock.changed_dev = st.st_dev; shared_mock.changed_ino = st.st_ino;
                shared_mock.changed_mask = STATX_MODE; shared_mock.mode = S_IFDIR | 02750;
            }
            if (shared_mock.insert_on_empty && shared_mock.empty_scans == 2) {
                int child = openat(dirfd(dir), "late-child", O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
                CHECK(child >= 0 && write(child, "preserved", 9) == 9 && close(child) == 0);
                struct timespec times[2] = { st.st_atim, st.st_mtim }; ++times[1].tv_sec;
                CHECK(futimens(dirfd(dir), times) == 0); errno = 0;
            }
        }
        return entry;
    }
    return readdir(dir);
}

static int test_mkdirat(int parent, const char *name, mode_t mode)
{
    int rc = mkdirat(parent, name, mode);
    if (!rc && shared_mock.active && test_config_mount_id(parent) && !strncmp(name, ".ajfw-", 6)) {
        struct stat created;
        CHECK(fstatat(parent, name, &created, AT_SYMLINK_NOFOLLOW) == 0);
        shared_mock.created_dev = created.st_dev; shared_mock.created_ino = created.st_ino;
        if (shared_mock.creation_mask) {
            shared_mock.changed_dev = created.st_dev; shared_mock.changed_ino = created.st_ino;
            shared_mock.changed_mask = shared_mock.creation_mask;
        }
        strcpy(shared_mock.temporary, name);
        if (shared_mock.nonempty_directory) {
            int directory = openat(parent, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            CHECK(directory >= 0);
            int child = openat(directory, "foreign-child", O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
            CHECK(child >= 0 && write(child, "preserved", 9) == 9 && close(child) == 0 && close(directory) == 0);
        }
        if (shared_mock.swap_directory) {
            CHECK(renameat(parent, name, parent, "saved-created") == 0 && symlinkat("/", parent, name) == 0);
            ++shared_mock.replacements;
        }
    }
    if (rc || !mkdir_race.active) return rc;
    ++mkdir_race.mkdir_calls;
    if (!strncmp(name, ".ajfw-", 6)) { CHECK(strlen(name) < sizeof(mkdir_race.temporary)); strcpy(mkdir_race.temporary, name); }
    struct stat directory, created;
    CHECK(fstat(parent, &directory) == 0 && fstatat(parent, name, &created, AT_SYMLINK_NOFOLLOW) == 0);
    bool known_parent = directory.st_dev == mkdir_race.workspace_dev && directory.st_ino == mkdir_race.workspace_ino;
    for (size_t i = 0; i < mkdir_race.issued; ++i)
        if (directory.st_dev == mkdir_race.issued_dev[i] && directory.st_ino == mkdir_race.issued_ino[i]) known_parent = true;
    if (known_parent) {
        CHECK(mkdir_race.issued < 4);
        size_t at = mkdir_race.issued++;
        mkdir_race.issued_dev[at] = created.st_dev; mkdir_race.issued_ino[at] = created.st_ino;
        if ((mkdir_race.swap_requested && !strcmp(name, "requested")) ||
            (mkdir_race.swap_temporary && !strncmp(name, ".ajfw-", 6))) {
            CHECK(renameat(parent, name, parent, "saved-created") == 0);
            CHECK(symlinkat("/", parent, name) == 0);
            CHECK(fstatat(parent, name, &created, AT_SYMLINK_NOFOLLOW) == 0);
            CHECK(mkdir_race.issued < 4);
            at = mkdir_race.issued++;
            mkdir_race.issued_dev[at] = created.st_dev; mkdir_race.issued_ino[at] = created.st_ino;
            ++mkdir_race.swaps;
        }
    }
    return 0;
}

static uid_t test_geteuid(void) { return root_mock.active ? root_mock.uid[1] : geteuid(); }
static gid_t test_getegid(void) { return root_mock.active ? root_mock.gid[1] : getegid(); }
static int test_getresuid(uid_t *real, uid_t *effective, uid_t *saved)
{
    if (!root_mock.active) return getresuid(real, effective, saved);
    *real = root_mock.uid[0]; *effective = root_mock.uid[1]; *saved = root_mock.uid[2]; return 0;
}
static int test_getresgid(gid_t *real, gid_t *effective, gid_t *saved)
{
    if (!root_mock.active) return getresgid(real, effective, saved);
    *real = root_mock.gid[0]; *effective = root_mock.gid[1]; *saved = root_mock.gid[2]; return 0;
}
static int test_getgroups(int size, gid_t *groups)
{
    if (!root_mock.active) return getgroups(size, groups);
    CHECK(size >= root_mock.groupc);
    if (root_mock.groupc) groups[0] = root_mock.group;
    return root_mock.groupc;
}
static int test_prctl(int option, ...)
{
    CHECK(root_mock.active);
    va_list args; va_start(args, option); int rc = -1;
    if (option == PR_GET_NO_NEW_PRIVS) rc = root_mock.nnp;
    else if (option == PR_GET_SECUREBITS) rc = root_mock.securebits;
    else if (option == PR_CAPBSET_READ) {
        int cap = va_arg(args, int);
        if (cap > 40) { errno = EINVAL; rc = -1; } else rc = (int)((root_mock.caps[3] >> cap) & 1U);
    } else if (option == PR_CAP_AMBIENT) {
        CHECK(va_arg(args, int) == PR_CAP_AMBIENT_IS_SET); int cap = va_arg(args, int);
        rc = (int)((root_mock.caps[4] >> cap) & 1U);
    } else CHECK(false);
    va_end(args); return rc;
}
static int test_fchown(int fd, uid_t uid, gid_t gid)
{
    if (!root_mock.active && !root_mock.metadata_fault) return fchown(fd, uid, gid);
    ++root_mock.chowns; root_mock.last_chown_fd = fd; root_mock.last_uid = uid; root_mock.last_gid = gid;
    if (mkdir_race.active) {
        struct stat target; CHECK(fstat(fd, &target) == 0);
        if (target.st_dev == mkdir_race.foreign_dev && target.st_ino == mkdir_race.foreign_ino)
            ++mkdir_race.foreign_chowns;
    }
    root_mock.order = 1;
    if (mkdir_race.active && mkdir_race.chown_error) return fw_fail(mkdir_race.chown_error);
    if (root_mock.fchown_error) return fw_fail(root_mock.fchown_error);
    if (!root_mock.all_chowns_mocked) return fchown(fd, uid, gid);
    struct stat st; CHECK(fstat(fd, &st) == 0);
    root_mock.virtual_fd = fd; root_mock.virtual_dev = st.st_dev; root_mock.virtual_ino = st.st_ino;
    root_mock.virtual_uid = uid; root_mock.virtual_gid = gid;
    root_mock.virtual_mode = st.st_mode & 01777;
    return 0;
}
static int test_fchmod(int fd, mode_t mode)
{
    if (!root_mock.active && !root_mock.metadata_fault) return fchmod(fd, mode);
    ++root_mock.chmods;
    if (root_mock.chowns) CHECK(root_mock.order == 1 && root_mock.last_chown_fd == fd);
    root_mock.order = 2;
    if (root_mock.fchmod_error) return fw_fail(root_mock.fchmod_error);
    if (!root_mock.all_chowns_mocked) return fchmod(fd, mode);
    struct stat st; CHECK(fstat(fd, &st) == 0);
    root_mock.virtual_fd = fd; root_mock.virtual_dev = st.st_dev; root_mock.virtual_ino = st.st_ino;
    root_mock.virtual_mode = mode;
    return 0;
}

static void test_reset_faults(void)
{
    memset(&faults, 0, sizeof(faults));
    faults.stat_fd = faults.special_fd = faults.directory = -1;
}

static long test_syscall(long number, ...)
{
    va_list arguments; va_start(arguments, number);
    if (root_mock.active && number == SYS_capget) {
        const struct __user_cap_header_struct *header = va_arg(arguments, const struct __user_cap_header_struct *);
        struct __user_cap_data_struct *caps = va_arg(arguments, struct __user_cap_data_struct *);
        CHECK(header->version == _LINUX_CAPABILITY_VERSION_3);
        memset(caps, 0, 2 * sizeof(*caps));
        caps[0].effective = (uint32_t)root_mock.caps[0]; caps[1].effective = (uint32_t)(root_mock.caps[0] >> 32);
        caps[0].permitted = (uint32_t)root_mock.caps[1]; caps[1].permitted = (uint32_t)(root_mock.caps[1] >> 32);
        caps[0].inheritable = (uint32_t)root_mock.caps[2]; caps[1].inheritable = (uint32_t)(root_mock.caps[2] >> 32);
        va_end(arguments); return 0;
    }
    if (root_mock.active && (number == SYS_setfsuid || number == SYS_setfsgid)) {
        va_end(arguments); return number == SYS_setfsuid ? (long)root_mock.uid[1] : (long)root_mock.gid[1];
    }
    if (number == SYS_openat2) {
        int directory = va_arg(arguments, int);
        const char *name = va_arg(arguments, const char *);
        const struct open_how *how = va_arg(arguments, const struct open_how *);
        size_t size = va_arg(arguments, size_t);
        va_end(arguments);
        if (faults.watch_name && !strcmp(name, faults.watch_name)) ++faults.watched_opens;
        if (faults.open_name && !strcmp(name, faults.open_name) && faults.open_error) return fw_fail(faults.open_error);
        struct open_how synthetic;
        if (how->resolve & RESOLVE_IN_ROOT) {
            CHECK(directory == synthetic_root && synthetic_root >= 0 && fw_path_valid(name) == 0);
            CHECK((how->resolve & (RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS)) ==
                (RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS));
            synthetic = *how;
            synthetic.resolve = (synthetic.resolve & ~(uint64_t)RESOLVE_IN_ROOT) | RESOLVE_BENEATH;
            how = &synthetic; name = name[1] ? name + 1 : ".";
        }
        long result = syscall(number, directory, name, how, size);
        if (result >= 0) {
            if (faults.stat_fd == result) faults.stat_fd = -1;
            if (faults.special_fd == result) faults.special_fd = -1;
        }
        if (result >= 0 && faults.open_name && !strcmp(name, faults.open_name)) {
            if (faults.stat_error) faults.stat_fd = (int)result;
            if (faults.special) faults.special_fd = (int)result;
        }
        if (result >= 0 && shared_mock.active && shared_mock.creation_mask &&
            (how->flags & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL)) {
            struct stat created; CHECK(fstat((int)result, &created) == 0);
            shared_mock.changed_dev = created.st_dev; shared_mock.changed_ino = created.st_ino;
            shared_mock.changed_mask = shared_mock.creation_mask;
        }
        if (result >= 0 && faults.begin_swap && (how->flags & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL)) {
            CHECK(strlen(name) < sizeof(faults.created));
            strcpy(faults.created, name); faults.stat_fd = (int)result;
        }
        return result;
    }
    if (number == SYS_statx) {
        int fd = va_arg(arguments, int);
        const char *name = va_arg(arguments, const char *);
        int flags = va_arg(arguments, int);
        unsigned int mask = va_arg(arguments, unsigned int);
        struct statx *identity = va_arg(arguments, struct statx *);
        va_end(arguments);
        if (fd == faults.stat_fd && faults.stat_error) return fw_fail(faults.stat_error);
        long result = syscall(number, fd, name, flags, mask, identity);
        if (!result && fd == faults.special_fd && faults.special)
            identity->stx_mode = S_IFIFO | 0600;
        if (!result && config_mount_count) {
            uint64_t mount = test_config_mount_id(fd);
            if (mount) identity->stx_mnt_id = mount;
        }
        if (!result && root_mock.active) {
            if (identity->stx_ino == (uint64_t)root_mock.original_ino &&
                makedev(identity->stx_dev_major, identity->stx_dev_minor) == root_mock.original_dev) {
                identity->stx_uid = root_mock.original_uid; identity->stx_gid = root_mock.original_gid;
                identity->stx_mode = (identity->stx_mode & S_IFMT) | (uint16_t)root_mock.original_mode;
            } else if (identity->stx_ino == (uint64_t)root_mock.virtual_ino &&
                makedev(identity->stx_dev_major, identity->stx_dev_minor) == root_mock.virtual_dev) {
                identity->stx_uid = root_mock.virtual_uid; identity->stx_gid = root_mock.virtual_gid;
                identity->stx_mode = (identity->stx_mode & S_IFMT) | (uint16_t)root_mock.virtual_mode;
            } else { identity->stx_uid = 0; identity->stx_gid = 0; }
        }
        if (!result && config_mount_count) {
            identity->stx_uid = identity->stx_gid = 10445;
            for (size_t i = 0; i < config_mount_count; ++i)
                if (identity->stx_mnt_id == config_mounts[i].id && config_mounts[i].synthetic_metadata) {
                    identity->stx_uid = identity->stx_gid = 1023;
                    if (S_ISDIR(identity->stx_mode)) {
                        identity->stx_mode = S_IFDIR | 02770;
                        identity->stx_nlink = shared_mock.directory_nlink;
                    } else if (S_ISREG(identity->stx_mode)) identity->stx_mode = S_IFREG | 0660;
                }
        }
        if (!result && shared_mock.active && identity->stx_ino == (uint64_t)shared_mock.changed_ino &&
            makedev(identity->stx_dev_major, identity->stx_dev_minor) == shared_mock.changed_dev) {
            if (shared_mock.changed_mask & STATX_UID) identity->stx_uid = shared_mock.uid;
            if (shared_mock.changed_mask & STATX_GID) identity->stx_gid = shared_mock.gid;
            if (shared_mock.changed_mask & STATX_MODE) identity->stx_mode = shared_mock.mode;
            if (shared_mock.changed_mask & STATX_NLINK) identity->stx_nlink = shared_mock.nlink;
            if (shared_mock.changed_mask & STATX_SIZE) identity->stx_size = shared_mock.size;
            if (shared_mock.mount) identity->stx_mnt_id = shared_mock.mount;
        }
        if (!result && mkdir_race.active) {
            dev_t dev = makedev(identity->stx_dev_major, identity->stx_dev_minor);
            ino_t ino = (ino_t)identity->stx_ino;
            if (dev == mkdir_race.home_dev && ino == mkdir_race.home_ino) {
                identity->stx_mnt_id = mkdir_race.home_mount;
                identity->stx_uid = identity->stx_gid = 10445;
            } else if (dev == mkdir_race.workspace_dev && ino == mkdir_race.workspace_ino)
                identity->stx_mnt_id = mkdir_race.workspace_mount;
            else for (size_t i = 0; i < mkdir_race.issued; ++i)
                if (dev == mkdir_race.issued_dev[i] && ino == mkdir_race.issued_ino[i])
                    identity->stx_mnt_id = mkdir_race.workspace_mount;
        }
        if (!result && fd == faults.stat_fd && faults.begin_swap) {
            CHECK(faults.directory >= 0 && faults.created[0]);
            CHECK(renameat(faults.directory, faults.created, faults.directory, "saved-begin-owned") == 0);
            int foreign = openat(faults.directory, faults.created, O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
            CHECK(foreign >= 0 && write(foreign, "foreign-preserved", 17) == 17 && close(foreign) == 0);
            identity->stx_nlink = 2; faults.begin_swap = false;
        }
        return result;
    }
    if (number == SYS_renameat2) {
        int source = va_arg(arguments, int);
        const char *source_name = va_arg(arguments, const char *);
        int target = va_arg(arguments, int);
        const char *target_name = va_arg(arguments, const char *);
        unsigned int flags = va_arg(arguments, unsigned int);
        va_end(arguments);
        struct stat root, src, dst;
        CHECK(synthetic_root >= 0 && fstat(synthetic_root, &root) == 0 && fstat(source, &src) == 0 && fstat(target, &dst) == 0);
        if (mkdir_race.active) {
            bool known_parent = src.st_dev == mkdir_race.workspace_dev && src.st_ino == mkdir_race.workspace_ino;
            for (size_t i = 0; i < mkdir_race.issued; ++i)
                if (src.st_dev == mkdir_race.issued_dev[i] && src.st_ino == mkdir_race.issued_ino[i]) known_parent = true;
            CHECK(known_parent && dst.st_dev == src.st_dev && dst.st_ino == src.st_ino && flags == RENAME_NOREPLACE);
            CHECK(!strncmp(source_name, ".ajfw-", 6) && (!strcmp(target_name, "requested") || !strcmp(target_name, "leaf")));
            ++mkdir_race.publish_attempts;
            if (mkdir_race.compete_on_publish) {
                CHECK(symlinkat("/", target, target_name) == 0); ++mkdir_race.swaps;
                struct stat link; CHECK(fstatat(target, target_name, &link, AT_SYMLINK_NOFOLLOW) == 0 && mkdir_race.issued < 4);
                size_t at = mkdir_race.issued++;
                mkdir_race.issued_dev[at] = link.st_dev; mkdir_race.issued_ino[at] = link.st_ino;
            }
        } else if (config_mount_count) {
            CHECK(test_config_mount_id(source) != 0 && test_config_mount_id(source) == test_config_mount_id(target));
            if (shared_mock.active) {
                ++shared_mock.publishes;
                if (shared_mock.compete_directory) {
                    CHECK(flags == RENAME_NOREPLACE && symlinkat("/", target, target_name) == 0);
                    ++shared_mock.replacements;
                }
            }
        } else CHECK(root.st_dev == src.st_dev && root.st_ino == src.st_ino && root.st_dev == dst.st_dev && root.st_ino == dst.st_ino);
        CHECK(fw_entry_name(source_name) == 0 && fw_entry_name(target_name) == 0 && (flags == 0 || flags == RENAME_NOREPLACE));
        return syscall(number, source, source_name, target, target_name, flags);
    }
    va_end(arguments); return fw_fail(ENOSYS);
}

static int test_unlinkat(int directory, const char *name, int flags)
{
    if (faults.watch_name && !strcmp(name, faults.watch_name)) ++faults.watched_unlinks;
    return unlinkat(directory, name, flags);
}

static void request_header(unsigned char bytes[FW_HEADER], uint16_t op, uint32_t id, uint32_t size)
{
    memcpy(bytes, "AJFW", 4); fw_put16(bytes + 4, 1); fw_put16(bytes + 6, op);
    fw_put32(bytes + 8, id); fw_put32(bytes + 12, size);
}
static int test_headers(void)
{
    unsigned char bytes[FW_HEADER]; struct fw_header header;
    for (uint16_t op = FW_STAT; op <= FW_RENAME; ++op) {
        request_header(bytes, op, UINT32_MAX, FW_FRAME);
        CHECK(fw_decode_header(bytes, &header) == 0);
        CHECK(header.op == op && header.id == UINT32_MAX && header.length == FW_FRAME);
        fw_encode_header(bytes, &header, 4);
        CHECK(!memcmp(bytes, "AJFW", 4) && fw_be16(bytes + 4) == 1);
        CHECK(fw_be16(bytes + 6) == (op | 0x8000U) && fw_be32(bytes + 8) == UINT32_MAX && fw_be32(bytes + 12) == 4);
        CHECK(fw_decode_header(bytes, &header) == -1 && errno == EPROTO);
    }
    for (uint32_t op = 0; op <= UINT16_MAX; ++op) {
        if (op >= FW_STAT && op <= FW_RENAME) continue;
        request_header(bytes, (uint16_t)op, 1, 0);
        CHECK(fw_decode_header(bytes, &header) == -1 && errno == EPROTO);
    }
    request_header(bytes, FW_STAT, 0, 0);
    CHECK(fw_decode_header(bytes, &header) == -1 && errno == EPROTO);
    request_header(bytes, FW_STAT, 1, FW_FRAME + 1);
    CHECK(fw_decode_header(bytes, &header) == -1 && errno == EPROTO);
    request_header(bytes, FW_STAT, 1, UINT32_MAX);
    CHECK(fw_decode_header(bytes, &header) == -1 && errno == EPROTO);
    for (size_t i = 0; i < 4; ++i) {
        request_header(bytes, FW_STAT, 1, 0); bytes[i] ^= 0xff;
        CHECK(fw_decode_header(bytes, &header) == -1 && errno == EPROTO);
    }
    request_header(bytes, FW_STAT, 1, 0); fw_put16(bytes + 4, 0);
    CHECK(fw_decode_header(bytes, &header) == -1 && errno == EPROTO);
    request_header(bytes, FW_STAT, 1, 0); fw_put16(bytes + 4, 2);
    CHECK(fw_decode_header(bytes, &header) == -1 && errno == EPROTO);
    return 0;
}

static int test_text_paths(void)
{
    static const char *const good[] = { "/", "/root", "/root/workspace", "/root/workspace/a b", "/root/.aicode/plugins/x",
        "/usr/lib", "/root/\xe9\x98\xbf\xe4\xb9\x9d-\xf0\x9f\x8f\xa0", "/root/a\\b" };
    static const char *const bad[] = { "", "root", "~/workspace", "//", "/root/", "/root//x", "/./root", "/root/../x", "/root/.", "/root/.." };
    for (size_t i = 0; i < sizeof(good) / sizeof(*good); ++i) CHECK(fw_path_valid(good[i]) == 0);
    for (size_t i = 0; i < sizeof(bad) / sizeof(*bad); ++i) CHECK(fw_path_valid(bad[i]) == -1 && errno == EINVAL);
    static const unsigned char bad_utf8[][5] = { {0xc0,0x80}, {0xc1,0xbf}, {0x80}, {0xf5,0x80,0x80,0x80},
        {0xed,0xa0,0x80}, {0xf4,0x90,0x80,0x80}, {0xe0,0x80,0x80}, {0xf0,0x80,0x80,0x80}, {0xe2,0x28,0xa1}, {0} };
    static const size_t bad_sizes[] = { 2, 2, 1, 4, 3, 4, 3, 4, 3, 1 };
    for (size_t i = 0; i < sizeof(bad_sizes) / sizeof(*bad_sizes); ++i)
        CHECK(!fw_utf8(bad_utf8[i], bad_sizes[i]));
    const unsigned char valid[] = { 0xc2,0x80,0xdf,0xbf,0xe0,0xa0,0x80,0xed,0x9f,0xbf,0xee,0x80,0x80,
        0xef,0xbf,0xbf,0xf0,0x90,0x80,0x80,0xf4,0x8f,0xbf,0xbf };
    CHECK(fw_utf8(valid, sizeof(valid)));
    CHECK(!fw_utf8(valid + 19, 3));
    for (size_t i = 0; i < 128; ++i) {
        unsigned char byte = (unsigned char)i;
        CHECK(fw_utf8(&byte, 1) == (i != 0));
    }
    char component[FW_NAME + 3]; component[0] = '/'; memset(component + 1, 'x', FW_NAME); component[FW_NAME + 1] = 0;
    CHECK(fw_path_valid(component) == 0);
    component[FW_NAME + 1] = 'x'; component[FW_NAME + 2] = 0;
    CHECK(fw_path_valid(component) == -1 && errno == ENAMETOOLONG);
    char path[FW_PATH + 2]; size_t used = 0;
    while (used < FW_PATH) {
        path[used++] = '/'; size_t length = FW_PATH - used;
        if (length > FW_NAME) length = FW_NAME;
        memset(path + used, 'x', length); used += length;
    }
    path[used] = 0;
    CHECK(fw_path_valid(path) == 0);
    path[used++] = 'x'; path[used] = 0;
    CHECK(fw_path_valid(path) == -1 && errno == ENAMETOOLONG);
    static const char *const reserved[] = { "/proc", "/proc/self/fd/8", "/dev", "/dev/null", "/sys/x", "/tmp", "/tmp/x", "/run", "/run/ajiu-file-worker" };
    for (size_t i = 0; i < sizeof(reserved) / sizeof(*reserved); ++i) CHECK(fw_reserved(reserved[i]));
    CHECK(!fw_reserved("/procure") && !fw_reserved("/root/tmp") && !fw_reserved("/runner"));
    CHECK(fw_below("/root/workspace/x", "/root/workspace"));
    CHECK(fw_below("/root/workspace", "/root/workspace"));
    CHECK(!fw_below("/root/workspace-other", "/root/workspace"));
    CHECK(fw_entry_name("normal") == 0 && fw_entry_name("a\\b") == 0);
    CHECK(fw_entry_name(".") == -1 && fw_entry_name("..") == -1 && fw_entry_name("a/b") == -1);
    return 0;
}

static int test_input_paths(void)
{
    static const char *const valid[] = { "/", "//", "/./a", "/sub//a", "/trailingdir/", "/guestlink/../file",
        "/a/./b/../../", "///a///b//", "/../../file", "/root/\xe9\x98\xbf\xe4\xb9\x9d//./x", "/tmp/../file" };
    for (size_t i = 0; i < sizeof(valid) / sizeof(*valid); ++i) {
        CHECK(fw_input_path_valid(valid[i]) == 0);
        unsigned char bytes[128] = {0}; struct fw_output out = { .bytes = bytes };
        CHECK(fw_text(&out, valid[i]) == 0);
        struct fw_input in = { .bytes = bytes, .size = out.size };
        char path[FW_PATH + 1];
        CHECK(fw_take_path(&in, path) == 0 && fw_end(&in) == 0 && !strcmp(path, valid[i]));
        if (i) CHECK(fw_path_valid(valid[i]) == -1 && errno == EINVAL);
    }
    static const char *const invalid[] = { "", ".", "..", "./a", "../file", "sub//a", "~/workspace", "relative" };
    for (size_t i = 0; i < sizeof(invalid) / sizeof(*invalid); ++i)
        CHECK(fw_input_path_valid(invalid[i]) == -1 && errno == EINVAL);
    static const char *const invalid_utf8[] = { "/\xc0\x80", "/\xed\xa0\x80", "/\xf4\x90\x80\x80", "/\xe2\x82" };
    for (size_t i = 0; i < sizeof(invalid_utf8) / sizeof(*invalid_utf8); ++i)
        CHECK(fw_input_path_valid(invalid_utf8[i]) == -1 && errno == EILSEQ);
    unsigned char bytes[16] = {0}; fw_put32(bytes, 4); memcpy(bytes + 4, "/a\0b", 4);
    struct fw_input in = { .bytes = bytes, .size = 8 }; char output[FW_PATH + 1];
    CHECK(fw_take_path(&in, output) == -1 && errno == EILSEQ);
    fw_put32(bytes, 4); memcpy(bytes + 4, "./ab", 4); in.offset = 0;
    CHECK(fw_take_path(&in, output) == -1 && errno == EINVAL);
    char component[FW_NAME + 3]; component[0] = '/';
    memset(component + 1, 'x', FW_NAME); component[FW_NAME + 1] = 0;
    CHECK(fw_input_path_valid(component) == 0);
    component[FW_NAME + 1] = 'x'; component[FW_NAME + 2] = 0;
    CHECK(fw_input_path_valid(component) == -1 && errno == ENAMETOOLONG);
    for (size_t i = 1; i <= FW_NAME; i += 3) memcpy(component + i, "\xe9\x98\xbf", 3);
    component[FW_NAME + 1] = 0;
    CHECK(fw_input_path_valid(component) == 0);
    component[FW_NAME + 1] = 'x'; component[FW_NAME + 2] = 0;
    CHECK(fw_input_path_valid(component) == -1 && errno == ENAMETOOLONG);
    char path[FW_PATH + 2]; memset(path, '/', FW_PATH); path[FW_PATH] = 0;
    CHECK(fw_input_path_valid(path) == 0);
    path[FW_PATH] = '/'; path[FW_PATH + 1] = 0;
    CHECK(fw_input_path_valid(path) == -1 && errno == ENAMETOOLONG);
    return 0;
}

static int test_payloads(void)
{
    unsigned char *bytes = malloc(FW_FRAME);
    CHECK(bytes != NULL);
    struct fw_output out = { .bytes = bytes };
    CHECK(fw_text(&out, "/root/workspace/a") == 0);
    CHECK(fw_out32(&out, 1) == 0);
    size_t total = out.size;
    char path[FW_PATH + 1]; uint32_t value;
    struct fw_input in = { .bytes = bytes, .size = total };
    CHECK(fw_take_path(&in, path) == 0 && !strcmp(path, "/root/workspace/a"));
    CHECK(fw_bool(&in, &value) == 0 && value == 1 && fw_end(&in) == 0);
    for (size_t i = 0; i < total; ++i) {
        in = (struct fw_input){ .bytes = bytes, .size = i };
        CHECK(fw_take_path(&in, path) < 0 || fw_bool(&in, &value) < 0 || fw_end(&in) < 0);
    }
    bytes[total] = 0; in = (struct fw_input){ .bytes = bytes, .size = total + 1 };
    CHECK(fw_take_path(&in, path) == 0 && fw_bool(&in, &value) == 0 && fw_end(&in) == -1);
    fw_put32(bytes + total - 4, 2); in = (struct fw_input){ .bytes = bytes, .size = total };
    CHECK(fw_take_path(&in, path) == 0 && fw_bool(&in, &value) == -1 && errno == EINVAL);
    fw_put32(bytes, FW_PATH + 1); in = (struct fw_input){ .bytes = bytes, .size = total };
    CHECK(fw_take_path(&in, path) == -1 && errno == ENAMETOOLONG);
    fw_put32(bytes, 1); bytes[4] = 0; in = (struct fw_input){ .bytes = bytes, .size = 5 };
    CHECK(fw_take_path(&in, path) == -1 && errno == EILSEQ);
    fw_put32(bytes, 0); in = (struct fw_input){ .bytes = bytes, .size = 4 };
    CHECK(fw_take_path(&in, path) == -1 && errno == EINVAL);
    out.size = 0; CHECK(fw_out64(&out, UINT64_C(0x0123456789abcdef)) == 0);
    static const unsigned char expected[] = {1,0x23,0x45,0x67,0x89,0xab,0xcd,0xef};
    CHECK(out.size == 8 && !memcmp(bytes, expected, 8));
    out.size = FW_FRAME;
    CHECK(fw_out32(&out, 1) == -1 && errno == E2BIG && out.size == FW_FRAME);
    out.size = FW_FRAME + 1;
    CHECK(fw_append(&out, expected, 1) == -1 && errno == E2BIG);
    out.size = FW_FRAME - 4; CHECK(fw_out32(&out, 0) == 0 && out.size == FW_FRAME);
    free(bytes); return 0;
}

static int test_mount_parser_policy(void)
{
    bool ro; uint64_t id; char path[FW_PATH + 1];
    char ordinary[] = "211 177 8:2 /source /root/workspace rw,nosuid,nodev - ext4 /dev/block/data rw,seclabel";
    CHECK(fw_mount_line(ordinary, &id, path, &ro) == 0 && id == 211 && !ro && !strcmp(path, "/root/workspace"));
    char optional[] = "1 2 8:2 / / ro,nosuid shared:1 master:3 - ext4 /dev/root ro";
    CHECK(fw_mount_line(optional, &id, path, &ro) == 0 && id == 1 && ro && !strcmp(path, "/"));
    char escaped[] = "42 2 8:2 /some\\040source /root/workspace/a\\040b rw - ext4 /source rw";
    CHECK(fw_mount_line(escaped, &id, path, &ro) == 0 && !strcmp(path, "/root/workspace/a b"));
    char slash[] = "42 2 8:2 / /root/workspace/a\\134b rw - ext4 /source rw";
    CHECK(fw_mount_line(slash, &id, path, &ro) == 0 && !strcmp(path, "/root/workspace/a\\b"));
    static const char *const invalid[] = { "", "1 2 3", "x 2 8:2 / / rw - ext4 / rw", "0 2 8:2 / / rw - ext4 / rw",
        "18446744073709551616 2 8:2 / / rw - ext4 / rw", "1 2 8:2 / /root//x rw - ext4 / rw",
        "1 2 8:2 / /root/x\\000y rw - ext4 / rw", "1 2 8:2 / / rw,ro - ext4 / rw", "1 2 8:2 / / rwhaha - ext4 / rw",
        "1 2 8:2 / / rw ext4 / rw", "1 2 8:2 / /root/x\\xx rw - ext4 / rw" };
    for (size_t i = 0; i < sizeof(invalid) / sizeof(*invalid); ++i) {
        char line[512]; strcpy(line, invalid[i]);
        CHECK(fw_mount_line(line, &id, path, &ro) == -1);
    }
    /* Root membership remains a parser control; its RW policy is tested separately below. */
    CHECK(fw_mount_class("/", &ro) == 1);
    CHECK(fw_mount_class("/root", &ro) == 1 && !ro);
    CHECK(fw_mount_class("/root/workspace", &ro) == 1 && !ro);
    CHECK(fw_mount_class("/etc/resolv.conf", &ro) == 2 && ro);
    static const char *const readonly[] = { "skills", "agents", "docs", "bin", "lib", "prompts.custom" };
    static const char *const writable[] = { "memory", "scripts", "plugins", "tool-output" };
    for (size_t i = 0; i < sizeof(readonly) / sizeof(*readonly); ++i) {
        CHECK(snprintf(path, sizeof(path), "/root/.aicode/%s", readonly[i]) > 0);
        CHECK(fw_mount_class(path, &ro) == 1 && ro);
    }
    for (size_t i = 0; i < sizeof(writable) / sizeof(*writable); ++i) {
        CHECK(snprintf(path, sizeof(path), "/root/.aicode/%s", writable[i]) > 0);
        CHECK(fw_mount_class(path, &ro) == 1 && !ro);
    }
    CHECK(fw_mount_class("/root/.aicode/credentials", &ro) == 0);
    CHECK(fw_mount_class("/root/.aicode/plugins/child", &ro) == 0);
    CHECK(fw_mount_class("/tmp", &ro) == 0 && fw_mount_class("/data/user/0/com.ajiu", &ro) == 0);
    struct fw_context *ctx = calloc(1, sizeof(*ctx)); CHECK(ctx != NULL);
    CHECK(fw_add_boundary(ctx, 1, "/") == 0 && fw_add_boundary(ctx, 2, "/root") == 0);
    CHECK(fw_add_boundary(ctx, 3, "/root/workspace") == 0);
    CHECK(fw_add_boundary(ctx, 4, "/root/.aicode/plugins") == 0);
    CHECK(fw_add_boundary(ctx, 55, "/root/workspace/deep/unapproved-mount") == 0);
    CHECK(fw_protected(ctx, "/root/workspace/deep") == -1 && errno == EBUSY);
    CHECK(fw_protected(ctx, "/root/workspace/deep/unapproved-mount") == -1);
    CHECK(fw_protected(ctx, "/root/.aicode") == -1);
    CHECK(fw_protected(ctx, "/root/.aicode/plugins") == -1);
    CHECK(fw_protected(ctx, "/root/.aicode/plugins/file") == 0);
    CHECK(fw_protected(ctx, "/root/workspace/unrelated") == 0);
    ctx->mount_count = 3;
    ctx->mounts[0].id = 1; strcpy(ctx->mounts[0].path, "/");
    ctx->mounts[1].id = 2; strcpy(ctx->mounts[1].path, "/root"); ctx->mounts[1].writable = true;
    ctx->mounts[2].id = 4; strcpy(ctx->mounts[2].path, "/root/.aicode/plugins");
    struct fw_identity identity = {0}; identity.st.stx_mode = S_IFREG | 0600; identity.st.stx_mnt_id = 2;
    CHECK(fw_authorize(ctx, -1, &identity, "/root/normal", false) == 0);
    CHECK(fw_authorize(ctx, -1, &identity, "/etc/not-home", false) == -1 && errno == EXDEV);
    CHECK(fw_authorize(ctx, -1, &identity, "/root/.aicode/settings.json", false) == 0);
    CHECK(fw_authorize(ctx, -1, &identity, "/root/.aicode/credentials/key", false) == 0);
    CHECK(fw_authorize(ctx, -1, &identity, "/root/.aicode/plugins/file", false) == -1 && errno == EXDEV);
    CHECK(fw_authorize(ctx, -1, &identity, "/root/workspace/file", false) == -1 && errno == EXDEV);
    identity.st.stx_mnt_id = 55;
    CHECK(fw_authorize(ctx, -1, &identity, "/root/workspace/deep/unapproved-mount/file", false) == -1 && errno == EXDEV);
    identity.st.stx_mnt_id = 4;
    CHECK(fw_authorize(ctx, -1, &identity, "/root/.aicode/plugins/file", false) == 0);
    CHECK(fw_authorize(ctx, -1, &identity, "/root/.aicode/plugins/file", true) == -1 && errno == EROFS);
    identity.st.stx_mnt_id = 1;
    CHECK(fw_authorize(ctx, -1, &identity, "/usr/lib/file", true) == -1 && errno == EROFS);
    CHECK(fw_authorize(ctx, -1, &identity, "/run/ajiu-file-worker", false) == -1 && errno == EACCES);
    for (size_t i = 0; i < ctx->boundary_count; ++i) free(ctx->boundaries[i].path);
    free(ctx->boundaries); free(ctx); return 0;
}

static int test_nonroot_credentials_control(void)
{
    /* Keep the original AppUID/nonroot identification controls independent of the new guest policy. */
    CHECK(fw_credentials_valid(10445,10445,10445,10445,10445,10445));
    CHECK(!fw_credentials_valid(9999,9999,9999,9999,9999,9999));
    CHECK(!fw_credentials_valid(10445,10445,0,10445,10445,10445));
    CHECK(!fw_credentials_valid(10445,10445,10445,10446,10446,10446));
    CHECK(!fw_credentials_valid(10445,10445,10445,10445,10445,0));
    return 0;
}

static int test_default_allow_root_guest_credentials(void)
{
    /* Pure calls to the existing static API: no real UID/capability changes or production guard. */
    CHECK(fw_credentials_valid(0,0,0,0,0,0));
    CHECK(!fw_credentials_valid(0,10445,0,0,0,0));
    CHECK(!fw_credentials_valid(0,0,10445,0,0,0));
    CHECK(!fw_credentials_valid(0,0,0,0,10445,0));
    CHECK(!fw_credentials_valid(0,0,0,0,0,10445));
    CHECK(!fw_credentials_valid(0,0,0,10445,10445,10445));
    CHECK(!fw_credentials_valid(1,1,1,1,1,1));
    return 0;
}

static int test_default_allow_root_guest_rootfs_rw_keeps_host_ro(void)
{
    bool ro = true;
    /* The installed guest OS is writable; changing that must not weaken host read-only binds. */
    CHECK(fw_mount_class("/", &ro) == 1 && !ro);
    static const char *const writable[] = { "/root", "/root/workspace", "/root/.aicode/memory",
        "/root/.aicode/scripts", "/root/.aicode/plugins", "/root/.aicode/tool-output" };
    static const char *const readonly[] = { "/root/.aicode/skills", "/root/.aicode/agents",
        "/root/.aicode/docs", "/root/.aicode/bin", "/root/.aicode/lib", "/root/.aicode/prompts.custom" };
    for (size_t i = 0; i < sizeof(writable) / sizeof(*writable); ++i)
        CHECK(fw_mount_class(writable[i], &ro) == 1 && !ro);
    for (size_t i = 0; i < sizeof(readonly) / sizeof(*readonly); ++i)
        CHECK(fw_mount_class(readonly[i], &ro) == 1 && ro);
    CHECK(fw_mount_class("/etc/resolv.conf", &ro) == 2 && ro);
    CHECK(fw_mount_class("/root/.aicode/credentials", &ro) == 0);
    CHECK(fw_mount_class("/root/.aicode/plugins/unapproved-mount", &ro) == 0);
    return 0;
}

static int test_root_guard_and_app_control(void)
{
    memset(&root_mock, 0, sizeof(root_mock)); root_mock.active = true;
    root_mock.caps[0] = root_mock.caps[1] = root_mock.caps[3] = AJIU_GUEST_CAPS;
    root_mock.securebits = AJIU_GUEST_SECUREBITS; root_mock.nnp = 1;
    CHECK(fw_guard() == 0);
    root_mock.groupc = 1; root_mock.group = 3003; CHECK(fw_guard() == 0);
    root_mock.group = 3004; CHECK(fw_guard() == -1 && errno == EPERM); root_mock.groupc = 0;
    root_mock.caps[0] |= UINT64_C(1) << CAP_SYS_ADMIN; CHECK(fw_guard() == -1 && errno == EPERM);
    root_mock.caps[0] = AJIU_GUEST_CAPS;
    root_mock.caps[3] |= UINT64_C(1) << CAP_SYS_PTRACE; CHECK(fw_guard() == -1 && errno == EPERM);
    root_mock.caps[3] = AJIU_GUEST_CAPS;
    root_mock.caps[2] = 1; CHECK(fw_guard() == -1 && errno == EPERM); root_mock.caps[2] = 0;
    root_mock.caps[4] = 1; CHECK(fw_guard() == -1 && errno == EPERM); root_mock.caps[4] = 0;
    root_mock.securebits |= SECBIT_NO_SETUID_FIXUP; CHECK(fw_guard() == -1 && errno == EPERM);
    root_mock.securebits = AJIU_GUEST_SECUREBITS;
    root_mock.nnp = 0; CHECK(fw_guard() == -1 && errno == EPERM); root_mock.nnp = 1;
    for (size_t i = 0; i < 3; ++i) root_mock.uid[i] = root_mock.gid[i] = 10445;
    memset(root_mock.caps, 0, sizeof(root_mock.caps));
    CHECK(fw_guard() == 0);
    root_mock.caps[3] = 1; CHECK(fw_guard() == -1 && errno == EPERM); root_mock.caps[3] = 0;
    root_mock.uid[2] = 0; CHECK(fw_guard() == -1 && errno == EPERM);
    memset(&root_mock, 0, sizeof(root_mock)); return 0;
}

static int test_metadata_handles(void)
{
    struct fw_identity a = {0}, b;
    a.st.stx_mnt_id = 2; a.st.stx_ino = UINT64_C(0x123456789abcdef); a.st.stx_mode = S_IFREG | 0640;
    a.st.stx_size = UINT64_C(0x1020304050607080); a.st.stx_dev_major = 8; a.st.stx_dev_minor = 129;
    a.st.stx_mtime.tv_sec = 1700000000; a.st.stx_mtime.tv_nsec = 987654321;
    b = a; CHECK(fw_same(&a, &b));
    b.st.stx_mnt_id++; CHECK(!fw_same(&a, &b));
    b = a; b.st.stx_ino++; CHECK(!fw_same(&a, &b));
    b = a; b.st.stx_dev_minor++; CHECK(!fw_same(&a, &b));
    b = a; b.st.stx_mode = S_IFDIR | 0640; CHECK(!fw_same(&a, &b));
    unsigned char bytes[64]; struct fw_output out = { .bytes = bytes };
    CHECK(fw_metadata(&out, &a) == 0 && out.size == 40);
    CHECK(fw_be32(bytes) == 2 && fw_be32(bytes + 4) == (S_IFREG | 0640));
    CHECK(fw_be32(bytes + 8) == 0x10203040 && fw_be32(bytes + 12) == 0x50607080);
    uint64_t millis = ((uint64_t)fw_be32(bytes + 16) << 32) | fw_be32(bytes + 20);
    CHECK(millis == UINT64_C(1700000000987));
    CHECK(fw_be32(bytes + 24) == 0 && fw_be32(bytes + 28) == (uint32_t)makedev(8,129));
    CHECK(fw_be32(bytes + 32) == 0x01234567 && fw_be32(bytes + 36) == 0x89abcdef);
    a.st.stx_mode = S_IFDIR; CHECK(fw_kind(&a) == 1);
    a.st.stx_mode = S_IFLNK; CHECK(fw_kind(&a) == 3);
    static const mode_t unsupported[] = { S_IFIFO, S_IFSOCK, S_IFCHR, S_IFBLK, 0 };
    for (size_t i = 0; i < sizeof(unsupported) / sizeof(*unsupported); ++i) {
        a.st.stx_mode = (uint16_t)unsupported[i];
        CHECK(fw_kind(&a) == -1 && errno == EOPNOTSUPP);
    }
    a.st.stx_mode = S_IFREG; a.st.stx_mtime.tv_sec = -1; out.size = 0;
    CHECK(fw_metadata(&out, &a) == -1 && errno == EOVERFLOW);
    struct fw_context *ctx = calloc(1, sizeof(*ctx)); CHECK(ctx != NULL); ctx->next_handle = 1;
    for (size_t i = 0; i < FW_HANDLES; ++i) {
        struct fw_handle *h = fw_new_handle(ctx);
        CHECK(h != NULL && h->number == i + 1 && h->fd == -1);
        CHECK(fw_find_handle(ctx, h->number) == h);
    }
    CHECK(fw_new_handle(ctx) == NULL && errno == EMFILE);
    CHECK(fw_find_handle(ctx, 0) == NULL && errno == EBADF);
    CHECK(fw_find_handle(ctx, 100) == NULL && errno == EBADF);
    CHECK(fw_drop_handle(ctx, &ctx->handles[0], false) == 0);
    CHECK(fw_find_handle(ctx, 1) == NULL && errno == EBADF);
    struct fw_handle *fresh = fw_new_handle(ctx);
    CHECK(fresh != NULL && fresh->number == 33);
    CHECK(fw_drop_handle(ctx, fresh, false) == 0);
    ctx->next_handle = UINT32_MAX;
    fresh = fw_new_handle(ctx); CHECK(fresh != NULL && fresh->number == UINT32_MAX);
    CHECK(fw_drop_handle(ctx, fresh, false) == 0);
    CHECK(fw_new_handle(ctx) == NULL && errno == EOVERFLOW);
    free(ctx); return 0;
}

static int test_temporary_files(int dir)
{
    int fd = openat(dir, "frame", O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    CHECK(fd >= 0);
    unsigned char bytes[FW_HEADER], readback[FW_HEADER]; request_header(bytes, FW_STAT, 7, 0);
    CHECK(fw_nonblock(fd) == 0 && (fcntl(fd, F_GETFL) & O_NONBLOCK));
    CHECK(fw_transfer(fd, bytes, sizeof(bytes), true, false, fw_now() + 1000) == 0);
    CHECK(lseek(fd, 0, SEEK_SET) == 0);
    CHECK(fw_transfer(fd, readback, sizeof(readback), false, false, fw_now() + 1000) == 0 && !memcmp(bytes, readback, sizeof(bytes)));
    CHECK(fw_transfer(fd, readback, sizeof(readback), false, true, fw_now() + 1000) == 1);
    CHECK(fw_transfer(fd, readback, sizeof(readback), false, false, fw_now() + 1000) == -1 && errno == EPROTO);
    CHECK(lseek(fd, 0, SEEK_SET) == 0 && ftruncate(fd, 7) == 0);
    CHECK(fw_transfer(fd, readback, sizeof(readback), false, true, fw_now() + 1000) == -1 && errno == EPROTO);
    CHECK(lseek(fd, 0, SEEK_SET) == 0);
    CHECK(fw_transfer(fd, readback, 1, false, false, fw_now() - 1) == -1 && errno == ETIMEDOUT);
    fw_stopping = 1;
    CHECK(fw_transfer(fd, readback, 1, false, false, fw_now() + 1000) == -1 && errno == ECANCELED);
    fw_stopping = 0;
    CHECK(close(fd) == 0);
    int pin = fw_open(dir, "frame", FW_PF, 0, FW_RESOLVE);
    CHECK(pin >= 0);
    struct fw_identity identity;
    CHECK(fw_identify(pin, &identity) == 0 && fw_kind(&identity) == 2);
    struct fw_context *policy = calloc(1, sizeof(*policy)); CHECK(policy != NULL);
    policy->mount_count = 1; policy->mounts[0].id = identity.st.stx_mnt_id;
    policy->mounts[0].writable = true; strcpy(policy->mounts[0].path, "/root/.aicode/plugins");
    CHECK(fw_authorize(policy, pin, &identity, "/root/.aicode/plugins/fixture", true) == 0);
    policy->mounts[0].writable = false;
    CHECK(fw_authorize(policy, pin, &identity, "/root/.aicode/plugins/fixture", true) == -1 && errno == EROFS);
    free(policy);
    CHECK(fw_named_same(dir, "frame", &identity) == 0);
    CHECK(renameat(dir, "frame", dir, "original-frame") == 0);
    fd = openat(dir, "frame", O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600); CHECK(fd >= 0);
    CHECK(fw_named_same(dir, "frame", &identity) == -1 && errno == ESTALE);
    CHECK(fw_fd_same(pin, &identity) == 0);
    CHECK(close(fd) == 0 && close(pin) == 0);
    fd = openat(dir, "owned", O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600); CHECK(fd >= 0);
    struct fw_context cleanup_context = {0};
    struct fw_handle h; fw_handle_init(&h); h.number = 1; h.writing = true; h.fd = fd; h.parent = fw_dup(dir);
    CHECK(h.parent >= 0 && fw_identify(dir, &h.parent_identity) == 0 && fw_identify(fd, &h.identity) == 0);
    strcpy(h.temporary, "owned");
    CHECK(fw_drop_handle(&cleanup_context, &h, true) == 0 && h.number == 0);
    struct stat st;
    CHECK(fstatat(dir, "owned", &st, AT_SYMLINK_NOFOLLOW) == -1 && errno == ENOENT);
    fd = openat(dir, "changed", O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600); CHECK(fd >= 0);
    fw_handle_init(&h); h.number = 2; h.writing = true; h.fd = fd; h.parent = fw_dup(dir);
    CHECK(h.parent >= 0 && fw_identify(dir, &h.parent_identity) == 0 && fw_identify(fd, &h.identity) == 0);
    strcpy(h.temporary, "changed");
    CHECK(renameat(dir, "changed", dir, "saved-owned") == 0);
    int foreign = openat(dir, "changed", O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600); CHECK(foreign >= 0);
    CHECK(write(foreign, "untouched", 9) == 9);
    CHECK(fw_drop_handle(&cleanup_context, &h, true) == -1 && errno == ESTALE && h.number == 0);
    CHECK(cleanup_context.cleanup_failed);
    CHECK(fstat(foreign, &st) == 0 && st.st_size == 9);
    CHECK(pread(foreign, readback, 9, 0) == 9 && !memcmp(readback, "untouched", 9));
    CHECK(close(foreign) == 0);
    fd = openat(dir, "committed", O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600); CHECK(fd >= 0);
    fw_handle_init(&h); h.number = 3; h.writing = true; h.committed = true; h.fd = fd; h.parent = fw_dup(dir);
    strcpy(h.temporary, "committed");
    CHECK(fw_drop_handle(&cleanup_context, &h, true) == 0 && fstatat(dir, "committed", &st, AT_SYMLINK_NOFOLLOW) == 0);
    const char *const files[] = { "frame", "original-frame", "saved-owned", "changed", "committed" };
    for (size_t i = 0; i < sizeof(files) / sizeof(*files); ++i) CHECK(unlinkat(dir, files[i], 0) == 0);
    return 0;
}

static struct fw_context *test_context(int directory)
{
    struct fw_context *ctx = calloc(1, sizeof(*ctx)); CHECK(ctx != NULL);
    ctx->root = fw_dup(directory); CHECK(ctx->root >= 0);
    ctx->proc = ctx->proc_self = ctx->proc_fds = -1;
    ctx->next_handle = 1; ctx->deadline = fw_now() + FW_WAIT_MS;
    for (size_t i = 0; i < FW_HANDLES; ++i) fw_handle_init(&ctx->handles[i]);
    ctx->mount_count = 1; ctx->mounts[0].fd = fw_dup(directory); CHECK(ctx->mounts[0].fd >= 0);
    CHECK(fw_identify(ctx->root, &ctx->mounts[0].identity) == 0);
    ctx->mounts[0].id = ctx->mounts[0].identity.st.stx_mnt_id;
    ctx->mounts[0].writable = true; strcpy(ctx->mounts[0].path, "/");
    CHECK(fw_add_boundary(ctx, ctx->mounts[0].id, "/") == 0);
    CHECK(synthetic_root < 0); synthetic_root = ctx->root;
    return ctx;
}

static int test_directory(int parent, const char *name)
{
    CHECK(mkdirat(parent, name, 0700) == 0);
    int directory = openat(parent, name, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    CHECK(directory >= 0); return directory;
}
static void test_file(int directory, const char *name)
{
    int fd = openat(directory, name, O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    CHECK(fd >= 0 && write(fd, "foreign-preserved", 17) == 17 && close(fd) == 0);
}
static void test_foreign_preserved(int directory, const char *name)
{
    int fd = openat(directory, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW); CHECK(fd >= 0);
    struct stat identity; unsigned char bytes[18];
    CHECK(fstat(fd, &identity) == 0 && S_ISREG(identity.st_mode) && identity.st_size == 17);
    CHECK(read(fd, bytes, sizeof(bytes)) == 17 && !memcmp(bytes, "foreign-preserved", 17));
    CHECK(close(fd) == 0);
}
static void test_list_contents(struct fw_context *ctx, const char *const *names, size_t count)
{
    unsigned char request[16]; struct fw_output payload = { .bytes = request };
    CHECK(fw_text(&payload, "/") == 0);
    unsigned char *bytes = calloc(1, FW_FRAME); CHECK(bytes != NULL);
    struct fw_output response = { .bytes = bytes, .size = 4 };
    struct fw_input input = { .bytes = request, .size = payload.size };
    CHECK(fw_list_request(ctx, &input, &response) == 0);
    CHECK(response.size >= 8 && fw_be32(bytes + 4) == count);
    bool seen[8] = {0}; CHECK(count <= sizeof(seen) / sizeof(*seen));
    size_t offset = 8;
    for (size_t i = 0; i < count; ++i) {
        CHECK(response.size - offset >= 4);
        uint32_t length = fw_be32(bytes + offset); offset += 4;
        CHECK(length <= FW_NAME && response.size - offset >= (size_t)length + 40);
        size_t match = count;
        for (size_t j = 0; j < count; ++j)
            if (strlen(names[j]) == length && !memcmp(names[j], bytes + offset, length)) match = j;
        CHECK(match < count && !seen[match]); seen[match] = true;
        offset += length;
        CHECK(fw_be32(bytes + offset) == 2 && (fw_be32(bytes + offset + 4) & S_IFMT) == S_IFREG);
        offset += 40;
    }
    CHECK(offset == response.size);
    for (size_t i = 0; i < count; ++i) CHECK(seen[i]);
    free(bytes);
}

static int test_list_handler(int temporary)
{
    int directory = test_directory(temporary, "list-handler");
    const char *const allowed[] = { "alpha", "omega", "ordinary" };
    const char *const restricted[] = { "proc", "dev", "sys", "tmp", "run", "unapproved", "synthetic-special" };
    for (size_t i = 0; i < sizeof(allowed) / sizeof(*allowed); ++i) test_file(directory, allowed[i]);
    for (size_t i = 0; i < sizeof(restricted) / sizeof(*restricted); ++i) test_file(directory, restricted[i]);
    struct fw_context *ctx = test_context(directory);
    uint64_t unknown_mount = ctx->mounts[0].id == UINT64_MAX ? UINT64_MAX - 1 : ctx->mounts[0].id + 1;
    CHECK(fw_add_boundary(ctx, unknown_mount, "/unapproved") == 0);
    test_reset_faults(); faults.open_name = "synthetic-special"; faults.special = true;
    faults.watch_name = "proc";
    test_list_contents(ctx, allowed, sizeof(allowed) / sizeof(*allowed));
    CHECK(faults.watched_opens == 0 && faults.watched_unlinks == 0);
    unsigned char request[16]; struct fw_output payload = { .bytes = request };
    CHECK(fw_text(&payload, "/") == 0);
    unsigned char *bytes = calloc(1, FW_FRAME); CHECK(bytes != NULL);
    const int errors[] = { EACCES, EIO, ESTALE };
    for (size_t i = 0; i < sizeof(errors) / sizeof(*errors); ++i) {
        for (unsigned int stage = 0; stage < 2; ++stage) {
            test_reset_faults(); faults.open_name = "ordinary";
            if (!stage) faults.open_error = errors[i]; else faults.stat_error = errors[i];
            struct fw_output response = { .bytes = bytes, .size = 4 };
            struct fw_input input = { .bytes = request, .size = payload.size };
            CHECK(fw_list_request(ctx, &input, &response) == -1 && errno == errors[i]);
        }
    }
    test_reset_faults(); faults.open_name = "."; faults.open_error = EACCES;
    struct fw_output response = { .bytes = bytes, .size = 4 };
    struct fw_input input = { .bytes = request, .size = payload.size };
    CHECK(fw_list_request(ctx, &input, &response) == -1 && errno == EACCES);
    test_reset_faults(); ctx->mount_count = 0;
    input = (struct fw_input){ .bytes = request, .size = payload.size };
    response.size = 4;
    CHECK(fw_list_request(ctx, &input, &response) == -1 && errno == EXDEV);
    ctx->mount_count = 1;
    test_reset_faults(); faults.open_name = "synthetic-special"; faults.special = true;
    test_list_contents(ctx, allowed, sizeof(allowed) / sizeof(*allowed));
    test_reset_faults(); free(bytes);
    CHECK(fw_cleanup(ctx) == 0 && !ctx->cleanup_failed); synthetic_root = -1; free(ctx);
    for (size_t i = 0; i < sizeof(allowed) / sizeof(*allowed); ++i) CHECK(unlinkat(directory, allowed[i], 0) == 0);
    for (size_t i = 0; i < sizeof(restricted) / sizeof(*restricted); ++i) CHECK(unlinkat(directory, restricted[i], 0) == 0);
    CHECK(close(directory) == 0 && unlinkat(temporary, "list-handler", AT_REMOVEDIR) == 0);
    return 0;
}

static uint32_t test_begin_handler(struct fw_context *ctx)
{
    unsigned char request[32], bytes[16]; struct fw_output payload = { .bytes = request };
    CHECK(fw_text(&payload, "/target") == 0 && fw_out32(&payload, 0) == 0);
    struct fw_input input = { .bytes = request, .size = payload.size };
    struct fw_output response = { .bytes = bytes, .size = 4 };
    CHECK(fw_begin_write_request(ctx, &input, &response) == 0 && response.size == 8);
    uint32_t number = fw_be32(bytes + 4); CHECK(number != 0); return number;
}
static int test_cleanup_handlers(int temporary)
{
    const char *const cases[] = { "abort-handler", "eof-handler", "begin-handler" };
    for (size_t scenario = 0; scenario < sizeof(cases) / sizeof(*cases); ++scenario) {
        int directory = test_directory(temporary, cases[scenario]);
        struct fw_context *ctx = test_context(directory);
        char temporary_name[64], saved_name[64];
        test_reset_faults();
        if (scenario == 2) {
            faults.begin_swap = true; faults.directory = directory;
            unsigned char request[32], bytes[16]; struct fw_output payload = { .bytes = request };
            CHECK(fw_text(&payload, "/target") == 0 && fw_out32(&payload, 0) == 0);
            struct fw_input input = { .bytes = request, .size = payload.size };
            struct fw_output response = { .bytes = bytes, .size = 4 };
            CHECK(fw_begin_write_request(ctx, &input, &response) == -1 && errno == ESTALE);
            CHECK(faults.created[0] && ctx->cleanup_failed);
            strcpy(temporary_name, faults.created); strcpy(saved_name, "saved-begin-owned");
            for (size_t i = 0; i < FW_HANDLES; ++i) CHECK(ctx->handles[i].number == 0);
        } else {
            uint32_t number = test_begin_handler(ctx);
            struct fw_handle *h = fw_find_handle(ctx, number); CHECK(h != NULL);
            strcpy(temporary_name, h->temporary); strcpy(saved_name, "saved-handler-owned");
            CHECK(renameat(directory, temporary_name, directory, saved_name) == 0);
            test_file(directory, temporary_name);
            faults.watch_name = temporary_name;
            if (scenario == 0) {
                unsigned char request[4]; fw_put32(request, number);
                struct fw_input input = { .bytes = request, .size = sizeof(request) };
                CHECK(fw_close_request(ctx, &input, true) == -1 && errno == ESTALE);
                CHECK(ctx->cleanup_failed && fw_find_handle(ctx, number) == NULL && errno == EBADF);
                CHECK(faults.watched_opens == 1 && faults.watched_unlinks == 0);
            }
        }
        test_foreign_preserved(directory, temporary_name);
        test_reset_faults(); faults.watch_name = temporary_name;
        CHECK(fw_cleanup(ctx) == -1 && errno == EUCLEAN && ctx->cleanup_failed);
        CHECK(faults.watched_opens == (scenario == 1 ? 1U : 0U) && faults.watched_unlinks == 0);
        for (size_t i = 0; i < FW_HANDLES; ++i) CHECK(ctx->handles[i].number == 0);
        test_foreign_preserved(directory, temporary_name);
        synthetic_root = -1; free(ctx); test_reset_faults();
        CHECK(unlinkat(directory, temporary_name, 0) == 0 && unlinkat(directory, saved_name, 0) == 0);
        CHECK(close(directory) == 0 && unlinkat(temporary, cases[scenario], AT_REMOVEDIR) == 0);
    }
    int directory = test_directory(temporary, "abort-success");
    struct fw_context *ctx = test_context(directory);
    uint32_t number = test_begin_handler(ctx);
    struct fw_handle *h = fw_find_handle(ctx, number); CHECK(h != NULL);
    char temporary_name[64]; strcpy(temporary_name, h->temporary);
    unsigned char request[4]; fw_put32(request, number);
    struct fw_input input = { .bytes = request, .size = sizeof(request) };
    CHECK(fw_close_request(ctx, &input, true) == 0 && !ctx->cleanup_failed);
    struct stat identity;
    CHECK(fstatat(directory, temporary_name, &identity, AT_SYMLINK_NOFOLLOW) == -1 && errno == ENOENT);
    CHECK(fw_cleanup(ctx) == 0 && !ctx->cleanup_failed); synthetic_root = -1; free(ctx);
    CHECK(close(directory) == 0 && unlinkat(temporary, "abort-success", AT_REMOVEDIR) == 0);
    return 0;
}

static size_t test_path_response(struct fw_context *ctx, uint16_t op, const char *path, bool has_flag,
                                 uint32_t flag, unsigned char *bytes)
{
    unsigned char request[FW_PATH + 8] = {0}, header_bytes[FW_HEADER] = {0};
    size_t length = strlen(path); CHECK(length > 0 && length <= FW_PATH);
    fw_put32(request, (uint32_t)length); memcpy(request + 4, path, length);
    struct fw_output payload = { .bytes = request, .size = length + 4 };
    if (has_flag) CHECK(fw_out32(&payload, flag) == 0);
    request_header(header_bytes, op, 73, (uint32_t)payload.size);
    struct fw_header header;
    CHECK(fw_decode_header(header_bytes, &header) == 0 && header.id == 73 && header.op == op &&
        header.length == payload.size);
    struct fw_output response = { .bytes = bytes, .size = 4 }; fw_put32(bytes, 0);
    if (fw_dispatch(ctx, header.op, request, header.length, &response) < 0) {
        CHECK(errno > 0); fw_put32(bytes, (uint32_t)errno); response.size = 4;
    }
    fw_encode_header(header_bytes, &header, (uint32_t)response.size);
    CHECK(fw_be16(header_bytes + 6) == (op | 0x8000U) && fw_be32(header_bytes + 8) == 73 &&
        fw_be32(header_bytes + 12) == response.size);
    return response.size;
}
static void test_path_stat(struct fw_context *ctx, const char *path, bool follow, int directory, const char *name)
{
    unsigned char bytes[FW_PATH + 16] = {0};
    size_t size = test_path_response(ctx, FW_STAT, path, true, follow ? 1U : 0U, bytes);
    CHECK(size == 44 && fw_be32(bytes) == 0);
    int fd = fw_open(directory, name, FW_PF, 0, FW_RESOLVE); CHECK(fd >= 0);
    struct fw_identity expected; CHECK(fw_identify(fd, &expected) == 0 && close(fd) == 0);
    CHECK(fw_be32(bytes + 4) == (uint32_t)fw_kind(&expected) && fw_be32(bytes + 8) == expected.st.stx_mode);
    CHECK((((uint64_t)fw_be32(bytes + 12) << 32) | fw_be32(bytes + 16)) == expected.st.stx_size);
    CHECK((((uint64_t)fw_be32(bytes + 36) << 32) | fw_be32(bytes + 40)) == expected.st.stx_ino);
}
static void test_path_resolved(struct fw_context *ctx, const char *path, bool follow, const char *expected)
{
    unsigned char bytes[FW_PATH + 16] = {0};
    size_t size = test_path_response(ctx, FW_RESOLVE_PATH, path, true, follow ? 1U : 0U, bytes);
    CHECK(fw_path_valid(expected) == 0);
    CHECK(size == strlen(expected) + 8 && fw_be32(bytes) == 0 && fw_be32(bytes + 4) == strlen(expected) &&
        !memcmp(bytes + 8, expected, strlen(expected)));
}
static void test_path_error(struct fw_context *ctx, uint16_t op, const char *path, bool has_flag, uint32_t flag, int error)
{
    unsigned char bytes[FW_PATH + 16]; memset(bytes, 0xa5, sizeof(bytes));
    CHECK(test_path_response(ctx, op, path, has_flag, flag, bytes) == 4 && fw_be32(bytes) == (uint32_t)error);
}
static int test_path_handlers(int temporary)
{
    int directory = test_directory(temporary, "path-handler");
    int sub = test_directory(directory, "sub"), actual = test_directory(directory, "actual");
    int nested = test_directory(actual, "nested"), trailing = test_directory(directory, "trailingdir");
    const char *const root_files[] = { "a", "file", "proc", "dev", "sys", "tmp", "run" };
    for (size_t i = 0; i < sizeof(root_files) / sizeof(*root_files); ++i) test_file(directory, root_files[i]);
    test_file(sub, "a"); test_file(actual, "file");
    const char *const links[] = { "guestlink", "relative", "parentlink", "filelink", "proclink", "tmplink", "devlink", "loop" };
    const char *const destinations[] = { "/actual/nested", "actual/nested", "/../../actual/nested", "/a", "/proc", "/tmp", "/dev", "loop" };
    for (size_t i = 0; i < sizeof(links) / sizeof(*links); ++i) CHECK(symlinkat(destinations[i], directory, links[i]) == 0);
    struct fw_context *ctx = test_context(directory); test_reset_faults();
    test_path_stat(ctx, "/./a", true, directory, "a");
    test_path_stat(ctx, "//./a", true, directory, "a");
    test_path_stat(ctx, "/sub//a", true, sub, "a");
    test_path_stat(ctx, "/sub/./a", true, sub, "a");
    test_path_stat(ctx, "/trailingdir/", true, trailing, ".");
    test_path_stat(ctx, "/trailingdir///", false, trailing, ".");
    test_path_stat(ctx, "/guestlink/../file", true, actual, "file");
    test_path_stat(ctx, "/relative/../file", true, actual, "file");
    test_path_stat(ctx, "/parentlink/../file", true, actual, "file");
    test_path_stat(ctx, "/../../file", true, directory, "file");
    test_path_stat(ctx, "/sub/../file", true, directory, "file");
    test_path_stat(ctx, "/guestlink", false, directory, "guestlink");
    test_path_stat(ctx, "/guestlink/", false, directory, "guestlink");
    test_path_stat(ctx, "/guestlink///", false, directory, "guestlink");
    test_path_stat(ctx, "/guestlink///", true, nested, ".");
    test_path_stat(ctx, "/guestlink/../file", false, actual, "file");
    test_path_stat(ctx, "/filelink/", false, directory, "filelink");
    test_path_resolved(ctx, "/./a", true, "/a");
    test_path_resolved(ctx, "/sub//a", true, "/sub/a");
    test_path_resolved(ctx, "/trailingdir/", true, "/trailingdir");
    test_path_resolved(ctx, "/guestlink/../file", true, "/actual/file");
    test_path_resolved(ctx, "/guestlink/..", true, "/actual");
    test_path_resolved(ctx, "/guestlink", false, "/guestlink");
    test_path_resolved(ctx, "/guestlink/", false, "/guestlink");
    test_path_resolved(ctx, "/guestlink///", false, "/guestlink");
    test_path_resolved(ctx, "/guestlink/../file", false, "/actual/file");
    test_path_resolved(ctx, "/filelink/", false, "/filelink");
    test_path_resolved(ctx, "/sub//./a", true, "/sub/a");
    test_path_resolved(ctx, "/guestlink/../../../../file", true, "/file");
    test_path_resolved(ctx, "/absent/child", true, "/absent/child");
    const char *const missing[] = { "/absent/../file", "/absent/./file", "/absent//file", "/absent/", "/guestlink/absent/../file" };
    for (size_t i = 0; i < sizeof(missing) / sizeof(*missing); ++i) {
        test_path_error(ctx, FW_RESOLVE_PATH, missing[i], true, 1, ENOENT);
        test_path_error(ctx, FW_BEGIN_WRITE, missing[i], true, 0, ENOENT);
    }
    const char *const not_directories[] = { "/a/", "/a/..", "/a/./x", "/filelink/", "/filelink/.." };
    for (size_t i = 0; i < sizeof(not_directories) / sizeof(*not_directories); ++i) {
        test_path_error(ctx, FW_STAT, not_directories[i], true, 1, ENOTDIR);
        if (strcmp(not_directories[i], "/filelink/"))
            test_path_error(ctx, FW_STAT, not_directories[i], true, 0, ENOTDIR);
    }
    const char *const denied[] = { "/./proc", "//dev", "/sub/../sys", "/tmp/../file", "/sub/../run/../file",
        "/proclink/self", "/proclink/../file", "/tmplink/../file", "/devlink/null", "/guestlink/../../proc", "/guestlink/../../tmp/../file" };
    for (size_t i = 0; i < sizeof(denied) / sizeof(*denied); ++i) {
        test_reset_faults(); faults.watch_name = "proc";
        test_path_error(ctx, FW_STAT, denied[i], true, 1, EACCES);
        CHECK(faults.watched_opens == 0 && faults.watched_unlinks == 0);
        test_path_error(ctx, FW_RESOLVE_PATH, denied[i], true, 1, EACCES);
        test_path_error(ctx, FW_BEGIN_WRITE, denied[i], true, 0, EACCES);
    }
    test_reset_faults();
    test_path_error(ctx, FW_STAT, "/loop", true, 1, ELOOP);
    test_path_error(ctx, FW_STAT, "./a", true, 1, EINVAL);
    test_path_error(ctx, FW_STAT, "/./a", true, 2, EINVAL);
    CHECK(!ctx->cleanup_failed && ctx->next_handle == 1);
    CHECK(fw_cleanup(ctx) == 0); synthetic_root = -1; free(ctx);
    for (size_t i = 0; i < sizeof(links) / sizeof(*links); ++i) CHECK(unlinkat(directory, links[i], 0) == 0);
    for (size_t i = 0; i < sizeof(root_files) / sizeof(*root_files); ++i) CHECK(unlinkat(directory, root_files[i], 0) == 0);
    CHECK(unlinkat(sub, "a", 0) == 0 && unlinkat(actual, "file", 0) == 0);
    CHECK(close(nested) == 0 && unlinkat(actual, "nested", AT_REMOVEDIR) == 0);
    CHECK(close(sub) == 0 && close(actual) == 0 && close(trailing) == 0);
    CHECK(unlinkat(directory, "sub", AT_REMOVEDIR) == 0 && unlinkat(directory, "actual", AT_REMOVEDIR) == 0 &&
        unlinkat(directory, "trailingdir", AT_REMOVEDIR) == 0);
    CHECK(close(directory) == 0 && unlinkat(temporary, "path-handler", AT_REMOVEDIR) == 0);
    return 0;
}

static void test_entry_identity(int directory, const char *name, const struct stat *expected)
{
    struct stat current;
    CHECK(fstatat(directory, name, &current, AT_SYMLINK_NOFOLLOW) == 0);
    CHECK(current.st_dev == expected->st_dev && current.st_ino == expected->st_ino && current.st_mode == expected->st_mode);
}
static void test_target_unchanged(int directory, int target, const struct stat *expected_dir, const struct stat *expected_file)
{
    test_entry_identity(directory, "target", expected_dir);
    test_entry_identity(target, "known", expected_file);
    test_foreign_preserved(target, "known");
}
static void test_missing_entry(int directory, const char *name)
{
    struct stat identity;
    CHECK(fstatat(directory, name, &identity, AT_SYMLINK_NOFOLLOW) == -1 && errno == ENOENT);
}
static void test_rename_response(struct fw_context *ctx, const char *source, const char *target, uint32_t overwrite, int error)
{
    unsigned char request[FW_PATH * 2 + 12] = {0}, bytes[16] = {0};
    size_t source_length = strlen(source), target_length = strlen(target);
    CHECK(source_length > 0 && source_length <= FW_PATH && target_length > 0 && target_length <= FW_PATH);
    fw_put32(request, (uint32_t)source_length); memcpy(request + 4, source, source_length);
    size_t offset = source_length + 4;
    fw_put32(request + offset, (uint32_t)target_length); memcpy(request + offset + 4, target, target_length);
    offset += target_length + 4; fw_put32(request + offset, overwrite); offset += 4;
    struct fw_output response = { .bytes = bytes, .size = 4 };
    if (fw_dispatch(ctx, FW_RENAME, request, offset, &response) < 0) {
        CHECK(errno > 0); fw_put32(bytes, (uint32_t)errno); response.size = 4;
    }
    CHECK(response.size == 4 && fw_be32(bytes) == (uint32_t)error);
}
static int test_nofollow_mutations(int temporary)
{
    int directory = test_directory(temporary, "nofollow-mutations");
    int target = test_directory(directory, "target"); test_file(target, "known");
    struct stat target_before, file_before;
    CHECK(fstat(target, &target_before) == 0 && fstatat(target, "known", &file_before, AT_SYMLINK_NOFOLLOW) == 0);
    struct fw_context *ctx = test_context(directory); test_reset_faults();
    const char *const suffixes[] = { "/", "///" };
    for (size_t i = 0; i < sizeof(suffixes) / sizeof(*suffixes); ++i) {
        char source[64], destination[64];
        CHECK(symlinkat("/target", directory, "delete-link") == 0);
        CHECK(snprintf(source, sizeof(source), "/delete-link%s", suffixes[i]) > 0);
        test_path_stat(ctx, source, false, directory, "delete-link");
        test_path_resolved(ctx, source, false, "/delete-link");
        unsigned char bytes[16] = {0};
        CHECK(test_path_response(ctx, FW_DELETE, source, true, 1, bytes) == 4 && fw_be32(bytes) == 0);
        test_missing_entry(directory, "delete-link");
        test_target_unchanged(directory, target, &target_before, &file_before);
        CHECK(symlinkat("/target", directory, "rename-link") == 0);
        struct stat link_before;
        CHECK(fstatat(directory, "rename-link", &link_before, AT_SYMLINK_NOFOLLOW) == 0 && S_ISLNK(link_before.st_mode));
        CHECK(snprintf(source, sizeof(source), "/rename-link%s", suffixes[i]) > 0);
        test_rename_response(ctx, source, "/renamed-link", 0, 0);
        test_missing_entry(directory, "rename-link"); test_entry_identity(directory, "renamed-link", &link_before);
        test_target_unchanged(directory, target, &target_before, &file_before);
        CHECK(symlinkat("/target", directory, "destination-link") == 0);
        CHECK(snprintf(source, sizeof(source), "/renamed-link%s", suffixes[i]) > 0 &&
            snprintf(destination, sizeof(destination), "/destination-link%s", suffixes[i]) > 0);
        test_rename_response(ctx, source, destination, 1, 0);
        test_missing_entry(directory, "renamed-link"); test_entry_identity(directory, "destination-link", &link_before);
        test_target_unchanged(directory, target, &target_before, &file_before);
        CHECK(test_path_response(ctx, FW_DELETE, destination, true, 0, bytes) == 4 && fw_be32(bytes) == 0);
        test_missing_entry(directory, "destination-link");
        test_target_unchanged(directory, target, &target_before, &file_before);
    }
    CHECK(symlinkat("/target", directory, "readonly-link") == 0 && symlinkat("/target", directory, "protected-link") == 0);
    struct stat readonly_before, protected_before;
    CHECK(fstatat(directory, "readonly-link", &readonly_before, AT_SYMLINK_NOFOLLOW) == 0 &&
        fstatat(directory, "protected-link", &protected_before, AT_SYMLINK_NOFOLLOW) == 0);
    ctx->mounts[0].writable = false;
    test_path_error(ctx, FW_DELETE, "/readonly-link///", true, 1, EROFS);
    test_rename_response(ctx, "/readonly-link///", "/renamed-readonly", 0, EROFS);
    test_entry_identity(directory, "readonly-link", &readonly_before);
    test_target_unchanged(directory, target, &target_before, &file_before);
    ctx->mounts[0].writable = true;
    CHECK(fw_add_boundary(ctx, ctx->mounts[0].id, "/protected-link") == 0);
    test_path_error(ctx, FW_DELETE, "/protected-link///", true, 1, EBUSY);
    test_rename_response(ctx, "/protected-link///", "/renamed-protected", 0, EBUSY);
    test_rename_response(ctx, "/readonly-link///", "/protected-link///", 1, EBUSY);
    test_entry_identity(directory, "protected-link", &protected_before);
    test_entry_identity(directory, "readonly-link", &readonly_before);
    test_missing_entry(directory, "renamed-readonly"); test_missing_entry(directory, "renamed-protected");
    test_target_unchanged(directory, target, &target_before, &file_before);
    int ordinary = test_directory(directory, "ordinary-dir"); test_file(ordinary, "inside");
    test_path_stat(ctx, "/ordinary-dir///", false, ordinary, ".");
    test_rename_response(ctx, "/ordinary-dir///", "/moved-dir", 0, 0);
    test_missing_entry(directory, "ordinary-dir");
    test_path_stat(ctx, "/moved-dir/", false, ordinary, ".");
    CHECK(close(ordinary) == 0);
    unsigned char bytes[16] = {0};
    CHECK(test_path_response(ctx, FW_DELETE, "/moved-dir///", true, 1, bytes) == 4 && fw_be32(bytes) == 0);
    test_missing_entry(directory, "moved-dir");
    test_target_unchanged(directory, target, &target_before, &file_before);
    CHECK(!ctx->cleanup_failed && fw_cleanup(ctx) == 0); synthetic_root = -1; free(ctx);
    CHECK(unlinkat(directory, "readonly-link", 0) == 0 && unlinkat(directory, "protected-link", 0) == 0);
    CHECK(unlinkat(target, "known", 0) == 0 && close(target) == 0 && unlinkat(directory, "target", AT_REMOVEDIR) == 0);
    CHECK(close(directory) == 0 && unlinkat(temporary, "nofollow-mutations", AT_REMOVEDIR) == 0);
    return 0;
}

static int test_default_allow_root_guest_atomic_overwrite_metadata(int temporary)
{
    /* Ordinary owned files only: exercises the existing handlers, not a root guest or a mounted OS. */
    static const mode_t modes[] = { 0644, 0755, 0640 };
    const char replacement[] = "rootguest edited content\n";
    for (size_t scenario = 0; scenario < sizeof(modes) / sizeof(*modes); ++scenario) {
        int directory = test_directory(temporary, "rootguest-atomic-overwrite");
        test_file(directory, "target");
        CHECK(fchmodat(directory, "target", modes[scenario], 0) == 0);
        struct stat before, pending, after;
        CHECK(fstatat(directory, "target", &before, AT_SYMLINK_NOFOLLOW) == 0);
        struct fw_context *ctx = test_context(directory); test_reset_faults();
        unsigned char request[128] = {0}, bytes[16] = {0};
        struct fw_output payload = { .bytes = request };
        CHECK(fw_text(&payload, "/target") == 0 && fw_out32(&payload, 1) == 0);
        struct fw_input input = { .bytes = request, .size = payload.size };
        struct fw_output response = { .bytes = bytes, .size = 4 };
        CHECK(fw_begin_write_request(ctx, &input, &response) == 0 && response.size == 8);
        uint32_t number = fw_be32(bytes + 4);
        struct fw_handle *h = fw_find_handle(ctx, number); CHECK(h != NULL && h->original >= 0);
        char temporary_name[64]; strcpy(temporary_name, h->temporary);
        CHECK(fstat(h->fd, &pending) == 0 && S_ISREG(pending.st_mode) &&
            (pending.st_mode & 07777) == 0600 && pending.st_nlink == 1);
        CHECK(fstatat(directory, "target", &after, AT_SYMLINK_NOFOLLOW) == 0 &&
            after.st_ino == before.st_ino && after.st_mode == before.st_mode &&
            after.st_uid == before.st_uid && after.st_gid == before.st_gid);
        test_foreign_preserved(directory, "target");
        payload.size = 0;
        CHECK(fw_out32(&payload, number) == 0 && fw_out32(&payload, (uint32_t)(sizeof(replacement) - 1)) == 0 &&
            fw_append(&payload, replacement, sizeof(replacement) - 1) == 0);
        input = (struct fw_input){ .bytes = request, .size = payload.size };
        CHECK(fw_write_chunk_request(ctx, &input) == 0);
        CHECK(fstat(h->fd, &pending) == 0 && (pending.st_mode & 07777) == 0600);
        test_foreign_preserved(directory, "target");
        fw_put32(request, number);
        input = (struct fw_input){ .bytes = request, .size = 4 }; response.size = 4;
        CHECK(fw_commit_write_request(ctx, &input, &response) == 0 && response.size == 12 &&
            fw_be32(bytes + 4) == 0 && fw_be32(bytes + 8) == sizeof(replacement) - 1);
        CHECK(fstatat(directory, "target", &after, AT_SYMLINK_NOFOLLOW) == 0);
        CHECK(S_ISREG(after.st_mode) && after.st_dev == before.st_dev && after.st_ino != before.st_ino);
        CHECK((after.st_mode & 07777) == (before.st_mode & 07777));
        CHECK(after.st_uid == before.st_uid && after.st_gid == before.st_gid && after.st_nlink == 1);
        CHECK(after.st_size == (off_t)(sizeof(replacement) - 1));
        int committed = openat(directory, "target", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        char actual[sizeof(replacement)];
        CHECK(committed >= 0 && read(committed, actual, sizeof(actual)) == (ssize_t)(sizeof(replacement) - 1) &&
            !memcmp(actual, replacement, sizeof(replacement) - 1) && close(committed) == 0);
        test_missing_entry(directory, temporary_name);
        CHECK(fw_find_handle(ctx, number) == NULL && errno == EBADF);
        CHECK(fw_cleanup(ctx) == 0 && !ctx->cleanup_failed); synthetic_root = -1; free(ctx);
        CHECK(unlinkat(directory, "target", 0) == 0 && close(directory) == 0 &&
            unlinkat(temporary, "rootguest-atomic-overwrite", AT_REMOVEDIR) == 0);
    }
    return 0;
}

static int test_root_creation_owner_and_metadata_failure(int temporary)
{
    int directory = test_directory(temporary, "rootguest-new-metadata");
    struct fw_context *ctx = test_context(directory); test_reset_faults();
    memset(&root_mock, 0, sizeof(root_mock)); root_mock.active = true; root_mock.all_chowns_mocked = true;
    CHECK(fw_identify(ctx->root, &ctx->mounts[0].identity) == 0);
    uid_t uid = (uid_t)-1; gid_t gid = (gid_t)-1;
    CHECK(fw_creation_owner(ctx, ctx->mounts[0].id, &uid, &gid) == 0 && uid == 0 && gid == 0);
    CHECK(fw_creation_owner(ctx, UINT64_MAX, &uid, &gid) == -1 && errno == EXDEV);
    int home = test_directory(directory, "app-home");
    struct stat home_stat; CHECK(fstat(home, &home_stat) == 0);
    root_mock.virtual_dev = home_stat.st_dev; root_mock.virtual_ino = home_stat.st_ino;
    root_mock.virtual_uid = root_mock.virtual_gid = 10445; root_mock.virtual_mode = 0700;
    ctx->mount_count = 2; ctx->mounts[1].fd = fw_dup(home); CHECK(ctx->mounts[1].fd >= 0);
    CHECK(fw_identify(home, &ctx->mounts[1].identity) == 0);
    ctx->mounts[1].id = ctx->mounts[0].id + 100; strcpy(ctx->mounts[1].path, "/root");
    strcpy(ctx->mounts[0].path, "/root/workspace");
    CHECK(fw_creation_owner(ctx, ctx->mounts[0].id, &uid, &gid) == 0 && uid == 10445 && gid == 10445);
    root_mock.virtual_uid = 10446;
    CHECK(fw_creation_owner(ctx, ctx->mounts[0].id, &uid, &gid) == -1 && errno == ESTALE);
    root_mock.virtual_uid = 10445; strcpy(ctx->mounts[0].path, "/");
    uint32_t number = test_begin_handler(ctx);
    struct fw_handle *h = fw_find_handle(ctx, number); CHECK(h != NULL && h->commit_uid == 0 && h->commit_gid == 0);
    /* Test the commit step for an App-bound new file using the owner derived above.
     * Ownership syscalls/statx are mocked; no arbitrary UID is applied to the host filesystem. */
    h->commit_uid = 10445; h->commit_gid = 10445;
    char temporary_name[64]; strcpy(temporary_name, h->temporary);
    unsigned char request[4], bytes[16] = {0}; fw_put32(request, number);
    struct fw_input input = { .bytes = request, .size = sizeof(request) };
    struct fw_output output = { .bytes = bytes, .size = 4 };
    CHECK(fw_commit_write_request(ctx, &input, &output) == 0);
    CHECK(root_mock.chowns == 1 && root_mock.chmods == 1 && root_mock.order == 2 &&
        root_mock.last_uid == 10445 && root_mock.last_gid == 10445 && root_mock.virtual_mode == 0600);
    int committed = openat(directory, "target", O_PATH | O_CLOEXEC | O_NOFOLLOW); CHECK(committed >= 0);
    struct fw_identity identity; CHECK(fw_identify(committed, &identity) == 0 && identity.st.stx_uid == 10445 && identity.st.stx_gid == 10445);
    CHECK(close(committed) == 0); test_missing_entry(directory, temporary_name);
    memset(&root_mock, 0, sizeof(root_mock));
    CHECK(fw_cleanup(ctx) == 0); synthetic_root = -1; free(ctx);
    CHECK(unlinkat(directory, "target", 0) == 0 && close(home) == 0 && unlinkat(directory, "app-home", AT_REMOVEDIR) == 0);
    CHECK(close(directory) == 0 && unlinkat(temporary, "rootguest-new-metadata", AT_REMOVEDIR) == 0);
    directory = test_directory(temporary, "rootguest-existing-owner"); test_file(directory, "target");
    struct stat original; CHECK(fstatat(directory, "target", &original, AT_SYMLINK_NOFOLLOW) == 0);
    ctx = test_context(directory); memset(&root_mock, 0, sizeof(root_mock));
    root_mock.active = root_mock.all_chowns_mocked = true;
    root_mock.original_dev = original.st_dev; root_mock.original_ino = original.st_ino;
    root_mock.original_uid = 123; root_mock.original_gid = 42; root_mock.original_mode = 06755;
    unsigned char existing_request[32] = {0}; struct fw_output existing_payload = { .bytes = existing_request };
    CHECK(fw_text(&existing_payload, "/target") == 0 && fw_out32(&existing_payload, 1) == 0);
    input = (struct fw_input){ .bytes = existing_request, .size = existing_payload.size }; output.size = 4;
    CHECK(fw_begin_write_request(ctx, &input, &output) == 0);
    number = fw_be32(bytes + 4); h = fw_find_handle(ctx, number); CHECK(h != NULL);
    CHECK(h->commit_uid == 123 && h->commit_gid == 42 && h->commit_mode == 06755);
    fw_put32(request, number); input = (struct fw_input){ .bytes = request, .size = sizeof(request) }; output.size = 4;
    CHECK(fw_commit_write_request(ctx, &input, &output) == 0);
    CHECK(root_mock.last_uid == 123 && root_mock.last_gid == 42 && root_mock.virtual_mode == 06755 && root_mock.order == 2);
    CHECK(fw_cleanup(ctx) == 0); synthetic_root = -1; free(ctx); memset(&root_mock, 0, sizeof(root_mock));
    CHECK(unlinkat(directory, "target", 0) == 0 && close(directory) == 0 && unlinkat(temporary, "rootguest-existing-owner", AT_REMOVEDIR) == 0);
    for (unsigned int scenario = 0; scenario < 2; ++scenario) {
        directory = test_directory(temporary, "metadata-failure"); test_file(directory, "target");
        ctx = test_context(directory);
        unsigned char payload[32], response_bytes[16]; struct fw_output payload_out = { .bytes = payload };
        CHECK(fw_text(&payload_out, "/target") == 0 && fw_out32(&payload_out, 1) == 0);
        input = (struct fw_input){ .bytes = payload, .size = payload_out.size };
        output = (struct fw_output){ .bytes = response_bytes, .size = 4 };
        CHECK(fw_begin_write_request(ctx, &input, &output) == 0);
        number = fw_be32(response_bytes + 4); h = fw_find_handle(ctx, number); CHECK(h != NULL);
        strcpy(temporary_name, h->temporary);
        root_mock.metadata_fault = true;
        if (!scenario) { h->commit_uid = h->identity.st.stx_uid == 10445 ? 10446 : 10445; root_mock.fchown_error = EPERM; }
        else root_mock.fchmod_error = EIO;
        fw_put32(request, number); input = (struct fw_input){ .bytes = request, .size = sizeof(request) }; output.size = 4;
        CHECK(fw_commit_write_request(ctx, &input, &output) == -1 && errno == (!scenario ? EPERM : EIO));
        CHECK(h->poisoned); test_foreign_preserved(directory, "target");
        memset(&root_mock, 0, sizeof(root_mock));
        CHECK(fw_close_request(ctx, &input, true) == -1 && errno == EINVAL);
        input = (struct fw_input){ .bytes = request, .size = sizeof(request) };
        CHECK(fw_close_request(ctx, &input, true) == 0); test_missing_entry(directory, temporary_name);
        CHECK(fw_cleanup(ctx) == 0); synthetic_root = -1; free(ctx);
        CHECK(unlinkat(directory, "target", 0) == 0 && close(directory) == 0 && unlinkat(temporary, "metadata-failure", AT_REMOVEDIR) == 0);
    }
    return 0;
}

static int test_review_regression_mkdir_case(int temporary, unsigned int scenario)
{
    /* Only the exact existing production handler is called. No real Root/chown/mount is used.
     * Fake mount IDs distinguish App home/workspace from an existing guest-root symlink target. */
    int directory = test_directory(temporary, "review-mkdir-race");
    int home = test_directory(directory, "root"), workspace = test_directory(home, "workspace");
    test_file(directory, "foreign-payload");
    struct stat home_before, workspace_before, foreign_before;
    CHECK(fstat(home, &home_before) == 0 && fstat(workspace, &workspace_before) == 0 && fstat(directory, &foreign_before) == 0);
    struct fw_context *ctx = test_context(directory); test_reset_faults();
    memset(&root_mock, 0, sizeof(root_mock)); root_mock.active = root_mock.all_chowns_mocked = true;
    memset(&mkdir_race, 0, sizeof(mkdir_race));
    mkdir_race.active = true;
    mkdir_race.swap_requested = mkdir_race.compete_on_publish = scenario == 0;
    mkdir_race.chown_error = scenario == 3 ? EPERM : 0;
    mkdir_race.swap_temporary = scenario == 4;
    mkdir_race.home_dev = home_before.st_dev; mkdir_race.home_ino = home_before.st_ino;
    mkdir_race.workspace_dev = workspace_before.st_dev; mkdir_race.workspace_ino = workspace_before.st_ino;
    mkdir_race.foreign_dev = foreign_before.st_dev; mkdir_race.foreign_ino = foreign_before.st_ino;
    mkdir_race.home_mount = ctx->mounts[0].id + 100; mkdir_race.workspace_mount = ctx->mounts[0].id + 101;
    ctx->mount_count = 3;
    ctx->mounts[1].fd = fw_dup(home); ctx->mounts[1].id = mkdir_race.home_mount; ctx->mounts[1].writable = true;
    strcpy(ctx->mounts[1].path, "/root");
    ctx->mounts[2].fd = fw_dup(workspace); ctx->mounts[2].id = mkdir_race.workspace_mount; ctx->mounts[2].writable = true;
    strcpy(ctx->mounts[2].path, "/root/workspace");
    CHECK(ctx->mounts[1].fd >= 0 && ctx->mounts[2].fd >= 0);
    CHECK(fw_identify(ctx->root, &ctx->mounts[0].identity) == 0 &&
        fw_identify(home, &ctx->mounts[1].identity) == 0 && fw_identify(workspace, &ctx->mounts[2].identity) == 0);
    CHECK(fw_add_boundary(ctx, mkdir_race.home_mount, "/root") == 0 && fw_add_boundary(ctx, mkdir_race.workspace_mount, "/root/workspace") == 0);
    struct fw_identity foreign_identity; CHECK(fw_identify(ctx->root, &foreign_identity) == 0);
    if (scenario == 2) {
        int existing = test_directory(workspace, "requested"); struct stat st;
        CHECK(fstat(existing, &st) == 0 && close(existing) == 0);
        mkdir_race.issued_dev[0] = st.st_dev; mkdir_race.issued_ino[0] = st.st_ino; mkdir_race.issued = 1;
        root_mock.original_dev = st.st_dev; root_mock.original_ino = st.st_ino;
        root_mock.original_uid = 123; root_mock.original_gid = 42; root_mock.original_mode = 0750;
    }
    unsigned char request[64] = {0}; struct fw_output payload = { .bytes = request };
    CHECK(fw_text(&payload, "/root/workspace/requested") == 0);
    struct fw_input input = { .bytes = request, .size = payload.size };
    int rc = fw_mkdirs_request(ctx, &input);
    if (scenario == 0) CHECK(rc == -1 && mkdir_race.swaps == 1);
    else if (scenario == 1 || scenario == 2) CHECK(rc == 0);
    else CHECK(rc == -1);
    if (scenario == 1) {
        int created = openat(workspace, "requested", O_PATH | O_NOFOLLOW | O_CLOEXEC); struct fw_identity child;
        CHECK(created >= 0 && fw_identify(created, &child) == 0 && child.st.stx_uid == 10445 && child.st.stx_gid == 10445);
        CHECK((child.st.stx_mode & 07777U) == 0700 && close(created) == 0 && mkdir_race.publish_attempts == 1);
    }
    if (scenario == 2) CHECK(root_mock.chowns == 0 && mkdir_race.mkdir_calls == 0);
    if (scenario == 3) CHECK(root_mock.chowns == 1 && !ctx->cleanup_failed && mkdir_race.publish_attempts == 0);
    if (scenario == 4) CHECK(ctx->cleanup_failed && root_mock.chowns == 0 && mkdir_race.publish_attempts == 0);
    /* The old implementation returns EBUSY only AFTER fchown follows requested -> /.
     * An error alone is not preservation: forbid metadata calls on the foreign inode itself. */
    CHECK(mkdir_race.foreign_chowns == 0);
    struct fw_identity after; CHECK(fw_identify(ctx->root, &after) == 0);
    CHECK(fw_same(&after, &foreign_identity) && after.st.stx_uid == foreign_identity.st.stx_uid &&
        after.st.stx_gid == foreign_identity.st.stx_gid && after.st.stx_mode == foreign_identity.st.stx_mode);
    struct stat held; CHECK(fstat(directory, &held) == 0 && held.st_ino == foreign_before.st_ino &&
        held.st_uid == foreign_before.st_uid && held.st_gid == foreign_before.st_gid && held.st_mode == foreign_before.st_mode);
    test_foreign_preserved(directory, "foreign-payload");
    char temporary_name[64]; strcpy(temporary_name, mkdir_race.temporary);
    memset(&root_mock, 0, sizeof(root_mock)); memset(&mkdir_race, 0, sizeof(mkdir_race));
    int cleanup = fw_cleanup(ctx);
    if (scenario == 4) CHECK(cleanup == -1 && errno == EUCLEAN); else CHECK(cleanup == 0);
    synthetic_root = -1; free(ctx);
    struct stat entry;
    if (temporary_name[0] && fstatat(workspace, temporary_name, &entry, AT_SYMLINK_NOFOLLOW) == 0)
        CHECK(scenario == 4 && S_ISLNK(entry.st_mode) && unlinkat(workspace, temporary_name, 0) == 0);
    if (fstatat(workspace, "requested", &entry, AT_SYMLINK_NOFOLLOW) == 0)
        CHECK(unlinkat(workspace, "requested", S_ISDIR(entry.st_mode) ? AT_REMOVEDIR : 0) == 0);
    if (fstatat(workspace, "saved-created", &entry, AT_SYMLINK_NOFOLLOW) == 0)
        CHECK(unlinkat(workspace, "saved-created", AT_REMOVEDIR) == 0);
    CHECK(close(workspace) == 0 && unlinkat(home, "workspace", AT_REMOVEDIR) == 0 && close(home) == 0);
    CHECK(unlinkat(directory, "root", AT_REMOVEDIR) == 0 && unlinkat(directory, "foreign-payload", 0) == 0 && close(directory) == 0);
    CHECK(unlinkat(temporary, "review-mkdir-race", AT_REMOVEDIR) == 0);
    return 0;
}

static int test_review_regression_mkdir_race(int temporary)
{
    for (unsigned int scenario = 0; scenario < 5; ++scenario)
        if (test_review_regression_mkdir_case(temporary, scenario)) return -1;
    int directory = test_directory(temporary, "review-guest-mkdir-recursive");
    struct fw_context *ctx = test_context(directory);
    memset(&mkdir_race, 0, sizeof(mkdir_race)); memset(&root_mock, 0, sizeof(root_mock));
    struct stat root; CHECK(fstat(directory, &root) == 0);
    mkdir_race.active = true; mkdir_race.workspace_dev = root.st_dev; mkdir_race.workspace_ino = root.st_ino;
    mkdir_race.workspace_mount = ctx->mounts[0].id;
    root_mock.active = root_mock.all_chowns_mocked = true;
    unsigned char request[64]; struct fw_output payload = { .bytes = request };
    CHECK(fw_text(&payload, "/requested/leaf") == 0);
    struct fw_input input = { .bytes = request, .size = payload.size };
    CHECK(fw_mkdirs_request(ctx, &input) == 0 && mkdir_race.publish_attempts == 2);
    int parent = openat(directory, "requested", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    int leaf = parent < 0 ? -1 : openat(parent, "leaf", O_PATH | O_CLOEXEC | O_NOFOLLOW);
    struct fw_identity identity;
    CHECK(parent >= 0 && leaf >= 0 && fw_identify(leaf, &identity) == 0 && identity.st.stx_uid == 0 && identity.st.stx_gid == 0);
    CHECK(root_mock.chowns == 0 && (identity.st.stx_mode & 07777U) == 0700);
    memset(&mkdir_race, 0, sizeof(mkdir_race)); memset(&root_mock, 0, sizeof(root_mock));
    CHECK(fw_cleanup(ctx) == 0); synthetic_root = -1; free(ctx);
    CHECK(close(leaf) == 0 && unlinkat(parent, "leaf", AT_REMOVEDIR) == 0 && close(parent) == 0);
    CHECK(unlinkat(directory, "requested", AT_REMOVEDIR) == 0 && close(directory) == 0 && unlinkat(temporary, "review-guest-mkdir-recursive", AT_REMOVEDIR) == 0);
    return 0;
}

static void test_config_mount(struct fw_context *ctx, int fd, const char *target, bool writable, bool allowed)
{
    CHECK(config_mount_count < sizeof(config_mounts) / sizeof(*config_mounts));
    size_t index = config_mount_count++;
    memset(&config_mounts[index], 0, sizeof(config_mounts[index]));
    char number[64]; CHECK(snprintf(number, sizeof(number), "/proc/self/fd/%d", fd) > 0);
    ssize_t size = readlink(number, config_mounts[index].path, FW_PATH);
    CHECK(size > 0 && size < FW_PATH); config_mounts[index].path[size] = 0;
    uint64_t id = ctx->mounts[0].id + 100 + index; config_mounts[index].id = id;
    CHECK(fw_add_boundary(ctx, id, target) == 0);
    if (!allowed) return;
    struct fw_mount *mount = &ctx->mounts[ctx->mount_count++];
    mount->fd = fw_dup(fd); mount->id = id; mount->writable = writable; strcpy(mount->path, target);
    CHECK(mount->fd >= 0 && fw_identify(fd, &mount->identity) == 0);
}

static void test_config_write(struct fw_context *ctx, const char *path, bool overwrite)
{
    unsigned char bytes[64], request[64];
    CHECK(test_path_response(ctx, FW_BEGIN_WRITE, path, true, overwrite ? 1U : 0U, bytes) == 8 && fw_be32(bytes) == 0);
    uint32_t handle = fw_be32(bytes + 4);
    struct fw_output payload = { .bytes = request };
    CHECK(fw_out32(&payload, handle) == 0 && fw_out32(&payload, 17) == 0 && fw_append(&payload, "foreign-preserved", 17) == 0);
    struct fw_input input = { .bytes = request, .size = payload.size };
    CHECK(fw_write_chunk_request(ctx, &input) == 0);
    fw_put32(request, handle); input = (struct fw_input){ .bytes = request, .size = 4 };
    struct fw_output output = { .bytes = bytes, .size = 4 };
    CHECK(fw_commit_write_request(ctx, &input, &output) == 0 && output.size == 12 && fw_be32(bytes + 8) == 17);
}

static int test_private_home_config(int temporary)
{
    int directory = test_directory(temporary, "private-home-config");
    int home = test_directory(directory, "root"), config = test_directory(home, ".aicode");
    int docs = test_directory(config, "docs"), output = test_directory(config, "tool-output");
    int foreign = test_directory(config, "unapproved");
    test_file(config, "settings.json"); test_file(docs, "guide.md"); test_file(foreign, "private");
    struct fw_context *ctx = test_context(directory); test_reset_faults();
    ctx->proc_fds = open("/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC); CHECK(ctx->proc_fds >= 0);
    memset(&root_mock, 0, sizeof(root_mock)); root_mock.active = root_mock.all_chowns_mocked = true;
    for (size_t i = 0; i < 3; ++i) root_mock.uid[i] = root_mock.gid[i] = 10445;
    test_config_mount(ctx, home, "/root", true, true);
    test_config_mount(ctx, docs, "/root/.aicode/docs", false, true);
    test_config_mount(ctx, output, "/root/.aicode/tool-output", true, true);
    test_config_mount(ctx, foreign, "/root/.aicode/unapproved", true, false);
    test_path_stat(ctx, "/root/.aicode/settings.json", true, config, "settings.json");
    test_path_error(ctx, FW_STAT, "/root/.aicode/missing.json", true, 1, ENOENT);
    test_path_resolved(ctx, "/root/.aicode/new/child", true, "/root/.aicode/new/child");
    unsigned char bytes[4096];
    CHECK(test_path_response(ctx, FW_LIST, "/root/.aicode", false, 0, bytes) > 8 && fw_be32(bytes) == 0 && fw_be32(bytes + 4) == 3);
    CHECK(test_path_response(ctx, FW_MKDIRS, "/root/.aicode/local", false, 0, bytes) == 4 && fw_be32(bytes) == 0);
    test_config_write(ctx, "/root/.aicode/local/new.json", false);
    test_config_write(ctx, "/root/.aicode/settings.json", true);
    test_config_write(ctx, "/root/.aicode/tool-output/result.log", false);
    test_foreign_preserved(config, "settings.json"); test_foreign_preserved(output, "result.log");
    CHECK(test_path_response(ctx, FW_OPEN_READ, "/root/.aicode/settings.json", false, 0, bytes) == 8 && fw_be32(bytes) == 0);
    unsigned char request[8]; fw_put32(request, fw_be32(bytes + 4)); fw_put32(request + 4, 32);
    struct fw_input input = { .bytes = request, .size = sizeof(request) };
    struct fw_output response = { .bytes = bytes, .size = 4 };
    CHECK(fw_read_chunk_request(ctx, &input, &response) == 0 && response.size == 25 && fw_be32(bytes + 4) == 17 &&
        !memcmp(bytes + 8, "foreign-preserved", 17));
    input = (struct fw_input){ .bytes = request, .size = 4 }; CHECK(fw_close_request(ctx, &input, false) == 0);
    test_rename_response(ctx, "/root/.aicode/local/new.json", "/root/.aicode/local/renamed.json", 0, 0);
    CHECK(test_path_response(ctx, FW_DELETE, "/root/.aicode/local", true, 1, bytes) == 4 && fw_be32(bytes) == 0);
    test_path_stat(ctx, "/root/.aicode/docs/guide.md", true, docs, "guide.md");
    test_path_error(ctx, FW_BEGIN_WRITE, "/root/.aicode/docs/guide.md", true, 1, EROFS);
    test_path_error(ctx, FW_BEGIN_WRITE, "/root/.aicode/docs/new.md", true, 0, EROFS);
    test_path_error(ctx, FW_MKDIRS, "/root/.aicode/docs/new", false, 0, EROFS);
    test_path_error(ctx, FW_DELETE, "/root/.aicode/docs/guide.md", true, 0, EROFS);
    test_rename_response(ctx, "/root/.aicode/docs/guide.md", "/root/.aicode/docs/renamed.md", 0, EROFS);
    test_foreign_preserved(docs, "guide.md"); test_missing_entry(docs, "new.md"); test_missing_entry(docs, "new");
    test_path_error(ctx, FW_STAT, "/root/.aicode/unapproved/private", true, 1, EXDEV);
    test_path_error(ctx, FW_BEGIN_WRITE, "/root/.aicode/unapproved/private", true, 1, EXDEV);
    test_foreign_preserved(foreign, "private");
    const char *const protected[] = { "/root", "/root/.aicode", "/root/.aicode/docs", "/root/.aicode/tool-output" };
    for (size_t i = 0; i < sizeof(protected) / sizeof(*protected); ++i) {
        test_path_error(ctx, FW_DELETE, protected[i], true, 1, EBUSY);
        test_rename_response(ctx, protected[i], "/root/moved", 0, EBUSY);
    }
    CHECK(symlinkat("/root/.aicode/docs/guide.md", config, "readonly-link") == 0);
    test_path_error(ctx, FW_BEGIN_WRITE, "/root/.aicode/readonly-link", true, 1, EROFS);
    test_foreign_preserved(docs, "guide.md");
    CHECK(symlinkat("/proc/self", config, "escape") == 0);
    test_path_error(ctx, FW_STAT, "/root/.aicode/escape/status", true, 1, EACCES);
    CHECK(fw_cleanup(ctx) == 0 && !ctx->cleanup_failed); synthetic_root = -1; free(ctx); config_mount_count = 0;
    memset(&root_mock, 0, sizeof(root_mock));
    CHECK(unlinkat(config, "readonly-link", 0) == 0 && unlinkat(config, "escape", 0) == 0 && unlinkat(config, "settings.json", 0) == 0);
    CHECK(unlinkat(docs, "guide.md", 0) == 0 && unlinkat(output, "result.log", 0) == 0 && unlinkat(foreign, "private", 0) == 0);
    CHECK(close(docs) == 0 && close(output) == 0 && close(foreign) == 0);
    CHECK(unlinkat(config, "docs", AT_REMOVEDIR) == 0 && unlinkat(config, "tool-output", AT_REMOVEDIR) == 0 &&
        unlinkat(config, "unapproved", AT_REMOVEDIR) == 0 && close(config) == 0);
    CHECK(unlinkat(home, ".aicode", AT_REMOVEDIR) == 0 && close(home) == 0 && unlinkat(directory, "root", AT_REMOVEDIR) == 0);
    CHECK(close(directory) == 0 && unlinkat(temporary, "private-home-config", AT_REMOVEDIR) == 0);
    return 0;
}

struct test_shared_fixture {
    struct fw_context *ctx;
    int directory, home, workspace, output;
};

static int test_shared_reload(struct fw_context *ctx)
{
    for (size_t i = 0; i < ctx->mount_count; ++i) CHECK(close(ctx->mounts[i].fd) == 0);
    ctx->mount_count = 0;
    for (size_t i = 0; i < ctx->boundary_count; ++i) free(ctx->boundaries[i].path);
    free(ctx->boundaries); ctx->boundaries = NULL; ctx->boundary_count = ctx->boundary_capacity = 0;
    return fw_load_mounts(ctx);
}

static struct test_shared_fixture test_shared_fixture(int temporary, long fs_type, unsigned int nlink)
{
    static unsigned int serial;
    char name[64]; CHECK(snprintf(name, sizeof(name), "external-upload-%u", serial++) > 0);
    struct test_shared_fixture f;
    f.directory = test_directory(temporary, name);
    f.home = test_directory(f.directory, "root"); f.workspace = test_directory(f.home, "workspace");
    int config = test_directory(f.home, ".aicode");
    f.output = test_directory(config, "tool-output"); CHECK(close(config) == 0);
    f.ctx = test_context(f.directory); test_reset_faults();
    memset(&root_mock, 0, sizeof(root_mock)); root_mock.active = root_mock.all_chowns_mocked = true;
    root_mock.fchown_error = EPERM; root_mock.fchmod_error = EIO;
    memset(&shared_mock, 0, sizeof(shared_mock)); shared_mock.active = true; shared_mock.directory_nlink = nlink;
    CHECK(config_mount_count == 0);
    test_config_mount(f.ctx, f.home, "/root", true, true);
    test_config_mount(f.ctx, f.workspace, "/root/workspace", true, true);
    test_config_mount(f.ctx, f.output, "/root/.aicode/tool-output", true, true);
    for (size_t i = 0; i < config_mount_count; ++i) config_mounts[i].fs_type = fs_type;
    config_mounts[1].synthetic_metadata = true;
    char mountinfo[1024];
    int length = snprintf(mountinfo, sizeof(mountinfo),
        "%llu 1 0:1 / / rw - ext4 none rw\n%llu 1 0:1 / /root rw - fuse none rw\n"
        "%llu 1 0:1 / /root/workspace rw - ext4 none rw\n"
        "%llu 1 0:1 / /root/.aicode/tool-output rw - fuse none rw\n",
        (unsigned long long)f.ctx->mounts[0].id, (unsigned long long)config_mounts[0].id,
        (unsigned long long)config_mounts[1].id, (unsigned long long)config_mounts[2].id);
    CHECK(length > 0 && (size_t)length < sizeof(mountinfo));
    int fd = openat(f.directory, "mountinfo", O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    CHECK(fd >= 0 && write(fd, mountinfo, (size_t)length) == length && close(fd) == 0);
    f.ctx->proc_self = fw_dup(f.directory);
    f.ctx->proc_fds = open("/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    CHECK(f.ctx->proc_self >= 0 && f.ctx->proc_fds >= 0 && test_shared_reload(f.ctx) == 0);
    return f;
}

static void test_shared_finish(struct test_shared_fixture *f)
{
    shared_mock.changed_mask = 0; shared_mock.mount = 0;
    bool failed = f->ctx->cleanup_failed || shared_mock.replaced_temporary;
    int rc = fw_cleanup(f->ctx);
    CHECK(failed ? rc == -1 && errno == EUCLEAN : rc == 0);
    synthetic_root = -1; free(f->ctx); config_mount_count = 0;
    memset(&root_mock, 0, sizeof(root_mock)); memset(&shared_mock, 0, sizeof(shared_mock)); test_reset_faults();
    CHECK(close(f->output) == 0 && close(f->workspace) == 0 && close(f->home) == 0 && close(f->directory) == 0);
}

static uint32_t test_shared_begin(struct fw_context *ctx, const char *path, bool overwrite)
{
    unsigned char bytes[64];
    CHECK(test_path_response(ctx, FW_BEGIN_WRITE, path, true, overwrite ? 1U : 0U, bytes) == 8 && fw_be32(bytes) == 0);
    return fw_be32(bytes + 4);
}

static int test_shared_commit(struct fw_context *ctx, uint32_t number)
{
    unsigned char request[4], bytes[16]; fw_put32(request, number);
    struct fw_input input = { .bytes = request, .size = sizeof(request) };
    struct fw_output output = { .bytes = bytes, .size = 4 };
    return fw_commit_write_request(ctx, &input, &output);
}

static void test_shared_read(struct fw_context *ctx, const char *path)
{
    unsigned char bytes[64], request[8];
    CHECK(test_path_response(ctx, FW_OPEN_READ, path, false, 0, bytes) == 8 && fw_be32(bytes) == 0);
    fw_put32(request, fw_be32(bytes + 4)); fw_put32(request + 4, 32);
    struct fw_input input = { .bytes = request, .size = sizeof(request) };
    struct fw_output output = { .bytes = bytes, .size = 4 };
    CHECK(fw_read_chunk_request(ctx, &input, &output) == 0 && output.size == 25 && fw_be32(bytes + 4) == 17 &&
        !memcmp(bytes + 8, "foreign-preserved", 17));
    input = (struct fw_input){ .bytes = request, .size = 4 }; CHECK(fw_close_request(ctx, &input, false) == 0);
}

static void test_shared_change(int fd, uint32_t mask)
{
    struct stat st; CHECK(fstat(fd, &st) == 0);
    shared_mock.changed_dev = st.st_dev; shared_mock.changed_ino = st.st_ino; shared_mock.changed_mask = mask;
}

static int test_external_upload_success(int temporary, bool directories)
{
    static const long types[] = { FUSE_SUPER_MAGIC, 0x5dca2df5L };
    for (size_t type = 0; type < sizeof(types) / sizeof(*types); ++type) for (unsigned int nlink = 1; nlink <= 2; ++nlink) {
        struct test_shared_fixture f = test_shared_fixture(temporary, types[type], nlink);
        const char *path = directories ? "/root/workspace/.aicode/attachments/item.bin" : "/root/workspace/item.bin";
        if (directories) {
            unsigned char bytes[16];
            CHECK(test_path_response(f.ctx, FW_MKDIRS, "/root/workspace/.aicode/attachments", false, 0, bytes) == 4 &&
                fw_be32(bytes) == 0);
            CHECK(shared_mock.directory_reads > 0);
            struct fw_resolved r;
            CHECK(fw_resolve(f.ctx, "/root/workspace/.aicode/attachments", false, 0, &r) == 0);
            CHECK(r.identity.st.stx_uid == 1023 && r.identity.st.stx_gid == 1023 &&
                (r.identity.st.stx_mode & 07777U) == 02770 && r.identity.st.stx_nlink == nlink);
            fw_resolved_close(&r);
        }
        test_config_write(f.ctx, path, false); test_shared_read(f.ctx, path);
        struct fw_resolved before, after;
        CHECK(fw_resolve(f.ctx, path, false, 0, &before) == 0);
        test_path_error(f.ctx, FW_BEGIN_WRITE, path, true, 0, EEXIST);
        test_config_write(f.ctx, path, true); test_shared_read(f.ctx, path);
        CHECK(fw_resolve(f.ctx, path, false, 0, &after) == 0);
        CHECK(!fw_same(&before.identity, &after.identity) && before.identity.st.stx_mode == after.identity.st.stx_mode &&
            after.identity.st.stx_uid == 1023 && after.identity.st.stx_gid == 1023 && after.identity.st.stx_nlink == 1);
        fw_resolved_close(&before); fw_resolved_close(&after);
        CHECK(root_mock.chowns == 0 && root_mock.chmods == 0);
        CHECK(config_mounts[0].fs_queries == 0 && config_mounts[1].fs_queries == 1 && config_mounts[2].fs_queries == 0);
        test_shared_finish(&f);
    }
    return 0;
}

static int test_external_upload_rejections(int temporary)
{
    for (unsigned int scenario = 0; scenario < 6; ++scenario) {
        struct test_shared_fixture f = test_shared_fixture(temporary, FUSE_SUPER_MAGIC, 1);
        shared_mock.nonempty_directory = scenario == 0; shared_mock.readdir_error = scenario == 1 ? EIO : 0;
        shared_mock.swap_directory = scenario == 2; shared_mock.compete_directory = scenario == 3;
        shared_mock.mutate_on_empty = scenario == 4;
        if (scenario == 5) shared_mock.directory_nlink = 3;
        unsigned char bytes[16];
        CHECK(test_path_response(f.ctx, FW_MKDIRS, "/root/workspace/new", false, 0, bytes) == 4 && fw_be32(bytes) != 0);
        CHECK(shared_mock.publishes == (scenario == 3 ? 1U : 0U));
        if (scenario == 1) CHECK(fw_be32(bytes) == EIO);
        CHECK(root_mock.chowns == 0 && root_mock.chmods == 0);
        if (scenario == 3) {
            struct stat st;
            CHECK(fstatat(f.workspace, "new", &st, AT_SYMLINK_NOFOLLOW) == 0 && S_ISLNK(st.st_mode));
        } else test_missing_entry(f.workspace, "new");
        if (scenario == 0) {
            int directory = openat(f.workspace, shared_mock.temporary, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
            CHECK(directory >= 0);
            int child = openat(directory, "foreign-child", O_RDONLY | O_NOFOLLOW | O_CLOEXEC); char content[9];
            CHECK(child >= 0 && read(child, content, sizeof(content)) == 9 && !memcmp(content, "preserved", 9));
            CHECK(close(child) == 0 && close(directory) == 0);
        }
        test_shared_finish(&f);
    }
    static const uint32_t masks[] = { STATX_UID, STATX_GID, STATX_MODE, STATX_NLINK, STATX_SIZE, 0 };
    for (size_t field = 0; field < sizeof(masks) / sizeof(*masks); ++field) {
        struct test_shared_fixture f = test_shared_fixture(temporary, FUSE_SUPER_MAGIC, 2);
        uint32_t number = test_shared_begin(f.ctx, "/root/workspace/new.bin", false);
        struct fw_handle *h = fw_find_handle(f.ctx, number); CHECK(h != NULL);
        test_shared_change(h->fd, masks[field]); shared_mock.uid = shared_mock.gid = 1024;
        shared_mock.mode = S_IFREG | 0640; shared_mock.nlink = 2; shared_mock.size = 1;
        if (!masks[field]) shared_mock.mount = config_mounts[0].id;
        CHECK(test_shared_commit(f.ctx, number) == -1 && errno == ESTALE && shared_mock.publishes == 0);
        shared_mock.changed_mask = 0; shared_mock.mount = 0;
        test_missing_entry(f.workspace, "new.bin");
        CHECK(root_mock.chowns == 0 && root_mock.chmods == 0); test_shared_finish(&f);
    }
    for (unsigned int scenario = 0; scenario < 7; ++scenario) {
        struct test_shared_fixture f = test_shared_fixture(temporary, FUSE_SUPER_MAGIC, 2);
        test_file(f.workspace, "target");
        uint32_t number = test_shared_begin(f.ctx, "/root/workspace/target", true);
        struct fw_handle *h = fw_find_handle(f.ctx, number); CHECK(h != NULL);
        if (scenario < 3) {
            test_shared_change(h->original, scenario == 0 ? STATX_UID : scenario == 1 ? STATX_GID : STATX_MODE);
            shared_mock.uid = shared_mock.gid = 1024; shared_mock.mode = S_IFREG | 0640;
        } else if (scenario == 3) {
            CHECK(renameat(f.workspace, "target", f.workspace, "saved-target") == 0 &&
                symlinkat("/", f.workspace, "target") == 0);
        } else if (scenario == 4 || scenario == 5) {
            shared_mock.replaced_temporary = true;
            CHECK(renameat(f.workspace, h->temporary, f.workspace, "saved-temporary") == 0);
            if (scenario == 4) CHECK(symlinkat("/", f.workspace, h->temporary) == 0);
            else test_file(f.workspace, h->temporary);
        } else {
            test_shared_change(h->parent, STATX_MODE); shared_mock.mode = S_IFDIR | 02750;
        }
        CHECK(test_shared_commit(f.ctx, number) == -1 && errno == ESTALE && shared_mock.publishes == 0);
        test_foreign_preserved(f.workspace, scenario == 3 ? "saved-target" : "target");
        if (scenario == 4 || scenario == 5) {
            char name[64]; strcpy(name, h->temporary);
            unsigned char request[4]; fw_put32(request, number);
            struct fw_input input = { .bytes = request, .size = sizeof(request) };
            CHECK(fw_close_request(f.ctx, &input, true) == -1 && errno == ESTALE && f.ctx->cleanup_failed);
            if (scenario == 4) {
                struct stat st;
                CHECK(fstatat(f.workspace, name, &st, AT_SYMLINK_NOFOLLOW) == 0 && S_ISLNK(st.st_mode));
            } else test_foreign_preserved(f.workspace, name);
        }
        CHECK(root_mock.chowns == 0 && root_mock.chmods == 0); test_shared_finish(&f);
    }
    for (unsigned int scenario = 0; scenario < 3; ++scenario) {
        struct test_shared_fixture f = test_shared_fixture(temporary, FUSE_SUPER_MAGIC, 2);
        test_file(f.workspace, "target");
        int original = openat(f.workspace, "target", O_PATH | O_NOFOLLOW | O_CLOEXEC); CHECK(original >= 0);
        test_shared_change(original, scenario == 0 ? STATX_UID : scenario == 1 ? STATX_GID : STATX_MODE);
        shared_mock.uid = shared_mock.gid = 1024; shared_mock.mode = S_IFREG | 0640;
        unsigned char bytes[64];
        CHECK(test_path_response(f.ctx, FW_BEGIN_WRITE, "/root/workspace/target", true, 1, bytes) == 4 && fw_be32(bytes) != 0);
        CHECK(close(original) == 0 && shared_mock.publishes == 0 && root_mock.chowns == 0 && root_mock.chmods == 0);
        test_foreign_preserved(f.workspace, "target"); test_shared_finish(&f);
    }
    struct test_shared_fixture f = test_shared_fixture(temporary, FUSE_SUPER_MAGIC, 2);
    uint32_t number = test_shared_begin(f.ctx, "/root/workspace/new.bin", false);
    test_file(f.workspace, "new.bin");
    CHECK(test_shared_commit(f.ctx, number) == -1 && errno == EEXIST && shared_mock.publishes == 0);
    test_foreign_preserved(f.workspace, "new.bin"); test_shared_finish(&f);
    return 0;
}

static int test_external_upload_write_epoch(int temporary)
{
    struct test_shared_fixture f = test_shared_fixture(temporary, FUSE_SUPER_MAGIC, 2);
    test_file(f.workspace, "target");
    uint32_t number = test_shared_begin(f.ctx, "/root/workspace/target", true);
    struct fw_handle *h = fw_find_handle(f.ctx, number); CHECK(h != NULL);
    unsigned char request[64]; struct fw_output payload = { .bytes = request };
    CHECK(fw_out32(&payload, number) == 0 && fw_out32(&payload, 8) == 0 && fw_append(&payload, "intended", 8) == 0);
    struct fw_input input = { .bytes = request, .size = payload.size };
    CHECK(fw_write_chunk_request(f.ctx, &input) == 0);
    struct stat before; CHECK(fstat(h->fd, &before) == 0);
    int writer = openat(h->parent, h->temporary, O_WRONLY | O_CLOEXEC | O_NOFOLLOW); CHECK(writer >= 0);
    CHECK(pwrite(writer, "tampered", 8, 0) == 8);
    struct timespec times[2] = { before.st_atim, before.st_mtim }; ++times[1].tv_sec;
    CHECK(futimens(writer, times) == 0 && close(writer) == 0);
    CHECK(test_shared_commit(f.ctx, number) == -1 && errno == ESTALE && shared_mock.publishes == 0);
    test_foreign_preserved(f.workspace, "target"); test_shared_finish(&f); return 0;
}

static int test_external_upload_directory_epoch(int temporary)
{
    struct test_shared_fixture f = test_shared_fixture(temporary, FUSE_SUPER_MAGIC, 1);
    shared_mock.insert_on_empty = true;
    unsigned char bytes[16];
    CHECK(test_path_response(f.ctx, FW_MKDIRS, "/root/workspace/new", false, 0, bytes) == 4 && fw_be32(bytes) == ESTALE);
    CHECK(shared_mock.empty_scans == 2 && shared_mock.publishes == 0);
    test_missing_entry(f.workspace, "new");
    int dir = openat(f.workspace, shared_mock.temporary, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    CHECK(dir >= 0);
    int child = openat(dir, "late-child", O_RDONLY | O_CLOEXEC | O_NOFOLLOW); char content[9];
    CHECK(child >= 0 && read(child, content, sizeof(content)) == 9 && !memcmp(content, "preserved", 9));
    CHECK(close(child) == 0 && close(dir) == 0); test_shared_finish(&f); return 0;
}

static int test_external_upload_creation(int temporary)
{
    for (unsigned int directory = 0; directory <= 1; ++directory) for (unsigned int scenario = 0; scenario < 7; ++scenario) {
        struct test_shared_fixture f = test_shared_fixture(temporary, FUSE_SUPER_MAGIC, 2);
        shared_mock.uid = shared_mock.gid = 1024; shared_mock.nlink = directory ? 3 : 2;
        shared_mock.size = 1;
        if (scenario == 0) shared_mock.creation_mask = STATX_UID;
        if (scenario == 1) shared_mock.creation_mask = STATX_GID;
        if (scenario == 2 || scenario == 3) {
            shared_mock.creation_mask = STATX_MODE;
            shared_mock.mode = directory ? (scenario == 2 ? S_IFDIR | 02771 : S_IFDIR | 06770) :
                (scenario == 2 ? S_IFREG | 0661 : S_IFREG | 02660);
        }
        if (scenario == 4) shared_mock.creation_mask = STATX_NLINK;
        if (scenario == 5) {
            shared_mock.creation_mask = directory ? STATX_MODE : STATX_SIZE;
            shared_mock.mode = S_IFDIR | 03770;
        }
        if (scenario == 6) {
            shared_mock.creation_mask = STATX_TYPE; shared_mock.mount = config_mounts[0].id;
        }
        test_path_error(f.ctx, directory ? FW_MKDIRS : FW_BEGIN_WRITE, "/root/workspace/new", directory == 0, 0, ESTALE);
        CHECK(shared_mock.publishes == 0 && root_mock.chowns == 0 && root_mock.chmods == 0);
        test_missing_entry(f.workspace, "new"); test_shared_finish(&f);
    }
    return 0;
}

static int test_external_upload_scope(int temporary)
{
    static const long ordinary[] = { EXT4_SUPER_MAGIC, TMPFS_MAGIC };
    for (size_t i = 0; i < sizeof(ordinary) / sizeof(*ordinary); ++i) {
        struct test_shared_fixture f = test_shared_fixture(temporary, ordinary[i], 2);
        test_path_error(f.ctx, FW_MKDIRS, "/root/workspace/new", false, 0, ESTALE);
        test_path_error(f.ctx, FW_BEGIN_WRITE, "/root/workspace/new.bin", true, 0, ESTALE);
        CHECK(root_mock.chowns == 0 && root_mock.chmods == 0); test_shared_finish(&f);
    }
    struct test_shared_fixture f = test_shared_fixture(temporary, FUSE_SUPER_MAGIC, 2);
    config_mounts[2].synthetic_metadata = true;
    test_path_error(f.ctx, FW_MKDIRS, "/root/.aicode/tool-output/new", false, 0, ESTALE);
    test_path_error(f.ctx, FW_BEGIN_WRITE, "/root/.aicode/tool-output/new.bin", true, 0, ESTALE);
    int nested = test_directory(f.workspace, "other-mount");
    test_config_mount(f.ctx, nested, "/root/workspace/other-mount", true, true);
    config_mounts[3].synthetic_metadata = true; config_mounts[3].fs_type = FUSE_SUPER_MAGIC;
    test_path_error(f.ctx, FW_MKDIRS, "/root/workspace/other-mount/new", false, 0, ESTALE);
    test_path_error(f.ctx, FW_BEGIN_WRITE, "/root/workspace/other-mount/new.bin", true, 0, ESTALE);
    CHECK(close(nested) == 0 && config_mounts[0].fs_queries == 0 && config_mounts[1].fs_queries == 1 &&
        config_mounts[2].fs_queries == 0 && config_mounts[3].fs_queries == 0);
    test_shared_finish(&f);
    for (unsigned int scenario = 0; scenario < 2; ++scenario) {
        f = test_shared_fixture(temporary, FUSE_SUPER_MAGIC, 2);
        size_t queries = config_mounts[1].fs_queries;
        if (!scenario) config_mounts[1].fs_error = EIO;
        else config_mounts[1].id += 200;
        CHECK(test_shared_reload(f.ctx) == -1 && errno == (!scenario ? EIO : EACCES));
        CHECK(fw_find_mount(f.ctx, config_mounts[1].id) == NULL &&
            config_mounts[1].fs_queries == queries + (!scenario ? 1U : 0U));
        test_shared_finish(&f);
    }
    return 0;
}

static unsigned int test_real_in_root(int directory)
{
    struct fw_identity expected, actual;
    CHECK(fw_identify(directory, &expected) == 0);
    struct open_how how = { .flags = FW_PF,
        .resolve = RESOLVE_IN_ROOT | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS };
    int fd = (int)syscall(SYS_openat2, directory, "/", &how, sizeof(how));
    if (fd < 0) {
        printf("SKIP real RESOLVE_IN_ROOT probe: syscall unavailable (errno=%d); mocked handler checks are not kernel/E2E evidence.\n", errno);
        return 1;
    }
    CHECK(fw_identify(fd, &actual) == 0 && close(fd) == 0);
    if (!fw_same(&expected, &actual)) {
        puts("SKIP real RESOLVE_IN_ROOT probe: current environment returned a different root identity; mocked handler checks are not kernel/E2E evidence.");
        return 1;
    }
    CHECK(fw_same(&expected, &actual));
    puts("Real RESOLVE_IN_ROOT identity probe passed; no production worker/service executed.");
    return 0;
}

int main(int argc, char **argv)
{
    if (argc != 2 && argc != 3) {
        fputs("Expected fresh TemporaryDirectory path and optional focused test name.\n", stderr); return 2;
    }
    int dir = open(argv[1], O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (dir < 0) { fputs("TemporaryDirectory unavailable.\n", stderr); return 2; }
    test_reset_faults();
    if (argc == 3) {
        int focused;
        if (!strcmp(argv[2], "externalUploadDirectories")) focused = test_external_upload_success(dir, true);
        else if (!strcmp(argv[2], "externalUploadFiles")) focused = test_external_upload_success(dir, false);
        else if (!strcmp(argv[2], "externalUploadRejections")) focused = test_external_upload_rejections(dir);
        else if (!strcmp(argv[2], "externalUploadCreation")) focused = test_external_upload_creation(dir);
        else if (!strcmp(argv[2], "externalUploadWriteEpoch")) focused = test_external_upload_write_epoch(dir);
        else if (!strcmp(argv[2], "externalUploadDirectoryEpoch")) focused = test_external_upload_directory_epoch(dir);
        else if (!strcmp(argv[2], "externalUploadScope")) focused = test_external_upload_scope(dir);
        else if (!strcmp(argv[2], "privateHomeConfig")) focused = test_private_home_config(dir);
        else if (!strcmp(argv[2], "nonrootCredentialsControl")) focused = test_nonroot_credentials_control();
        else if (!strcmp(argv[2], "reviewRegressionMkdirRace")) focused = test_review_regression_mkdir_race(dir);
        else if (!strcmp(argv[2], "rootGuestGuard")) focused = test_root_guard_and_app_control();
        else if (!strcmp(argv[2], "rootGuestMetadataFailures")) focused = test_root_creation_owner_and_metadata_failure(dir);
        else if (!strcmp(argv[2], "defaultAllowRootGuestCredentials"))
            focused = test_nonroot_credentials_control() || test_default_allow_root_guest_credentials();
        else if (!strcmp(argv[2], "defaultAllowRootGuestRootfsRw"))
            focused = test_default_allow_root_guest_rootfs_rw_keeps_host_ro();
        else if (!strcmp(argv[2], "defaultAllowRootGuestAtomicOverwriteMetadata"))
            focused = test_default_allow_root_guest_atomic_overwrite_metadata(dir);
        else { fputs("Unknown focused test name.\n", stderr); close(dir); return 2; }
        close(dir);
        if (!focused) printf("AJFW focused checks passed: %s, %lu assertions; pure/static API or synthetic-context owned-temp handlers only, no production main/guard/root/mount.\n", argv[2], checks);
        return focused ? 1 : 0;
    }
    int rc = test_headers() || test_text_paths() || test_input_paths() || test_payloads() || test_mount_parser_policy() ||
        test_nonroot_credentials_control() || test_metadata_handles() || test_root_guard_and_app_control() || test_default_allow_root_guest_credentials() ||
        test_default_allow_root_guest_rootfs_rw_keeps_host_ro();
    if (rc) { close(dir); return 1; }
    unsigned long pure_checks = checks;
    if (test_temporary_files(dir)) { close(dir); return 1; }
    unsigned long ordinary_checks = checks - pure_checks;
    unsigned int real_probe_skips = test_real_in_root(dir);
    unsigned long real_probe_checks = checks - pure_checks - ordinary_checks;
    if (test_list_handler(dir) || test_cleanup_handlers(dir) || test_path_handlers(dir) || test_nofollow_mutations(dir) ||
        test_default_allow_root_guest_atomic_overwrite_metadata(dir) || test_root_creation_owner_and_metadata_failure(dir) ||
        test_review_regression_mkdir_race(dir) || test_private_home_config(dir) || test_external_upload_success(dir, true) ||
        test_external_upload_success(dir, false) || test_external_upload_rejections(dir) || test_external_upload_creation(dir) ||
        test_external_upload_scope(dir) || test_external_upload_write_epoch(dir) || test_external_upload_directory_epoch(dir)) {
        close(dir); return 1;
    }
    unsigned long mocked_checks = checks - pure_checks - ordinary_checks - real_probe_checks;
    close(dir);
    printf("AJFW offline checks passed: total=%lu pure=%lu ordinary_temp=%lu real_probe_checks=%lu real_probe_skips=%u mocked_handler=%lu; file operations and mount discovery use synthetic-context mocks and owned TemporaryDirectory files/dirs/symlinks only; production main/guard/service and real mounts not run.\n",
        checks, pure_checks, ordinary_checks, real_probe_checks, real_probe_skips, mocked_checks);
    return 0;
}
