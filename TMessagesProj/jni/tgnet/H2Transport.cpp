/*
 * HTTP/2 media transport for tgnet.
 *
 * Wire contract (matches Flowseal/tg-ws-proxy's cf_h2):
 *   POST https://kws{dc}.{domain}/api   (or /api_test on test backend)
 *   content-type: application/octet-stream, accept-encoding: identity
 *   request body  = one complete plain MTProto packet payload
 *   response body = one complete plain MTProto packet payload, or a 4-byte
 *                   negative transport error code; 403/404/429/444 statuses
 *                   and HTML bodies are transport errors.
 *
 * The /api endpoint terminates the MTProto transport itself, so the bodies
 * carry the plain abridged payload (no obfuscation prefix, no AES-CTR).
 *
 * One endpoint object owns one TLS + ALPN(h2) connection shared by every
 * media ConnectionSocket of the same account, host and path. A dedicated
 * worker thread per endpoint performs blocking BoringSSL I/O with poll();
 * responses are handed back to the tgnet thread through
 * ConnectionsManager::scheduleTask so ConnectionSocket is only touched on
 * the tgnet thread.
 */

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <deque>
#include <fcntl.h>
#include <map>
#include <memory>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <nghttp2/nghttp2.h>
#include <openssl/err.h>
#include <openssl/ssl.h>
#include <poll.h>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>

#include "ConnectionSocket.h"
#include "ConnectionsManager.h"
#include "Defines.h"
#include "FileLog.h"
#include "H2Transport.h"

namespace {

const size_t H2_MAX_INFLIGHT_STREAMS = 64;
const size_t H2_MAX_INFLIGHT_BYTES = 8 * 1024 * 1024;
const size_t H2_MAX_QUEUED_BYTES = 8 * 1024 * 1024;
const size_t H2_MAX_REQUEST_BYTES = 2 * 1024 * 1024;
const size_t H2_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
const int32_t H2_MAX_REPLAYS = 2;
const int32_t H2_MAX_SETUP_ATTEMPTS = 3;
const int64_t H2_REQUEST_TIMEOUT_MS = 65000;
const int64_t H2_REPLAY_IDLE_MS = 10000;
const int64_t H2_SETUP_TIMEOUT_MS = 8000;
const int64_t H2_COOLDOWN_MS = 30000;
const int64_t H2_BACKOFF_MAX_MS = 4000;
const int64_t H2_IDLE_POLL_MS = 500;

int64_t h2Now() {
    struct timespec tp;
    clock_gettime(CLOCK_BOOTTIME, &tp);
    return (int64_t) tp.tv_sec * 1000 + (int64_t) tp.tv_nsec / 1000000;
}

/*
 * Certificate verification is intentionally disabled: tgnet's WebSocket
 * transport does the same and the CF proxy domains are user supplied relays.
 * See the transport's known tradeoffs in the change description.
 */
SSL_CTX *h2SslCtx() {
    static SSL_CTX *ctx = [] {
        SSL_CTX *c = SSL_CTX_new(TLS_client_method());
        if (c == nullptr) {
            return c;
        }
        SSL_CTX_set_min_proto_version(c, TLS1_2_VERSION);
        SSL_CTX_set_verify(c, SSL_VERIFY_NONE, nullptr);
        SSL_CTX_set_mode(c, SSL_MODE_ENABLE_PARTIAL_WRITE | SSL_MODE_ACCEPT_MOVING_WRITE_BUFFER);
        return c;
    }();
    return ctx;
}

struct PendingRequest {
    std::shared_ptr<H2ClientRef> ref;
    uint32_t generation = 0;
    std::string body;
    int32_t replays = 0;
};

struct H2Stream {
    std::shared_ptr<H2ClientRef> ref;
    uint32_t generation = 0;
    std::string body;
    size_t bodyOffset = 0;
    int32_t replays = 0;
    int64_t submittedAt = 0;
    int64_t lastActivityAt = 0;
    int32_t status = 0;
    int64_t declaredLength = -1;
    bool html = false;
    bool tooLarge = false;
    bool headersDone = false;
    bool finished = false;
    bool released = false;
    std::string response;
};

} // namespace

class H2Endpoint {
public:
    H2Endpoint(int32_t instanceNum, std::string hostName, std::string requestPath);
    ~H2Endpoint();

    void attach(const std::shared_ptr<H2ClientRef> &ref);
    void detach(const std::shared_ptr<H2ClientRef> &ref);
    void submit(const std::shared_ptr<H2ClientRef> &ref, uint32_t generation, std::string body);
    void stop();

private:
    void run();
    bool connectTls();
    void createSession();
    void destroySession();
    void closeConnection(const char *reason);
    void failEndpoint(const char *reason);
    bool pump();
    bool writeTls(const uint8_t *data, size_t length);
    bool readTls();
    void processPending();
    void submitStream(PendingRequest &&request);
    void finalizeStream(int32_t streamId, H2Stream *stream, uint32_t errorCode);
    void failStream(int32_t streamId, H2Stream *stream, const char *reason);
    void releaseStream(H2Stream *stream);
    void eraseStream(int32_t streamId);
    bool replayStream(int32_t streamId, H2Stream *stream, const char *reason);
    void markCooldown();
    void checkTimeouts();
    void cancelDetachedStreams();
    void notifyConnected();
    void notifyClientsFailed();
    bool waitForPoll(int timeoutMs, bool forSession);
    void wake();
    void drainWakeup();
    bool hasClients();
    bool hasWork();

