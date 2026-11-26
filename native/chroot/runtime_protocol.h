#ifndef AJIU_RUNTIME_PROTOCOL_H
#define AJIU_RUNTIME_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>

#define AJIU_PROTOCOL_VERSION 1U
#define AJIU_HEADER_BYTES 12U
#define AJIU_MAX_FRAME_BYTES (1024U * 1024U)
#define AJIU_MAX_DATA_BYTES (64U * 1024U)

enum ajiu_frame_type {
    AJIU_LAUNCH = 1,
    AJIU_GO = 2,
    AJIU_STDIN = 3,
    AJIU_STDIN_EOF = 4,
    AJIU_RESIZE = 5,
    AJIU_SIGNAL = 6,
    AJIU_HEARTBEAT = 7,
    AJIU_STOP = 8,
    AJIU_READY = 128,
    AJIU_STDOUT = 129,
    AJIU_STDERR = 130,
    AJIU_EXIT = 131,
    AJIU_CLEANUP = 132,
    AJIU_ERROR = 133
};

enum ajiu_protocol_phase {
    AJIU_PHASE_INITIAL,
    AJIU_PHASE_PREPARED,
    AJIU_PHASE_RUNNING,
    AJIU_PHASE_STOPPING,
    AJIU_PHASE_CLOSED
};

struct ajiu_frame_header {
    uint16_t type;
    uint32_t length;
};

int ajiu_decode_header(const unsigned char *bytes, size_t length, struct ajiu_frame_header *header);
int ajiu_encode_header(unsigned char bytes[AJIU_HEADER_BYTES], uint16_t type, uint32_t length);
int ajiu_validate_input(enum ajiu_protocol_phase phase, const struct ajiu_frame_header *header,
                        const unsigned char *body);
int ajiu_read_frame(int fd, struct ajiu_frame_header *header, unsigned char *body,
                    size_t capacity, unsigned int timeout_ms);
/* Pipe transports must ignore SIGPIPE and discard the transport after any frame error. */
int ajiu_write_frame(int fd, uint16_t type, const unsigned char *body,
                     uint32_t length, unsigned int timeout_ms);

#endif
