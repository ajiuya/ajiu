#define main chroot_runtime_production_main_not_called
#include "runtime_helper.c"
#undef main

#define CHECK(value) do { if (!(value)) { fprintf(stderr, "Native control EOF check line %d: %s (errno=%d)\n", __LINE__, #value, errno); exit(1); } ++checks; } while (0)
static unsigned int checks, cases;

struct control_case {
    int sockets[2];
    struct ajiu_launch_session session;
    struct ajiu_frame_header header;
    unsigned char command[4];
    bool stopped;
    int64_t stop_at;
    int error;
};

static void open_control(struct control_case *c, bool running)
{
    *c = (struct control_case){ .session = { .phase = running ? AJIU_PHASE_RUNNING : AJIU_PHASE_PREPARED,
        .launch_seen = 1, .go_seen = running ? 1 : 0 }, .stop_at = -1 };
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, c->sockets) == 0);
    for (size_t i = 0; i < 2; ++i) {
        CHECK((fcntl(c->sockets[i], F_GETFL) & O_NONBLOCK) != 0);
        CHECK((fcntl(c->sockets[i], F_GETFD) & FD_CLOEXEC) != 0);
    }
}

static void close_control(struct control_case *c)
{
    if (c->sockets[0] >= 0) CHECK(close(c->sockets[0]) == 0);
    CHECK(close(c->sockets[1]) == 0);
    ++cases;
}

static void accept_control(struct control_case *c, bool ready, uint16_t type,
                           const unsigned char *body, uint32_t length, enum ajiu_protocol_phase phase)
{
    int saved_error = c->error;
    unsigned int go_seen = c->session.go_seen;
    CHECK(ajiu_write_frame(c->sockets[1], type, body, length, FRAME_WAIT) == 0);
    errno = 0;
    CHECK(read_control(c->sockets[0], &c->session, ready, &c->header, c->command,
        &c->stopped, 101, &c->stop_at, &c->error));
    CHECK(c->header.type == type && c->header.length == length);
    CHECK(!length || !memcmp(c->command, body, length));
    CHECK(!c->stopped && c->stop_at == -1 && c->error == saved_error);
    CHECK(c->session.phase == phase && c->session.launch_seen == 1 && !c->session.stdin_closed);
    CHECK(c->session.go_seen == (type == AJIU_GO ? 1 : go_seen));
}

static void check_boundary_eof(unsigned int state, bool framed, int stale_errno, int saved_error)
{
    struct control_case c;
    open_control(&c, false);
    bool ready = state != 0;
    if (state == 2) accept_control(&c, true, AJIU_GO, NULL, 0, AJIU_PHASE_RUNNING);
    if (framed) accept_control(&c, ready, AJIU_HEARTBEAT, NULL, 0, c.session.phase);
    CHECK(shutdown(c.sockets[1], SHUT_WR) == 0);
    struct ajiu_launch_session before = c.session;
    c.error = saved_error;
    c.header = (struct ajiu_frame_header){ .type = AJIU_GO };
    errno = stale_errno;
    CHECK(!read_control(c.sockets[0], &c.session, ready, &c.header, c.command,
        &c.stopped, 102, &c.stop_at, &c.error));
    CHECK(c.stopped && c.stop_at == 102);
    CHECK(c.error == saved_error);
    CHECK(c.session.phase == before.phase && c.session.go_seen == before.go_seen);
    CHECK(c.session.launch_seen == before.launch_seen && c.session.stdin_closed == before.stdin_closed);
    unsigned char byte;
    CHECK(recv(c.sockets[1], &byte, 1, MSG_DONTWAIT) == 0);
    CHECK(send(c.sockets[0], "x", 1, MSG_NOSIGNAL) == -1 && errno == EPIPE);
    close_control(&c);
}

static void reject_control(struct control_case *c, bool ready, int expected_error,
                           enum ajiu_protocol_phase phase)
{
    unsigned int go_seen = c->session.go_seen;
    CHECK(!read_control(c->sockets[0], &c->session, ready, &c->header, c->command,
        &c->stopped, 103, &c->stop_at, &c->error));
    CHECK(c->stopped && c->stop_at == 103);
    CHECK(c->error == expected_error && c->error != 0);
    CHECK(c->session.phase == phase && c->session.go_seen == go_seen && !c->session.stdin_closed);
    if (c->sockets[0] >= 0)
        CHECK(send(c->sockets[0], "x", 1, MSG_NOSIGNAL) == -1 && errno == EPIPE);
}