    int32_t instanceNum;
    std::string hostName;
    std::string requestPath;

    std::thread worker;
    std::atomic<bool> running{true};
    int wakeupPipe[2] = {-1, -1};

    std::mutex mutex;
    std::vector<std::shared_ptr<H2ClientRef>> clients;
    std::deque<PendingRequest> pending;
    size_t queuedBytes = 0;
    bool sessionReady = false;

    // Worker-thread-only state.
    int socketFd = -1;
    SSL *ssl = nullptr;
    nghttp2_session *session = nullptr;
    std::map<int32_t, std::unique_ptr<H2Stream>> streams;
    size_t inflightBytes = 0;
    int32_t setupFailures = 0;
    int64_t nextConnectAt = 0;
    int64_t bytesSent = 0;
    int64_t bytesReceived = 0;

    friend struct H2Callbacks;
};

namespace {

void scheduleSocketConnected(const std::shared_ptr<H2ClientRef> &ref, uint32_t generation) {
    int32_t instance;
    {
        std::lock_guard<std::mutex> lock(ref->mutex);
        instance = ref->instance;
    }
    H2Transport::scheduleDelivery(instance, [ref, generation] {
        ConnectionSocket *socket;
        {
            std::lock_guard<std::mutex> lock(ref->mutex);
            if (ref->generation != generation) {
                return;
            }
            socket = ref->socket;
        }
        if (socket != nullptr && socket->isH2Registered(generation)) {
            socket->onH2EndpointConnected();
        }
    });
}

void scheduleSocketFailure(const std::shared_ptr<H2ClientRef> &ref, uint32_t generation) {
    int32_t instance;
    {
        std::lock_guard<std::mutex> lock(ref->mutex);
        instance = ref->instance;
    }
    H2Transport::scheduleDelivery(instance, [ref, generation] {
        ConnectionSocket *socket;
        {
            std::lock_guard<std::mutex> lock(ref->mutex);
            if (ref->generation != generation) {
                return;
            }
            socket = ref->socket;
        }
        if (socket != nullptr && socket->isH2Registered(generation)) {
            socket->onH2EndpointFailure();
        }
    });
}

void scheduleSocketResponse(const std::shared_ptr<H2ClientRef> &ref, uint32_t generation, const std::string &body) {
    auto payload = std::make_shared<std::string>(body);
    int32_t instance;
    {
        std::lock_guard<std::mutex> lock(ref->mutex);
        instance = ref->instance;
    }
    H2Transport::scheduleDelivery(instance, [ref, generation, payload] {
        ConnectionSocket *socket;
        {
            std::lock_guard<std::mutex> lock(ref->mutex);
            if (ref->generation != generation) {
                return;
            }
            socket = ref->socket;
        }
        if (socket != nullptr && socket->isH2Registered(generation)) {
            socket->onH2Response(*payload);
        }
    });
}

void reportTraffic(int32_t instance, int32_t sent, int32_t received) {
    if (sent == 0 && received == 0) {
        return;
    }
    H2Transport::scheduleDelivery(instance, [instance, sent, received] {
        ConnectionsManager::getInstance(instance).reportNetworkTraffic(sent, received);
    });
}

} // namespace

struct H2Callbacks {
    static int onHeader(nghttp2_session *session, const nghttp2_frame *frame, const uint8_t *name, size_t nameLength, const uint8_t *value, size_t valueLength, uint8_t flags, void *userData) {
        (void) flags;
        if (frame->hd.type != NGHTTP2_HEADERS || frame->headers.cat != NGHTTP2_HCAT_RESPONSE) {
            return 0;
        }
        auto *endpoint = static_cast<H2Endpoint *>(userData);
        auto it = endpoint->streams.find(frame->hd.stream_id);
        if (it == endpoint->streams.end()) {
            return 0;
        }
        H2Stream *stream = it->second.get();
        stream->lastActivityAt = h2Now();
        if (nameLength == 7 && memcmp(name, ":status", 7) == 0) {
            int32_t status = 0;
            for (size_t i = 0; i < valueLength; i++) {
                if (value[i] < '0' || value[i] > '9') {
                    return 0;
                }
                status = status * 10 + (value[i] - '0');
            }
            stream->status = status;
        } else if (nameLength == 12 && memcmp(name, "content-type", 12) == 0) {
            std::string contentType((const char *) value, valueLength);
            std::transform(contentType.begin(), contentType.end(), contentType.begin(), [](unsigned char c) { return (char) tolower(c); });
            stream->html = contentType.find("text/html") != std::string::npos;
        } else if (nameLength == 14 && memcmp(name, "content-length", 14) == 0) {
            int64_t length = 0;
            for (size_t i = 0; i < valueLength; i++) {
                if (value[i] < '0' || value[i] > '9') {
                    return 0;
                }
                length = length * 10 + (value[i] - '0');
                if (length > (int64_t) H2_MAX_RESPONSE_BYTES) {
                    length = (int64_t) H2_MAX_RESPONSE_BYTES + 1;
                    break;
                }
            }
            stream->declaredLength = length;
        }
        (void) session;
        return 0;
    }

    static int onBeginHeaders(nghttp2_session *session, const nghttp2_frame *frame, void *userData) {
        (void) session;
        if (frame->hd.type != NGHTTP2_HEADERS || frame->headers.cat != NGHTTP2_HCAT_RESPONSE) {
            return 0;
        }
        auto *endpoint = static_cast<H2Endpoint *>(userData);
        auto it = endpoint->streams.find(frame->hd.stream_id);
        if (it != endpoint->streams.end()) {
            it->second->headersDone = true;
        }
        return 0;
    }

