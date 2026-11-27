#define main chroot_runtime_production_main_not_called
#ifndef AJIU_RUNTIME_HELPER_SOURCE
#define AJIU_RUNTIME_HELPER_SOURCE "runtime_helper.c"
#endif
#include AJIU_RUNTIME_HELPER_SOURCE
#undef main

#define CHECK(value) do { if (!(value)) { fprintf(stderr, "Native supervision check line %d: %s (errno=%d)\n", __LINE__, #value, errno); exit(1); } ++checks; } while (0)
static unsigned int checks;

struct deadline_case {
    struct ajiu_launch launch;
    struct runtime runtime;
    int alive[2];
    bool stopped;
    int64_t stop_at;
};

static void open_deadline(struct deadline_case *c)
{
    *c = (struct deadline_case){ .launch = { .lease_ms = 5000, .command_timeout_ms = 2000 } };
    CHECK(pipe2(c->alive, O_CLOEXEC | O_NONBLOCK) == 0);
    c->runtime = (struct runtime){ .launch = &c->launch, .app_fd = c->alive[0] };
    interrupted = 0;
}

static int deadline_step(struct deadline_case *c, bool ready, int64_t now, int64_t lease, int64_t command)
{
    return supervision_stop_reason(&c->runtime, ready, 1000, lease, command, now, &c->stopped, &c->stop_at);
}

static void close_deadline(struct deadline_case *c)
{
    CHECK(close(c->alive[0]) == 0 && close(c->alive[1]) == 0);
    interrupted = 0;
}

static void check_running_timeouts(void)
{
    struct deadline_case c;
    open_deadline(&c);
    CHECK(deadline_step(&c, true, 2999, 1000, 1000) == 0 && !c.stopped && !c.stop_at);
    CHECK(deadline_step(&c, true, 3000, 1000, 1000) == ETIMEDOUT && c.stopped && c.stop_at == 3000);
    c.stopped = false; c.stop_at = 0;
    CHECK(deadline_step(&c, true, 5999, 1000, 0) == 0 && !c.stopped);
    CHECK(deadline_step(&c, true, 6000, 1000, 0) == ETIMEDOUT && c.stopped && c.stop_at == 6000);
    c.stopped = false; c.stop_at = 0;
    CHECK(deadline_step(&c, false, 15999, 15999, 0) == 0 && !c.stopped);
    CHECK(deadline_step(&c, false, 16000, 16000, 0) == ETIMEDOUT && c.stopped && c.stop_at == 16000);
    c.stopped = false; c.stop_at = 0; c.launch.command_timeout_ms = 0;
    CHECK(deadline_step(&c, true, 90000, 90000, 1000) == 0 && !c.stopped);
    close_deadline(&c);
}

static void check_exit_command_boundary(void)
{
    struct deadline_case c;
    open_deadline(&c);
    CHECK(deadline_step(&c, true, 2999, 1000, 1000) == 0);
    c.stopped = true; c.stop_at = 2999;
    CHECK(deadline_step(&c, true, 3000, 1000, 1000) == 0 && c.stop_at == 2999);
    CHECK(deadline_step(&c, true, 4000, 1000, 1000) == 0 && c.stopped && c.stop_at == 2999);
    close_deadline(&c);
}

static void check_exit_lease_boundary(void)
{
    struct deadline_case c;
    open_deadline(&c);
    CHECK(deadline_step(&c, true, 5999, 1000, 0) == 0);
    c.stopped = true; c.stop_at = 5999;
    CHECK(deadline_step(&c, true, 6000, 1000, 0) == 0 && c.stop_at == 5999);
    CHECK(deadline_step(&c, false, 16000, 1000, 1000) == 0 && c.stopped && c.stop_at == 5999);
    close_deadline(&c);
}

static void check_stop_safety(void)
{
    struct deadline_case c;
    open_deadline(&c);
    interrupted = 1;
    CHECK(deadline_step(&c, true, 1200, 1000, 1000) == ECANCELED && c.stopped && c.stop_at == 1200);
    CHECK(deadline_step(&c, true, 1300, 1000, 1000) == ECANCELED && c.stop_at == 1200);
    interrupted = 0;
    CHECK(write(c.alive[1], "x", 1) == 1);
    CHECK(deadline_step(&c, true, 1400, 1000, 1000) == ETIMEDOUT && c.stop_at == 1200);
    close_deadline(&c);
}

