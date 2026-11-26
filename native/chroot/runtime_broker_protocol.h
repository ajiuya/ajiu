#ifndef AJIU_RUNTIME_BROKER_PROTOCOL_H
#define AJIU_RUNTIME_BROKER_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>

#define AJIU_BROKER_MAGIC UINT32_C(0x414a4252)
#define AJIU_BROKER_EXIT_MAGIC UINT32_C(0x414a4558)
#define AJIU_BROKER_VERSION 1U
#define AJIU_BROKER_HEADER_SIZE 12U
#define AJIU_BROKER_MAX_PAYLOAD 256U
#define AJIU_BROKER_HELLO_SIZE 56U
#define AJIU_BROKER_START_SIZE 56U
#define AJIU_BROKER_STARTED_SIZE 16U
#define AJIU_BROKER_FAILED_SIZE 16U
#define AJIU_BROKER_STOP_SIZE 16U
#define AJIU_BROKER_SHUTDOWN_SIZE 8U
#define AJIU_BROKER_EXIT_SIZE 16U
#define AJIU_BROKER_FD_COUNT 4U
#define AJIU_BROKER_MAX_JOBS 32U
#define AJIU_BROKER_MAX_SOCKET_NAME 100U
#define AJIU_BROKER_NONCE_SIZE 32U
#define AJIU_BROKER_INSTANCE_SIZE 24U
#define AJIU_BROKER_IO_TIMEOUT_MS 5000
#define AJIU_BROKER_SHUTDOWN_TIMEOUT_MS 5000
#define AJIU_BROKER_LIFETIME_MS INT64_C(86400000)
#define AJIU_BROKER_MAX_REQUEST_ID INT64_MAX

#define AJIU_BROKER_RUNTIME_NAME "libajiu_chroot_runtime.so"
#define AJIU_BROKER_INSTALLER_NAME "libajiu_chroot_installer.so"
#define AJIU_BROKER_NAME "libajiu_chroot_broker.so"

enum ajiu_broker_type {
    AJIU_BROKER_HELLO = 1,
    AJIU_BROKER_HELLO_ACK = 2,
    AJIU_BROKER_START = 3,
    AJIU_BROKER_STOP = 4,
    AJIU_BROKER_SHUTDOWN = 5,
    AJIU_BROKER_STARTED = 6,
    AJIU_BROKER_FAILED = 7,
    AJIU_BROKER_STOPPED = 8,
    AJIU_BROKER_BYE = 9
};

enum ajiu_broker_operation {
    AJIU_BROKER_RUNTIME = 1,
    AJIU_BROKER_INSTALL = 2,
    AJIU_BROKER_REMOVE = 3
};

enum ajiu_broker_failure {
    AJIU_BROKER_FAIL_CAPACITY = 1,
    AJIU_BROKER_FAIL_PIPE = 2,
    AJIU_BROKER_FAIL_FORK = 3,
    AJIU_BROKER_FAIL_IDENTITY = 4,
    AJIU_BROKER_FAIL_TARGET = 5
};

enum ajiu_broker_outcome {
    AJIU_BROKER_EXITED = 1,
    AJIU_BROKER_SIGNAL = 2,
    AJIU_BROKER_UNKNOWN = 3
};

enum ajiu_broker_fd_index {
    AJIU_BROKER_STDIN = 0,
    AJIU_BROKER_STDOUT = 1,
    AJIU_BROKER_STDERR = 2,
    AJIU_BROKER_STATUS = 3
};

struct ajiu_broker_header {
    uint16_t type;
    uint32_t length;
};

struct ajiu_broker_request {
    uint64_t id;
    uint32_t operation;
    char instance[AJIU_BROKER_INSTANCE_SIZE + 1U];
    uint64_t device;
    uint64_t inode;
    uint64_t target;
};

#endif