    static int onDataChunk(nghttp2_session *session, uint8_t flags, int32_t streamId, const uint8_t *data, size_t length, void *userData) {
        (void) flags;
        auto *endpoint = static_cast<H2Endpoint *>(userData);
        auto it = endpoint->streams.find(streamId);
        if (it == endpoint->streams.end()) {
            return 0;
        }
        H2Stream *stream = it->second.get();
        stream->lastActivityAt = h2Now();
        if (!stream->tooLarge) {
            if (stream->response.size() + length > H2_MAX_RESPONSE_BYTES) {
                stream->tooLarge = true;
                if (LOGS_ENABLED) DEBUG_E("h2 %s stream %d response exceeds bound", endpoint->hostName.c_str(), streamId);
                nghttp2_submit_rst_stream(session, NGHTTP2_FLAG_NONE, streamId, NGHTTP2_INTERNAL_ERROR);
                return 0;
            }
            stream->response.append((const char *) data, length);
        }
        nghttp2_session_consume(session, streamId, length);
        return 0;
    }

    static int onStreamClose(nghttp2_session *session, int32_t streamId, uint32_t errorCode, void *userData) {
        auto *endpoint = static_cast<H2Endpoint *>(userData);
        auto it = endpoint->streams.find(streamId);
        if (it == endpoint->streams.end()) {
            return 0;
        }
        H2Stream *stream = it->second.get();
        endpoint->finalizeStream(streamId, stream, errorCode);
        endpoint->eraseStream(streamId);
        (void) session;
        return 0;
    }

    static ssize_t onDataRead(nghttp2_session *session, int32_t streamId, uint8_t *buffer, size_t length, uint32_t *dataFlags, nghttp2_data_source *source, void *userData) {
        (void) session;
        (void) streamId;
        (void) userData;
        auto *stream = static_cast<H2Stream *>(source->ptr);
        if (stream->finished) {
            *dataFlags |= NGHTTP2_DATA_FLAG_EOF;
            return 0;
        }
        size_t remaining = stream->body.size() - stream->bodyOffset;
        if (remaining == 0) {
            *dataFlags |= NGHTTP2_DATA_FLAG_EOF;
            return 0;
        }
        size_t count = std::min(length, remaining);
        memcpy(buffer, stream->body.data() + stream->bodyOffset, count);
        stream->bodyOffset += count;
        if (stream->bodyOffset == stream->body.size()) {
            *dataFlags |= NGHTTP2_DATA_FLAG_EOF;
        }
        return (ssize_t) count;
    }
};

H2Endpoint::H2Endpoint(int32_t instance, std::string host, std::string path) : instanceNum(instance), hostName(std::move(host)), requestPath(std::move(path)) {
    if (pipe(wakeupPipe) != 0) {
        wakeupPipe[0] = wakeupPipe[1] = -1;
    } else {
        for (int fd : wakeupPipe) {
            fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
            fcntl(fd, F_SETFD, FD_CLOEXEC);
        }
    }
    worker = std::thread([this] { run(); });
}

H2Endpoint::~H2Endpoint() {
    stop();
    for (int fd : wakeupPipe) {
        if (fd >= 0) {
            close(fd);
        }
    }
}

void H2Endpoint::stop() {
    if (!running.exchange(false)) {
        return;
    }
    wake();
    if (worker.joinable()) {
        worker.join();
    }
}

void H2Endpoint::wake() {
    if (wakeupPipe[1] >= 0) {
        char byte = 1;
        ssize_t written = write(wakeupPipe[1], &byte, 1);
        (void) written;
    }
}

void H2Endpoint::drainWakeup() {
    if (wakeupPipe[0] < 0) {
        return;
    }
    char buffer[64];
    while (read(wakeupPipe[0], buffer, sizeof(buffer)) > 0) {
    }
}

bool H2Endpoint::hasClients() {
    std::lock_guard<std::mutex> lock(mutex);
    return !clients.empty();
}

bool H2Endpoint::hasWork() {
    std::lock_guard<std::mutex> lock(mutex);
    return !pending.empty();
}

void H2Endpoint::attach(const std::shared_ptr<H2ClientRef> &ref) {
    bool ready;
    {
        std::lock_guard<std::mutex> lock(mutex);
        clients.push_back(ref);
        ready = sessionReady;
    }
    wake();
    if (ready) {
        scheduleSocketConnected(ref, ref->generation);
    }
}

void H2Endpoint::detach(const std::shared_ptr<H2ClientRef> &ref) {
    {
        std::lock_guard<std::mutex> lock(mutex);
        clients.erase(std::remove(clients.begin(), clients.end(), ref), clients.end());
        for (auto it = pending.begin(); it != pending.end();) {
            if (it->ref == ref) {
                queuedBytes -= it->body.size();
                it = pending.erase(it);
            } else {
                ++it;
            }
        }
    }
    {
        std::lock_guard<std::mutex> lock(ref->mutex);
        if (ref->socket != nullptr) {
            ref->socket = nullptr;
        }
    }
    wake();
}