static void check_live_input(void)
{
    int pipefd[2];
    CHECK(pipe2(pipefd, O_CLOEXEC | O_NONBLOCK) == 0);
    struct controller_io io = { .input = pipefd[1], .master = -1 };
    unsigned char pending[AJIU_MAX_FRAME_BYTES];
    memset(pending, 'a', sizeof(pending));
    size_t used = sizeof(pending), offset = 0;
    bool eof = false;
    CHECK(!controller_input_ready(&io, pending, &used, &offset, false));
    CHECK(used == sizeof(pending) && offset == 0 && io.input == pipefd[1]);
    offset = AJIU_MAX_DATA_BYTES;
    CHECK(controller_input_ready(&io, pending, &used, &offset, false));
    CHECK(used == sizeof(pending) - AJIU_MAX_DATA_BYTES && !offset);
    struct packet packet = { .type = AJIU_STDIN, .length = AJIU_MAX_DATA_BYTES };
    memset(packet.body, 'b', sizeof(packet.body));
    CHECK(controller_queue_input(&packet, &io, pending, &used, true, false, &eof, false) == 0);
    CHECK(used == sizeof(pending) && pending[used - 1] == 'b' && !eof);
    used = 0;
    packet.length = 3;
    memcpy(packet.body, "abc", 3);
    CHECK(controller_queue_input(&packet, &io, pending, &used, true, false, &eof, false) == 0);
    packet.type = AJIU_STDIN_EOF; packet.length = 0;
    CHECK(controller_queue_input(&packet, &io, pending, &used, true, false, &eof, false) == 0 && eof);
    CHECK(flush_input(&io, pending, &used, &offset, eof, false) == 0);
    CHECK(io.input == -1 && !used && !offset);
    unsigned char actual[4];
    CHECK(read(pipefd[0], actual, sizeof(actual)) == 3 && !memcmp(actual, "abc", 3));
    CHECK(read(pipefd[0], actual, sizeof(actual)) == 0);
    CHECK(close(pipefd[0]) == 0);
}

static void check_late_input(void)
{
    struct controller_io io = { .input = -1, .master = -1 };
    unsigned char pending[AJIU_MAX_FRAME_BYTES];
    size_t used = 0;
    bool eof = false;
    struct packet packet = { .type = AJIU_STDIN, .length = AJIU_MAX_DATA_BYTES };
    CHECK(controller_queue_input(&packet, &io, pending, &used, true, false, &eof, true) == 0);
    CHECK(!used && !eof);
    packet.type = AJIU_STDIN_EOF; packet.length = 0;
    CHECK(controller_queue_input(&packet, &io, pending, &used, true, false, &eof, true) == 0);
    CHECK(!used && eof);
    packet.type = AJIU_STDIN; packet.length = AJIU_MAX_DATA_BYTES;
    CHECK(controller_queue_input(&packet, &io, pending, &used, true, true, &eof, true) == 0);
    CHECK(!used && eof);
    packet.type = AJIU_STDIN_EOF; packet.length = 0;
    CHECK(controller_queue_input(&packet, &io, pending, &used, true, true, &eof, true) == 0);
    CHECK(!used && eof);
}

static void check_invalid_input(void)
{
    struct controller_io io = { .input = -1, .master = -1 };
    unsigned char pending[AJIU_MAX_FRAME_BYTES];
    size_t used = 0;
    bool eof = false;
    struct packet packet = { .type = AJIU_STDIN, .length = 1 };
    CHECK(controller_queue_input(&packet, &io, pending, &used, false, false, &eof, false) == -1 && errno == EPROTO);
    packet.type = AJIU_STDIN_EOF;
    CHECK(controller_queue_input(&packet, &io, pending, &used, true, true, &eof, false) == -1 && errno == EPROTO);
    packet.type = AJIU_HEARTBEAT; packet.length = 0;
    CHECK(controller_queue_input(&packet, &io, pending, &used, true, true, &eof, false) == -1 && errno == EPROTO);
    packet.type = AJIU_STDIN; packet.length = 1; eof = true;
    CHECK(controller_queue_input(&packet, &io, pending, &used, true, false, &eof, false) == -1 && errno == EPROTO);
    CHECK(!used && eof);
}

