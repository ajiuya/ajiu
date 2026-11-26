#include "runtime_protocol.h"

#include <stdio.h>
#include <string.h>

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "Protocol check failed at line %d: %s\n", __LINE__, #condition); \
        return 1; \
    } \
    ++checks; \
} while (0)

int main(void)
{
    int checks = 0;
    unsigned char bytes[AJIU_HEADER_BYTES];
    struct ajiu_frame_header h;
    CHECK(ajiu_encode_header(bytes, AJIU_LAUNCH, 1) == 0);
    CHECK(ajiu_decode_header(bytes, sizeof(bytes), &h) == 0 && h.type == AJIU_LAUNCH && h.length == 1);
    for (size_t size = 0; size < AJIU_HEADER_BYTES; ++size)
        CHECK(ajiu_decode_header(bytes, size, &h) == -1);
    bytes[0] = 'X';
    CHECK(ajiu_decode_header(bytes, sizeof(bytes), &h) == -1);
    bytes[0] = 'A';
    bytes[5] = 2;
    CHECK(ajiu_decode_header(bytes, sizeof(bytes), &h) == -1);
    CHECK(ajiu_encode_header(bytes, AJIU_LAUNCH, AJIU_MAX_FRAME_BYTES + 1) == -1);
    CHECK(ajiu_encode_header(bytes, AJIU_STDIN, AJIU_MAX_DATA_BYTES + 1) == -1);
    CHECK(ajiu_encode_header(bytes, AJIU_STOP, 1) == -1);
    CHECK(ajiu_encode_header(bytes, 99, 0) == -1);
    CHECK(ajiu_encode_header(bytes, AJIU_EXIT, 8) == 0);
    CHECK(ajiu_decode_header(bytes, sizeof(bytes), &h) == 0 && h.type == AJIU_EXIT && h.length == 8);
    CHECK(ajiu_validate_input(AJIU_PHASE_RUNNING, &h, bytes) == -1);
    unsigned char body[4] = { 0, 80, 0, 24 };
    h = (struct ajiu_frame_header){ AJIU_LAUNCH, 1 };
    CHECK(ajiu_validate_input(AJIU_PHASE_INITIAL, &h, body) == 0);
    CHECK(ajiu_validate_input(AJIU_PHASE_INITIAL, &h, NULL) == -1);
    CHECK(ajiu_validate_input(AJIU_PHASE_PREPARED, &h, body) == -1);
    CHECK(ajiu_validate_input(AJIU_PHASE_RUNNING, &h, body) == -1);
    CHECK(ajiu_validate_input(AJIU_PHASE_STOPPING, &h, body) == -1);
    h = (struct ajiu_frame_header){ AJIU_GO, 0 };
    CHECK(ajiu_validate_input(AJIU_PHASE_PREPARED, &h, NULL) == 0);
    CHECK(ajiu_validate_input(AJIU_PHASE_INITIAL, &h, NULL) == -1);
    CHECK(ajiu_validate_input(AJIU_PHASE_RUNNING, &h, NULL) == -1);
    h = (struct ajiu_frame_header){ AJIU_RESIZE, 4 };
    CHECK(ajiu_validate_input(AJIU_PHASE_RUNNING, &h, body) == 0);
    body[1] = 0;
    CHECK(ajiu_validate_input(AJIU_PHASE_RUNNING, &h, body) == -1);
    body[0] = 0x20;
    CHECK(ajiu_validate_input(AJIU_PHASE_RUNNING, &h, body) == -1);
    h = (struct ajiu_frame_header){ AJIU_SIGNAL, 1 };
    for (unsigned char signal = 0; signal < 32; ++signal) {
        body[0] = signal;
        CHECK((ajiu_validate_input(AJIU_PHASE_RUNNING, &h, body) == 0) ==
              (signal == 1 || signal == 2 || signal == 15));
    }
    h = (struct ajiu_frame_header){ AJIU_HEARTBEAT, 0 };
    CHECK(ajiu_validate_input(AJIU_PHASE_RUNNING, &h, NULL) == 0);
    CHECK(ajiu_validate_input(AJIU_PHASE_STOPPING, &h, NULL) == -1);
    h = (struct ajiu_frame_header){ AJIU_STOP, 0 };
    CHECK(ajiu_validate_input(AJIU_PHASE_PREPARED, &h, NULL) == 0);
    CHECK(ajiu_validate_input(AJIU_PHASE_RUNNING, &h, NULL) == 0);
    CHECK(ajiu_validate_input(AJIU_PHASE_CLOSED, &h, NULL) == -1);
    printf("Native launch protocol checks passed: %d; no process, mount or file writes.\n", checks);
    return 0;
}