void H2Endpoint::submit(const std::shared_ptr<H2ClientRef> &ref, uint32_t generation, std::string body) {
    if (body.empty() || body.size() > H2_MAX_REQUEST_BYTES) {
        if (LOGS_ENABLED) DEBUG_E("h2 %s dropping request with invalid size %zu", hostName.c_str(), body.size());
        scheduleSocketFailure(ref, generation);
        return;
    }
    bool overflow = false;
    {
        std::lock_guard<std::mutex> lock(mutex);
        if (queuedBytes + body.size() > H2_MAX_QUEUED_BYTES) {
            overflow = true;
        } else {
            PendingRequest request;
            request.ref = ref;
            request.generation = generation;
            request.body = std::move(body);
            queuedBytes += request.body.size();
            pending.push_back(std::move(request));
        }
    }
    if (overflow) {
        if (LOGS_ENABLED) DEBUG_E("h2 %s request queue overflow", hostName.c_str());
        scheduleSocketFailure(ref, generation);
        return;
    }
    wake();
}

void H2Endpoint::notifyConnected() {
    {
        std::lock_guard<std::mutex> lock(mutex);
        sessionReady = true;
    }
    std::vector<std::shared_ptr<H2ClientRef>> copy;
    {
        std::lock_guard<std::mutex> lock(mutex);
        copy = clients;
    }
    for (const auto &ref : copy) {
        scheduleSocketConnected(ref, ref->generation);
    }
}

void H2Endpoint::notifyClientsFailed() {
    {
        std::lock_guard<std::mutex> lock(mutex);
        sessionReady = false;
    }
    std::vector<std::shared_ptr<H2ClientRef>> copy;
    {
        std::lock_guard<std::mutex> lock(mutex);
        copy = clients;
    }
    for (const auto &ref : copy) {
        scheduleSocketFailure(ref, ref->generation);
    }
}

bool H2Endpoint::waitForPoll(int timeoutMs, bool forSession) {
    struct pollfd fds[2];
    int count = 0;
    fds[count].fd = wakeupPipe[0];
    fds[count].events = POLLIN;
    fds[count].revents = 0;
    count++;
    if (forSession && socketFd >= 0) {
        fds[count].fd = socketFd;
        fds[count].events = POLLIN;
        fds[count].revents = 0;
        count++;
    }
    int result = poll(fds, count, timeoutMs);
    if (result < 0 && errno != EINTR) {
        return false;
    }
    if (fds[0].revents & POLLIN) {
        drainWakeup();
    }
    return true;
}

bool H2Endpoint::connectTls() {
    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo *result = nullptr;
    if (getaddrinfo(hostName.c_str(), "443", &hints, &result) != 0 || result == nullptr) {
        if (LOGS_ENABLED) DEBUG_E("h2 %s unable to resolve", hostName.c_str());
        return false;
    }
    int fd = -1;
    for (struct addrinfo *it = result; it != nullptr; it = it->ai_next) {
        fd = socket(it->ai_family, it->ai_socktype, it->ai_protocol);
        if (fd < 0) {
            continue;
        }
        int yes = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(int));
        if (fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK) == -1) {
            close(fd);
            fd = -1;
            continue;
        }
        int connectResult = connect(fd, it->ai_addr, it->ai_addrlen);
        if (connectResult != 0 && errno != EINPROGRESS) {
            close(fd);
            fd = -1;
            continue;
        }
        if (connectResult != 0) {
            struct pollfd pfd = {fd, POLLOUT, 0};
            int64_t deadline = h2Now() + H2_SETUP_TIMEOUT_MS;
            bool connected = false;
            while (running) {
                int64_t remaining = deadline - h2Now();
                if (remaining <= 0) {
                    break;
                }
                int pr = poll(&pfd, 1, (int) std::min<int64_t>(remaining, 100));
                if (pr > 0) {
                    int error = 0;
                    socklen_t length = sizeof(error);
                    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length) == 0 && error == 0) {
                        connected = true;
                    }
                    break;
                }
                if (pr < 0 && errno != EINTR) {
                    break;
                }
            }
            if (!connected) {
                close(fd);
                fd = -1;
                continue;
            }
        }
        break;
    }
    freeaddrinfo(result);
    if (fd < 0) {
        return false;
    }
    socketFd = fd;

    SSL_CTX *ctx = h2SslCtx();
    if (ctx == nullptr) {
        close(socketFd);
        socketFd = -1;
        return false;
    }
    ssl = SSL_new(ctx);
    if (ssl == nullptr) {
        close(socketFd);
        socketFd = -1;
        return false;
    }
    SSL_set_tlsext_host_name(ssl, hostName.c_str());
    static const uint8_t alpn[] = {2, 'h', '2'};
    SSL_set_alpn_protos(ssl, alpn, sizeof(alpn));
    SSL_set_connect_state(ssl);
    SSL_set_fd(ssl, socketFd);

    int64_t deadline = h2Now() + H2_SETUP_TIMEOUT_MS;
    while (running) {
        int rc = SSL_connect(ssl);
        if (rc == 1) {
            break;
        }
        int error = SSL_get_error(ssl, rc);
        if (error != SSL_ERROR_WANT_READ && error != SSL_ERROR_WANT_WRITE) {
            char buffer[256];
            ERR_error_string_n(ERR_get_error(), buffer, sizeof(buffer));
            if (LOGS_ENABLED) DEBUG_E("h2 %s tls handshake failed: %s", hostName.c_str(), buffer);
            closeConnection("tls handshake");
            return false;
        }
        int64_t remaining = deadline - h2Now();
        if (remaining <= 0) {
            if (LOGS_ENABLED) DEBUG_E("h2 %s tls handshake timeout", hostName.c_str());
            closeConnection("tls timeout");
            return false;
        }
        struct pollfd pfd = {socketFd, (short) (error == SSL_ERROR_WANT_READ ? POLLIN : POLLOUT), 0};
        poll(&pfd, 1, (int) std::min<int64_t>(remaining, 100));
    }
    if (!running) {
        closeConnection("stopped");
        return false;
    }
    const uint8_t *selected = nullptr;
    unsigned int selectedLength = 0;
    SSL_get0_alpn_selected(ssl, &selected, &selectedLength);
    if (selected == nullptr || selectedLength != 2 || memcmp(selected, "h2", 2) != 0) {
        if (LOGS_ENABLED) DEBUG_E("h2 %s server did not negotiate h2", hostName.c_str());
        closeConnection("alpn");
        return false;
    }
    if (LOGS_ENABLED) DEBUG_D("h2 %s endpoint open (alpn=h2)", hostName.c_str());
    return true;
}