static void drain_finish(struct controller_io *io, bool stopped)
{
    int sockets[2];
    CHECK(socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, sockets) == 0);
    unsigned char pending[AJIU_MAX_FRAME_BYTES];
    memset(pending, 'x', sizeof(pending));
    size_t used = sizeof(pending), offset = 17;
    bool eof = false, finish = false;
    CHECK(send_packet(sockets[1], AJIU_STDIN, "late", 4, FRAME_WAIT) == 0);
    CHECK(send_packet(sockets[1], AJIU_STDIN_EOF, NULL, 0, FRAME_WAIT) == 0);
    CHECK(send_packet(sockets[1], AJIU_HEARTBEAT, NULL, 0, FRAME_WAIT) == 0);
    CHECK(send_packet(sockets[1], MSG_FINISH, NULL, 0, FRAME_WAIT) == 0);
    for (unsigned int i = 0; i < 4; ++i) {
        CHECK(controller_input_ready(io, pending, &used, &offset, stopped));
        CHECK(!used && !offset);
        struct packet packet;
        CHECK(receive_packet(sockets[0], &packet) == 1);
        if (packet.type == AJIU_STDIN || packet.type == AJIU_STDIN_EOF) {
            CHECK(controller_queue_input(&packet, io, pending, &used, true, stopped, &eof, io->master >= 0) == 0);
        } else if (packet.type == MSG_FINISH) {
            CHECK(i == 3 && !packet.length && eof);
            finish = true;
        } else CHECK(packet.type == AJIU_HEARTBEAT && !packet.length);
        CHECK(flush_input(io, pending, &used, &offset, eof, io->master >= 0) == 0);
        CHECK(!used && !offset);
    }
    CHECK(finish && io->input == -1);
    CHECK(close(sockets[0]) == 0 && close(sockets[1]) == 0);
}

static void check_eof_backlog(void)
{
    struct controller_io io = { .input = -1, .output = -1, .error = -1, .master = -1 };
    drain_finish(&io, false);
    drain_finish(&io, true);
}

static void check_stopped_backlog(void)
{
    int input[2], output[2], sockets[2];
    CHECK(pipe2(input, O_CLOEXEC | O_NONBLOCK) == 0);
    CHECK(pipe2(output, O_CLOEXEC | O_NONBLOCK) == 0);
    CHECK(socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, sockets) == 0);
    struct controller_io io = { .input = input[1], .output = output[0], .error = -1, .master = -1 };
    CHECK(write(output[1], "tail", 4) == 4 && close(output[1]) == 0);
    drain_finish(&io, true);
    CHECK(output_step(sockets[0], &io.output, AJIU_STDOUT, false, false) == 0);
    struct packet packet;
    CHECK(receive_packet(sockets[1], &packet) == 1 && packet.type == AJIU_STDOUT && packet.length == 4);
    CHECK(!memcmp(packet.body, "tail", 4));
    CHECK(output_step(sockets[0], &io.output, AJIU_STDOUT, false, false) == 0 && io.output == -1);
    unsigned char byte;
    CHECK(read(input[0], &byte, 1) == 0 && close(input[0]) == 0);
    CHECK(close(sockets[0]) == 0 && close(sockets[1]) == 0);
}

static void check_pty_output_survives_stop(void)
{
    int duplex[2], sockets[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, duplex) == 0);
    CHECK(socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, sockets) == 0);
    struct controller_io io = { .input = duplex[0], .output = duplex[0], .error = -1, .master = duplex[0] };
    CHECK(write(duplex[1], "tail", 4) == 4 && shutdown(duplex[1], SHUT_WR) == 0);
    drain_finish(&io, true);
    CHECK(fcntl(io.master, F_GETFD) >= 0 && io.output == io.master);
    CHECK(output_step(sockets[0], &io.output, AJIU_STDOUT, true, false) == 0);
    struct packet packet;
    CHECK(receive_packet(sockets[1], &packet) == 1 && packet.type == AJIU_STDOUT && packet.length == 4);
    CHECK(!memcmp(packet.body, "tail", 4));
    CHECK(output_step(sockets[0], &io.output, AJIU_STDOUT, true, false) == 0 && io.output == -1);
    CHECK(close(duplex[1]) == 0 && close(sockets[0]) == 0 && close(sockets[1]) == 0);
}

int main(int argc, char **argv)
{
    static const struct { const char *name; void (*run)(void); } tests[] = {
        { "running-timeouts", check_running_timeouts },
        { "exit-command-boundary", check_exit_command_boundary },
        { "exit-lease-boundary", check_exit_lease_boundary },
        { "stop-safety", check_stop_safety },
        { "live-input", check_live_input },
        { "late-input", check_late_input },
        { "invalid-input", check_invalid_input },
        { "eof-backlog", check_eof_backlog },
        { "stopped-backlog", check_stopped_backlog },
        { "pty-output-survives-stop", check_pty_output_survives_stop }
    };
    unsigned int cases = 0;
    CHECK(argc == 1 || argc == 2);
    for (size_t i = 0; i < sizeof(tests) / sizeof(*tests); ++i) {
        if (argc == 2 && strcmp(argv[1], tests[i].name)) continue;
        tests[i].run();
        ++cases;
    }
    CHECK(cases > 0);
    printf("Runtime supervision checks passed: %u across %u cases; production deadline/input decisions, synthetic liveness and real nonblocking pipes/socketpairs; no production main, cgroup, namespace, mount, chroot or device execution.\n", checks, cases);
    return 0;
}
