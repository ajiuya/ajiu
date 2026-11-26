#include "runtime_protocol.h"

#include <errno.h>
#include <string.h>

static const unsigned char magic[4] = { 'A', 'J', 'C', 'R' };

static uint16_t read16(const unsigned char *p)
{
    return ((uint16_t)p[0] << 8) | p[1];
}

static uint32_t read32(const unsigned char *p)
{
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}

static int valid_header(uint16_t type, uint32_t length)
{
    if (length > AJIU_MAX_FRAME_BYTES)
        return 0;
    switch (type) {
    case AJIU_LAUNCH: return length > 0;
    case AJIU_GO:
    case AJIU_STDIN_EOF:
    case AJIU_HEARTBEAT:
    case AJIU_STOP: return length == 0;
    case AJIU_STDIN:
    case AJIU_STDOUT:
    case AJIU_STDERR: return length <= AJIU_MAX_DATA_BYTES;
    case AJIU_RESIZE: return length == 4;
    case AJIU_SIGNAL: return length == 1;
    case AJIU_READY: return length == 32;
    case AJIU_EXIT: return length == 8;
    case AJIU_CLEANUP: return length == 1;
    case AJIU_ERROR: return length > 0 && length <= 1024;
    default: return 0;
    }
}

int ajiu_decode_header(const unsigned char *bytes, size_t length, struct ajiu_frame_header *header)
{
    if (bytes == NULL || header == NULL || length != AJIU_HEADER_BYTES ||
        memcmp(bytes, magic, sizeof(magic)) != 0 || read16(bytes + 4) != AJIU_PROTOCOL_VERSION) {
        errno = EPROTO;
        return -1;
    }
    uint16_t type = read16(bytes + 6);
    uint32_t size = read32(bytes + 8);
    if (!valid_header(type, size)) {
        errno = EPROTO;
        return -1;
    }
    header->type = type;
    header->length = size;
    return 0;
}

int ajiu_encode_header(unsigned char bytes[AJIU_HEADER_BYTES], uint16_t type, uint32_t length)
{
    if (bytes == NULL || !valid_header(type, length)) {
        errno = EPROTO;
        return -1;
    }
    memcpy(bytes, magic, sizeof(magic));
    bytes[4] = 0;
    bytes[5] = AJIU_PROTOCOL_VERSION;
    bytes[6] = (unsigned char)(type >> 8);
    bytes[7] = (unsigned char)type;
    bytes[8] = (unsigned char)(length >> 24);
    bytes[9] = (unsigned char)(length >> 16);
    bytes[10] = (unsigned char)(length >> 8);
    bytes[11] = (unsigned char)length;
    return 0;
}

int ajiu_validate_input(enum ajiu_protocol_phase phase, const struct ajiu_frame_header *header,
                        const unsigned char *body)
{
    if (header == NULL || !valid_header(header->type, header->length) ||
        (header->length != 0 && body == NULL))
        goto invalid;
    if (phase == AJIU_PHASE_INITIAL && header->type == AJIU_LAUNCH)
        return 0;
    if (phase == AJIU_PHASE_PREPARED && header->type == AJIU_GO)
        return 0;
    if ((phase == AJIU_PHASE_PREPARED || phase == AJIU_PHASE_RUNNING) &&
        (header->type == AJIU_HEARTBEAT || header->type == AJIU_STOP))
        return 0;
    if (phase == AJIU_PHASE_RUNNING) {
        switch (header->type) {
        case AJIU_STDIN:
        case AJIU_STDIN_EOF:
            return 0;
        case AJIU_RESIZE:
            if (read16(body) > 0 && read16(body) <= 4096 && read16(body + 2) > 0 && read16(body + 2) <= 4096)
                return 0;
            break;
        case AJIU_SIGNAL:
            if (body[0] == 2 || body[0] == 15 || body[0] == 1)
                return 0;
            break;
        default:
            break;
        }
    }
invalid:
    errno = EPROTO;
    return -1;
}