void H2Endpoint::createSession() {
    nghttp2_session_callbacks *callbacks = nullptr;
    if (nghttp2_session_callbacks_new(&callbacks) != 0) {
        return;
    }
    nghttp2_session_callbacks_set_on_header_callback(callbacks, H2Callbacks::onHeader);
    nghttp2_session_callbacks_set_on_begin_headers_callback(callbacks, H2Callbacks::onBeginHeaders);
    nghttp2_session_callbacks_set_on_data_chunk_recv_callback(callbacks, H2Callbacks::onDataChunk);
    nghttp2_session_callbacks_set_on_stream_close_callback(callbacks, H2Callbacks::onStreamClose);
    nghttp2_session_client_new(&session, callbacks, this);
    nghttp2_session_callbacks_del(callbacks);
    if (session == nullptr) {
        return;
    }
    nghttp2_settings_entry settings[] = {
            {NGHTTP2_SETTINGS_MAX_CONCURRENT_STREAMS, (uint32_t) H2_MAX_INFLIGHT_STREAMS},
            {NGHTTP2_SETTINGS_INITIAL_WINDOW_SIZE, 256 * 1024},
    };
    nghttp2_submit_settings(session, NGHTTP2_FLAG_NONE, settings, sizeof(settings) / sizeof(settings[0]));
    nghttp2_submit_window_update(session, NGHTTP2_FLAG_NONE, 0, 4 * 1024 * 1024 - 65535);
}

void H2Endpoint::destroySession() {
    if (session != nullptr) {
        nghttp2_session_del(session);
        session = nullptr;
    }
}

void H2Endpoint::closeConnection(const char *reason) {
    if (ssl != nullptr) {
        SSL_free(ssl);
        ssl = nullptr;
    }
    if (socketFd >= 0) {
        close(socketFd);
        socketFd = -1;
    }
    destroySession();
    {
        std::lock_guard<std::mutex> lock(mutex);
        sessionReady = false;
    }
    if (LOGS_ENABLED) DEBUG_D("h2 %s endpoint closed (%s)", hostName.c_str(), reason);
}

void H2Endpoint::failEndpoint(const char *reason) {
    if (LOGS_ENABLED) DEBUG_E("h2 %s endpoint failed (%s)", hostName.c_str(), reason);
    std::deque<PendingRequest> dropped;
    {
        std::lock_guard<std::mutex> lock(mutex);
        dropped.swap(pending);
        queuedBytes = 0;
    }
    for (auto &request : dropped) {
        scheduleSocketFailure(request.ref, request.generation);
    }
    notifyClientsFailed();
    closeConnection(reason);
    for (auto &entry : streams) {
        releaseStream(entry.second.get());
    }
    streams.clear();
    setupFailures = 0;
    nextConnectAt = h2Now() + H2_COOLDOWN_MS;
    markCooldown();
}

void H2Endpoint::processPending() {
    if (session == nullptr) {
        return;
    }
    while (streams.size() < H2_MAX_INFLIGHT_STREAMS) {
        PendingRequest request;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (pending.empty()) {
                break;
            }
            if (inflightBytes + pending.front().body.size() > H2_MAX_INFLIGHT_BYTES) {
                break;
            }
            request = std::move(pending.front());
            pending.pop_front();
            queuedBytes -= request.body.size();
        }
        submitStream(std::move(request));
    }
}

void H2Endpoint::submitStream(PendingRequest &&request) {
    if (session == nullptr) {
        return;
    }
    auto stream = std::make_unique<H2Stream>();
    stream->ref = request.ref;
    stream->generation = request.generation;
    stream->body = std::move(request.body);
    stream->replays = request.replays;
    stream->submittedAt = h2Now();
    stream->lastActivityAt = stream->submittedAt;

    auto makeNv = [](const char *name, const std::string &value) {
        nghttp2_nv nv;
        nv.name = (uint8_t *) name;
        nv.namelen = strlen(name);
        nv.value = (uint8_t *) value.data();
        nv.valuelen = value.size();
        nv.flags = NGHTTP2_NV_FLAG_NONE;
        return nv;
    };
    static const std::string method = "POST";
    static const std::string scheme = "https";
    static const std::string contentType = "application/octet-stream";
    static const std::string acceptEncoding = "identity";
    std::string length = std::to_string(stream->body.size());
    nghttp2_nv headers[] = {
            makeNv(":method", method),
            makeNv(":scheme", scheme),
            makeNv(":authority", hostName),
            makeNv(":path", requestPath),
            makeNv("content-type", contentType),
            makeNv("content-length", length),
            makeNv("accept-encoding", acceptEncoding),
    };

    nghttp2_data_provider provider;
    memset(&provider, 0, sizeof(provider));
    provider.read_callback = H2Callbacks::onDataRead;
    provider.source.ptr = stream.get();

    H2Stream *rawStream = stream.get();
    int32_t streamId = nghttp2_submit_request(session, nullptr, headers, sizeof(headers) / sizeof(headers[0]), &provider, stream.get());
    if (streamId < 0) {
        if (LOGS_ENABLED) DEBUG_E("h2 %s submit failed: %s", hostName.c_str(), nghttp2_strerror(streamId));
        scheduleSocketFailure(stream->ref, stream->generation);
        return;
    }
    if (LOGS_ENABLED) DEBUG_D("h2 %s request sent stream=%d bytes=%zu replay=%d", hostName.c_str(), streamId, rawStream->body.size(), rawStream->replays);
    inflightBytes += rawStream->body.size();
    streams[streamId] = std::move(stream);
}