static void check_bad_wire(const unsigned char *wire, size_t length, bool running, int stale_errno, int expected_error)
{
    struct control_case c;
    open_control(&c, running);
    CHECK(send(c.sockets[1], wire, length, MSG_NOSIGNAL) == (ssize_t)length);
    CHECK(shutdown(c.sockets[1], SHUT_WR) == 0);
    errno = stale_errno;
    reject_control(&c, true, expected_error, c.session.phase);
    close_control(&c);
}

static void check_invalid_frame(uint16_t type, const unsigned char *body, uint32_t length,
                                bool running, bool ready, enum ajiu_protocol_phase phase)
{
    struct control_case c;
    open_control(&c, running);
    CHECK(ajiu_write_frame(c.sockets[1], type, body, length, FRAME_WAIT) == 0);
    CHECK(shutdown(c.sockets[1], SHUT_WR) == 0);
    errno = 0;
    reject_control(&c, ready, EPROTO, phase);
    close_control(&c);
}

static void check_truncated_and_malformed(void)
{
    unsigned char wire[AJIU_HEADER_BYTES + 4];
    CHECK(ajiu_encode_header(wire, AJIU_RESIZE, 4) == 0);
    memcpy(wire + AJIU_HEADER_BYTES, "\0\120\0\30", 4);
    for (unsigned int running = 0; running < 2; ++running) {
        for (size_t length = 1; length < AJIU_HEADER_BYTES; ++length)
            check_bad_wire(wire, length, running != 0, EPIPE, EPROTO);
        for (size_t body = 0; body < 4; ++body)
            check_bad_wire(wire, AJIU_HEADER_BYTES + body, running != 0, EPIPE, EPROTO);
    }
    CHECK(ajiu_encode_header(wire, AJIU_GO, 0) == 0);
    wire[0] = 'X';
    check_bad_wire(wire, AJIU_HEADER_BYTES, false, EPIPE, EPROTO);
    CHECK(ajiu_encode_header(wire, AJIU_GO, 0) == 0);
    wire[5] = 2;
    check_bad_wire(wire, AJIU_HEADER_BYTES, false, EPIPE, EPROTO);
    CHECK(ajiu_encode_header(wire, AJIU_GO, 0) == 0);
    wire[7] = 99;
    check_bad_wire(wire, AJIU_HEADER_BYTES, false, EPIPE, EPROTO);
    CHECK(ajiu_encode_header(wire, AJIU_GO, 0) == 0);
    wire[11] = 1;
    check_bad_wire(wire, AJIU_HEADER_BYTES, false, EPIPE, EPROTO);
    CHECK(ajiu_encode_header(wire, AJIU_READY, 32) == 0);
    check_bad_wire(wire, AJIU_HEADER_BYTES, false, EPIPE, EMSGSIZE);
}

static void check_frame_order_and_values(void)
{
    static const unsigned char resize[4] = { 0, 80, 0, 24 };
    static const unsigned char invalid_resize[][4] = {
        { 0, 0, 0, 24 }, { 0, 80, 0, 0 }, { 16, 1, 0, 24 }, { 0, 80, 16, 1 }
    };
    check_invalid_frame(AJIU_STDIN, (const unsigned char *)"x", 1, true, true, AJIU_PHASE_RUNNING);
    check_invalid_frame(AJIU_STDIN_EOF, NULL, 0, true, true, AJIU_PHASE_RUNNING);
    check_invalid_frame(AJIU_LAUNCH, (const unsigned char *)"x", 1, false, true, AJIU_PHASE_PREPARED);
    check_invalid_frame(AJIU_STDOUT, (const unsigned char *)"x", 1, true, true, AJIU_PHASE_RUNNING);
    check_invalid_frame(AJIU_STDERR, (const unsigned char *)"x", 1, true, true, AJIU_PHASE_RUNNING);
    check_invalid_frame(AJIU_ERROR, (const unsigned char *)"x", 1, true, true, AJIU_PHASE_RUNNING);
    check_invalid_frame(AJIU_GO, NULL, 0, true, true, AJIU_PHASE_CLOSED);
    check_invalid_frame(AJIU_GO, NULL, 0, false, false, AJIU_PHASE_PREPARED);
    check_invalid_frame(AJIU_RESIZE, resize, sizeof(resize), false, true, AJIU_PHASE_CLOSED);
    unsigned char sig = SIGTERM;
    check_invalid_frame(AJIU_SIGNAL, &sig, 1, false, true, AJIU_PHASE_CLOSED);
    for (size_t i = 0; i < sizeof(invalid_resize) / sizeof(*invalid_resize); ++i)
        check_invalid_frame(AJIU_RESIZE, invalid_resize[i], 4, true, true, AJIU_PHASE_CLOSED);
    static const unsigned char invalid_signals[] = { 0, SIGKILL, 255 };
    for (size_t i = 0; i < sizeof(invalid_signals); ++i)
        check_invalid_frame(AJIU_SIGNAL, invalid_signals + i, 1, true, true, AJIU_PHASE_CLOSED);
    struct control_case c;
    open_control(&c, false);
    c.session.phase = AJIU_PHASE_INITIAL;
    c.session.launch_seen = 0;
    CHECK(ajiu_write_frame(c.sockets[1], AJIU_GO, NULL, 0, FRAME_WAIT) == 0);
    errno = 0;
    reject_control(&c, true, EPROTO, AJIU_PHASE_CLOSED);
    close_control(&c);
}

