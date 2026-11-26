#ifndef AJIU_RUNTIME_LAUNCH_H
#define AJIU_RUNTIME_LAUNCH_H

#include "runtime_protocol.h"

#include <stddef.h>
#include <stdint.h>

#define AJIU_LAUNCH_TOKEN_BYTES 32U
#define AJIU_LAUNCH_MAX_GROUPS 32U
#define AJIU_LAUNCH_MAX_ARGS 256U
#define AJIU_LAUNCH_MAX_ENV 256U
#define AJIU_LAUNCH_MAX_MOUNTS 32U
#define AJIU_LAUNCH_MAX_PATH 4096U
#define AJIU_LAUNCH_MAX_STRING 65536U

enum ajiu_io_mode { AJIU_IO_STDIO = 0, AJIU_IO_PTY = 1 };
enum ajiu_mount_kind { AJIU_MOUNT_DIRECTORY = 1, AJIU_MOUNT_FILE = 2 };

struct ajiu_launch_mount {
    uint32_t kind, read_only;
    uint64_t source_dev, source_ino;
    char *source_path, *target_path;
};

struct ajiu_launch {
    unsigned char token[AJIU_LAUNCH_TOKEN_BYTES];
    uint32_t uid, gid, group_count, groups[AJIU_LAUNCH_MAX_GROUPS];
    uint32_t io_mode, lease_ms, command_timeout_ms;
    uint64_t rootfs_dev, rootfs_ino;
    char *instance_id, *launch_id, *owner_identity, *cwd;
    uint32_t argc, envc, mountc;
    char *argv[AJIU_LAUNCH_MAX_ARGS + 1];
    char *env[AJIU_LAUNCH_MAX_ENV + 1];
    struct ajiu_launch_mount mounts[AJIU_LAUNCH_MAX_MOUNTS];
};

struct ajiu_launch_session {
    enum ajiu_protocol_phase phase;
    unsigned int launch_seen, go_seen, stdin_closed;
};

int ajiu_launch_group_allowed(uint32_t uid, uint32_t group);
int ajiu_launch_target_allowed(const struct ajiu_launch_mount *mount);
int ajiu_launch_source_allowed(const struct ajiu_launch *launch,
                              const struct ajiu_launch_mount *mount);
int ajiu_launch_parse(const unsigned char *bytes, size_t length, struct ajiu_launch *launch);
void ajiu_launch_free(struct ajiu_launch *launch);
void ajiu_launch_session_init(struct ajiu_launch_session *session);
int ajiu_launch_session_accept(struct ajiu_launch_session *session,
                               const struct ajiu_frame_header *header,
                               const unsigned char *body, struct ajiu_launch *launch);

#endif