void H2Endpoint::releaseStream(H2Stream *stream) {
    if (stream->released) {
        return;
    }
    stream->released = true;
    inflightBytes -= std::min(inflightBytes, stream->body.size());
}

void H2Endpoint::eraseStream(int32_t streamId) {
    auto it = streams.find(streamId);
    if (it == streams.end()) {
        return;
    }
    releaseStream(it->second.get());
    streams.erase(it);
}

void H2Endpoint::failStream(int32_t streamId, H2Stream *stream, const char *reason) {
    if (stream->finished) {
        return;
    }
    stream->finished = true;
    releaseStream(stream);
    markCooldown();
    if (LOGS_ENABLED) DEBUG_E("h2 %s stream %d failed (%s)", hostName.c_str(), streamId, reason);
    scheduleSocketFailure(stream->ref, stream->generation);
    if (session != nullptr) {
        nghttp2_submit_rst_stream(session, NGHTTP2_FLAG_NONE, streamId, NGHTTP2_CANCEL);
    } else {
        eraseStream(streamId);
    }
}

void H2Endpoint::finalizeStream(int32_t streamId, H2Stream *stream, uint32_t errorCode) {
    if (stream->finished) {
        return;
    }
    if (errorCode != NGHTTP2_NO_ERROR && !stream->tooLarge) {
        if (!replayStream(streamId, stream, nghttp2_strerror((int) errorCode))) {
            failStream(streamId, stream, nghttp2_strerror((int) errorCode));
        }
        return;
    }
    if (stream->tooLarge) {
        failStream(streamId, stream, "response too large");
        return;
    }

    int32_t status = stream->status;
    if (LOGS_ENABLED) DEBUG_D("h2 %s response stream=%d status=%d bytes=%zu", hostName.c_str(), streamId, status, stream->response.size());

    if (status == 200) {
        const std::string &body = stream->response;
        if (stream->html) {
            failStream(streamId, stream, "html body");
            return;
        }
        if (body.empty()) {
            failStream(streamId, stream, "empty response");
            return;
        }
        if (body.size() % 4 != 0 || body.size() > H2_MAX_RESPONSE_BYTES) {
            failStream(streamId, stream, "unaligned response");
            return;
        }
        stream->finished = true;
        releaseStream(stream);
        scheduleSocketResponse(stream->ref, stream->generation, body);
        return;
    }

    if (status == 403 || status == 404 || status == 429 || status == 444) {
        stream->finished = true;
        releaseStream(stream);
        int32_t code = -status;
        scheduleSocketResponse(stream->ref, stream->generation, std::string((const char *) &code, sizeof(code)));
        return;
    }

    failStream(streamId, stream, "http status");
}

void H2Endpoint::cancelDetachedStreams() {
    std::vector<int32_t> detached;
    for (auto &entry : streams) {
        bool alive;
        {
            std::lock_guard<std::mutex> lock(entry.second->ref->mutex);
            alive = entry.second->ref->socket != nullptr;
        }
        if (!alive) {
            detached.push_back(entry.first);
        }
    }
    for (int32_t streamId : detached) {
        auto it = streams.find(streamId);
        if (it == streams.end()) {
            continue;
        }
        H2Stream *stream = it->second.get();
        stream->finished = true;
        releaseStream(stream);
        if (session != nullptr) {
            nghttp2_submit_rst_stream(session, NGHTTP2_FLAG_NONE, streamId, NGHTTP2_CANCEL);
        } else {
            eraseStream(streamId);
        }
        if (LOGS_ENABLED) DEBUG_D("h2 %s cancel stream=%d (owner closed)", hostName.c_str(), streamId);
    }
}

bool H2Endpoint::replayStream(int32_t streamId, H2Stream *stream, const char *reason) {
    if (stream->replays >= H2_MAX_REPLAYS) {
        return false;
    }
    PendingRequest request;
    request.ref = stream->ref;
    request.generation = stream->generation;
    request.body = stream->body;
    request.replays = stream->replays + 1;
    stream->finished = true;
    releaseStream(stream);
    if (LOGS_ENABLED) DEBUG_D("h2 %s replay stream=%d attempt=%d (%s)", hostName.c_str(), streamId, request.replays, reason);
    {
        std::lock_guard<std::mutex> lock(mutex);
        queuedBytes += request.body.size();
        pending.push_front(std::move(request));
    }
    if (session != nullptr) {
        nghttp2_submit_rst_stream(session, NGHTTP2_FLAG_NONE, streamId, NGHTTP2_CANCEL);
    } else {
        eraseStream(streamId);
    }
    wake();
    return true;
}