static void check_valid_frames(void)
{
    struct control_case c;
    open_control(&c, false);
    accept_control(&c, false, AJIU_HEARTBEAT, NULL, 0, AJIU_PHASE_PREPARED);
    accept_control(&c, true, AJIU_GO, NULL, 0, AJIU_PHASE_RUNNING);
    accept_control(&c, true, AJIU_HEARTBEAT, NULL, 0, AJIU_PHASE_RUNNING);
    static const unsigned char resizes[][4] = { { 0, 80, 0, 24 }, { 0, 1, 0, 1 }, { 16, 0, 16, 0 } };
    for (size_t i = 0; i < sizeof(resizes) / sizeof(*resizes); ++i)
        accept_control(&c, true, AJIU_RESIZE, resizes[i], 4, AJIU_PHASE_RUNNING);
    static const unsigned char sigs[] = { SIGHUP, SIGINT, SIGTERM };
    for (size_t i = 0; i < sizeof(sigs); ++i)
        accept_control(&c, true, AJIU_SIGNAL, sigs + i, 1, AJIU_PHASE_RUNNING);
    accept_control(&c, true, AJIU_STOP, NULL, 0, AJIU_PHASE_STOPPING);
    close_control(&c);
    open_control(&c, false);
    accept_control(&c, false, AJIU_STOP, NULL, 0, AJIU_PHASE_STOPPING);
    close_control(&c);
}

static void check_read_failures(void)
{
    unsigned char wire[AJIU_HEADER_BYTES + 4];
    CHECK(ajiu_encode_header(wire, AJIU_RESIZE, 4) == 0);
    memcpy(wire + AJIU_HEADER_BYTES, "\0\120\0\30", 4);
    static const size_t lengths[] = { 0, 3, AJIU_HEADER_BYTES + 2 };
    for (size_t i = 0; i < sizeof(lengths) / sizeof(*lengths); ++i) {
        struct control_case c;
        open_control(&c, false);
        if (lengths[i]) CHECK(send(c.sockets[1], wire, lengths[i], MSG_NOSIGNAL) == (ssize_t)lengths[i]);
        errno = EPIPE;
        reject_control(&c, true, ETIMEDOUT, AJIU_PHASE_PREPARED);
        close_control(&c);
    }
    struct control_case c;
    open_control(&c, false);
    CHECK(close(c.sockets[0]) == 0);
    c.sockets[0] = -1;
    errno = 0;
    reject_control(&c, true, EBADF, AJIU_PHASE_PREPARED);
    close_control(&c);
}

static void check_unrelated_epipe(void)
{
    bool usable = false;
    errno = 0;
    CHECK(output_frame(&usable, AJIU_STDOUT, NULL, 0) == -1 && errno == EPIPE && !usable);
    int sockets[2];
    CHECK(socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, sockets) == 0);
    CHECK(close(sockets[1]) == 0);
    struct packet packet;
    errno = 0;
    CHECK(receive_packet(sockets[0], &packet) == -1 && errno == EPIPE);
    errno = 0;
    CHECK(send_packet(sockets[0], AJIU_HEARTBEAT, NULL, 0, FRAME_WAIT) == -1 && errno == EPIPE);
    CHECK(close(sockets[0]) == 0);
    ++cases;
}

int main(void)
{
    static const int saved_errors[] = { 0, EACCES, EPIPE, EIO };
    for (unsigned int state = 0; state < 3; ++state) {
        for (size_t i = 0; i < sizeof(saved_errors) / sizeof(*saved_errors); ++i) {
            check_boundary_eof(state, false, 0, saved_errors[i]);
            check_boundary_eof(state, true, EPIPE, saved_errors[i]);
        }
    }
    check_truncated_and_malformed();
    check_frame_order_and_values();
    check_valid_frames();
    check_read_failures();
    check_unrelated_epipe();
    printf("Runtime control EOF checks passed: %u across %u cases; production control decision and real nonblocking socketpairs only; no supervisor, cgroup, namespace, mount, chroot or device execution.\n", checks, cases);
    return 0;
}