void H2Endpoint::checkTimeouts() {
    int64_t now = h2Now();
    std::vector<int32_t> expired;
    for (auto &entry : streams) {
        H2Stream *stream = entry.second.get();
        if (stream->finished) {
            continue;
        }
        bool hardTimeout = now - stream->submittedAt > H2_REQUEST_TIMEOUT_MS;
        bool idle = now - stream->lastActivityAt > H2_REPLAY_IDLE_MS;
        if (hardTimeout || idle) {
            expired.push_back(entry.first);
        }
    }
    for (int32_t streamId : expired) {
        auto it = streams.find(streamId);
        if (it == streams.end()) {
            continue;
        }
        H2Stream *stream = it->second.get();
        bool hardTimeout = now - stream->submittedAt > H2_REQUEST_TIMEOUT_MS;
        if (hardTimeout || !replayStream(streamId, stream, hardTimeout ? "request timeout" : "no response")) {
            failStream(streamId, stream, hardTimeout ? "timeout" : "no response");
        }
    }
}

bool H2Endpoint::writeTls(const uint8_t *data, size_t length) {
    size_t offset = 0;
    int64_t deadline = h2Now() + 15000;
    while (offset < length && running) {
        size_t chunk = std::min<size_t>(length - offset, 16384);
        int written = SSL_write(ssl, data + offset, (int) chunk);
        if (written > 0) {
            offset += (size_t) written;
            bytesSent += written;
            continue;
        }
        int error = SSL_get_error(ssl, written);
        if (error != SSL_ERROR_WANT_READ && error != SSL_ERROR_WANT_WRITE) {
            return false;
        }
        int64_t remaining = deadline - h2Now();
        if (remaining <= 0) {
            return false;
        }
        struct pollfd pfd = {socketFd, (short) (error == SSL_ERROR_WANT_READ ? POLLIN : POLLOUT), 0};
        int result = poll(&pfd, 1, (int) std::min<int64_t>(remaining, 100));
        if (result < 0 && errno != EINTR) {
            return false;
        }
    }
    return offset == length;
}

bool H2Endpoint::readTls() {
    uint8_t buffer[16384];
    while (running && ssl != nullptr) {
        int read = SSL_read(ssl, buffer, sizeof(buffer));
        if (read > 0) {
            bytesReceived += read;
            ssize_t consumed = nghttp2_session_mem_recv(session, buffer, (size_t) read);
            if (consumed < 0) {
                if (LOGS_ENABLED) DEBUG_E("h2 %s mem_recv failed: %s", hostName.c_str(), nghttp2_strerror((int) consumed));
                return false;
            }
            continue;
        }
        int error = SSL_get_error(ssl, read);
        if (error == SSL_ERROR_WANT_READ) {
            break;
        }
        if (error == SSL_ERROR_WANT_WRITE) {
            struct pollfd pfd = {socketFd, POLLOUT, 0};
            if (poll(&pfd, 1, 100) < 0 && errno != EINTR) {
                return false;
            }
            continue;
        }
        if (error == SSL_ERROR_ZERO_RETURN) {
            if (LOGS_ENABLED) DEBUG_D("h2 %s tls closed by peer", hostName.c_str());
        }
        return false;
    }
    return true;
}

bool H2Endpoint::pump() {
    if (session == nullptr) {
        return false;
    }
    processPending();
    while (running) {
        const uint8_t *data = nullptr;
        ssize_t length = nghttp2_session_mem_send(session, &data);
        if (length < 0) {
            if (LOGS_ENABLED) DEBUG_E("h2 %s mem_send failed: %s", hostName.c_str(), nghttp2_strerror((int) length));
            return false;
        }
        if (length == 0) {
            break;
        }
        if (!writeTls(data, (size_t) length)) {
            return false;
        }
    }
    if (!readTls()) {
        return false;
    }
    if (bytesSent > 0 || bytesReceived > 0) {
        reportTraffic(instanceNum, (int32_t) std::min<int64_t>(bytesSent, INT32_MAX), (int32_t) std::min<int64_t>(bytesReceived, INT32_MAX));
        bytesSent = 0;
        bytesReceived = 0;
    }
    return true;
}

void H2Endpoint::run() {
    while (running) {
        if (session == nullptr) {
            if (!hasClients() && !hasWork()) {
                waitForPoll((int) H2_IDLE_POLL_MS, false);
                continue;
            }
            int64_t now = h2Now();
            if (now < nextConnectAt) {
                waitForPoll((int) std::min<int64_t>(nextConnectAt - now, 100), false);
                continue;
            }
            if (!connectTls()) {
                setupFailures++;
                int64_t backoff = std::min<int64_t>(H2_BACKOFF_MAX_MS, 250LL << std::min<int32_t>(setupFailures, 4));
                nextConnectAt = h2Now() + backoff;
                closeConnection("setup");
                if (setupFailures >= H2_MAX_SETUP_ATTEMPTS) {
                    failEndpoint("setup attempts");
                }
                continue;
            }
            setupFailures = 0;
            createSession();
            if (session == nullptr) {
                closeConnection("session");
                setupFailures++;
                nextConnectAt = h2Now() + 1000;
                continue;
            }
            notifyConnected();
            continue;
        }

        if (!pump()) {
            closeConnection("io");
            // Replay in-flight requests on the next connection.
            std::deque<PendingRequest> replay;
            for (auto &entry : streams) {
                H2Stream *stream = entry.second.get();
                if (stream->finished) {
                    continue;
                }
                stream->finished = true;
                if (stream->replays < H2_MAX_REPLAYS) {
                    PendingRequest request;
                    request.ref = stream->ref;
                    request.generation = stream->generation;
                    request.body = std::move(stream->body);
                    request.replays = stream->replays + 1;
                    if (LOGS_ENABLED) DEBUG_D("h2 %s replay stream=%d attempt=%d after io failure", hostName.c_str(), entry.first, request.replays);
                    replay.push_back(std::move(request));
                } else {
                    scheduleSocketFailure(stream->ref, stream->generation);
                }
            }
            streams.clear();
            inflightBytes = 0;
            {
                std::lock_guard<std::mutex> lock(mutex);
                for (auto it = replay.rbegin(); it != replay.rend(); ++it) {
                    queuedBytes += it->body.size();
                    pending.push_front(std::move(*it));
                }
            }
            setupFailures++;
            nextConnectAt = h2Now() + std::min<int64_t>(H2_BACKOFF_MAX_MS, 250LL << std::min<int32_t>(setupFailures, 4));
            if (setupFailures >= H2_MAX_SETUP_ATTEMPTS) {
                failEndpoint("reconnect attempts");
            }
            continue;
        }
        checkTimeouts();
        cancelDetachedStreams();
        if (!hasClients() && !hasWork() && streams.empty()) {
            closeConnection("idle");
            continue;
        }
        struct pollfd fds[2];
        fds[0].fd = wakeupPipe[0];
        fds[0].events = POLLIN;
        fds[1].fd = socketFd;
        fds[1].events = POLLIN;
        if (nghttp2_session_want_write(session)) {
            fds[1].events |= POLLOUT;
        }
        int result = poll(fds, 2, 100);
        if (fds[0].revents & POLLIN) {
            drainWakeup();
        }
        if (result < 0 && errno != EINTR) {
            closeConnection("poll");
            setupFailures++;
            nextConnectAt = h2Now() + 500;
            if (setupFailures >= H2_MAX_SETUP_ATTEMPTS) {
                failEndpoint("poll error");
            }
        }
    }
    closeConnection("stopped");
}

void H2Transport::scheduleDelivery(int32_t instance, std::function<void()> task) {
    ConnectionsManager::getInstance(instance).scheduleTask(std::move(task));
}

// ---------------------------------------------------------------------------
// Registry

struct H2Key {
    int32_t instance;
    std::string host;
    std::string path;

    bool operator<(const H2Key &other) const {
        if (instance != other.instance) {
            return instance < other.instance;
        }
        if (host != other.host) {
            return host < other.host;
        }
        return path < other.path;
    }
};

static std::mutex registryMutex;
static std::map<H2Key, std::shared_ptr<H2Endpoint>> endpoints;
static std::map<H2Key, int64_t> cooldowns;

void H2Endpoint::markCooldown() {
    H2Key key{instanceNum, hostName, requestPath};
    std::lock_guard<std::mutex> lock(registryMutex);
    cooldowns[key] = h2Now() + H2_COOLDOWN_MS;
}

std::shared_ptr<H2ClientRef> H2Transport::createRef(ConnectionSocket *socket, int32_t instance, uint32_t generation, const std::string &host, const std::string &path) {
    auto ref = std::make_shared<H2ClientRef>();
    ref->socket = socket;
    ref->instance = instance;
    ref->generation = generation;
    ref->host = host;
    ref->path = path;
    return ref;
}

void H2Transport::attach(const std::shared_ptr<H2ClientRef> &ref) {
    H2Key key{ref->instance, ref->host, ref->path};
    std::shared_ptr<H2Endpoint> endpoint;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        auto it = endpoints.find(key);
        if (it == endpoints.end()) {
            endpoint = std::make_shared<H2Endpoint>(ref->instance, ref->host, ref->path);
            endpoints[key] = endpoint;
        } else {
            endpoint = it->second;
        }
    }
    endpoint->attach(ref);
}

void H2Transport::detach(const std::shared_ptr<H2ClientRef> &ref) {
    std::shared_ptr<H2Endpoint> endpoint;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        auto it = endpoints.find(H2Key{ref->instance, ref->host, ref->path});
        if (it != endpoints.end()) {
            endpoint = it->second;
        }
    }
    if (endpoint != nullptr) {
        endpoint->detach(ref);
    }
}

void H2Transport::submit(const std::shared_ptr<H2ClientRef> &ref, uint32_t generation, const uint8_t *data, size_t length) {
    std::shared_ptr<H2Endpoint> endpoint;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        auto it = endpoints.find(H2Key{ref->instance, ref->host, ref->path});
        if (it != endpoints.end()) {
            endpoint = it->second;
        }
    }
    if (endpoint == nullptr) {
        scheduleSocketFailure(ref, generation);
        return;
    }
    endpoint->submit(ref, generation, std::string((const char *) data, length));
}

bool H2Transport::available(int32_t instance, const std::string &host, const std::string &path) {
    H2Key key{instance, host, path};
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it = cooldowns.find(key);
    return it == cooldowns.end() || it->second <= h2Now();
}

void H2Transport::resetInstance(int32_t instance) {
    std::vector<std::shared_ptr<H2Endpoint>> stopped;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        for (auto it = endpoints.begin(); it != endpoints.end();) {
            if (it->first.instance == instance) {
                stopped.push_back(it->second);
                it = endpoints.erase(it);
            } else {
                ++it;
            }
        }
        for (auto it = cooldowns.begin(); it != cooldowns.end();) {
            if (it->first.instance == instance) {
                it = cooldowns.erase(it);
            } else {
                ++it;
            }
        }
    }
    for (auto &endpoint : stopped) {
        endpoint->stop();
    }
}
