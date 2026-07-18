/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2015-2018.
 */

#include <cassert>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <sys/socket.h>
#include <memory.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <openssl/rand.h>
#include <openssl/hmac.h>
#include <algorithm>
#include <utility>
#include <openssl/bn.h>
#include <openssl/ssl.h>
#include <openssl/bio.h>
#include <openssl/err.h>
#include "ByteStream.h"
#include "ConnectionSocket.h"
#include "FileLog.h"
#include "Defines.h"
#include "ConnectionsManager.h"
#include "EventObject.h"
#include "Timer.h"
#include "NativeByteBuffer.h"
#include "BuffersStorage.h"
#include "Connection.h"
#include "TLSHello.h"
#include "H2Transport.h"
#include <random>
#include <chrono>
#include <mutex>
#include <thread>
#include <vector>
#include <poll.h>

#ifndef EPOLLRDHUP
#define EPOLLRDHUP 0x2000
#endif

#define MAX_GREASE 8

static SSL_CTX *getWebSocketSslCtx() {
    static SSL_CTX *ctx = [] {
        SSL_CTX *c = SSL_CTX_new(TLS_client_method());
        SSL_CTX_set_min_proto_version(c, TLS1_2_VERSION);
        SSL_CTX_set_verify(c, SSL_VERIFY_NONE, nullptr);
        return c;
    }();
    return ctx;
}

static std::string webSocketBase64(const uint8_t *data, size_t length) {
    static const char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string result;
    size_t i = 0;
    while (i + 3 <= length) {
        uint32_t n = (data[i] << 16) | (data[i + 1] << 8) | data[i + 2];
        result.push_back(alphabet[(n >> 18) & 0x3f]);
        result.push_back(alphabet[(n >> 12) & 0x3f]);
        result.push_back(alphabet[(n >> 6) & 0x3f]);
        result.push_back(alphabet[n & 0x3f]);
        i += 3;
    }
    if (length - i == 1) {
        uint32_t n = data[i] << 16;
        result.push_back(alphabet[(n >> 18) & 0x3f]);
        result.push_back(alphabet[(n >> 12) & 0x3f]);
        result.push_back('=');
        result.push_back('=');
    } else if (length - i == 2) {
        uint32_t n = (data[i] << 16) | (data[i + 1] << 8);
        result.push_back(alphabet[(n >> 18) & 0x3f]);
        result.push_back(alphabet[(n >> 12) & 0x3f]);
        result.push_back(alphabet[(n >> 6) & 0x3f]);
        result.push_back('=');
    }
    return result;
}

static const size_t webSocketHeaderReserve = 14;

static void webSocketMaskPayload(uint8_t *payload, size_t length, const uint8_t *mask) {
    uint32_t maskWord;
    memcpy(&maskWord, mask, 4);
    size_t i = 0;
    for (; i + 4 <= length; i += 4) {
        uint32_t chunk;
        memcpy(&chunk, payload + i, 4);
        chunk ^= maskWord;
        memcpy(payload + i, &chunk, 4);
    }
    for (; i < length; i++) {
        payload[i] ^= mask[i & 3];
    }
}

static void webSocketAppendFrameHeader(std::string &out, uint8_t opcode, uint64_t length, const uint8_t *mask) {
    out.push_back((char) (0x80 | opcode));
    if (length < 126) {
        out.push_back((char) (0x80 | (uint8_t) length));
    } else if (length <= 0xffff) {
        out.push_back((char) (0x80 | 126));
        out.push_back((char) ((length >> 8) & 0xff));
        out.push_back((char) (length & 0xff));
    } else {
        out.push_back((char) (0x80 | 127));
        for (int32_t i = 7; i >= 0; i--) {
            out.push_back((char) ((length >> (8 * i)) & 0xff));
        }
    }
    out.append((const char *) mask, 4);
}

namespace {

struct WarmWebSocket {
    int fd = -1;
    SSL *ssl = nullptr;
    BIO *readBio = nullptr;
    BIO *writeBio = nullptr;
    std::string host;
    std::string path;
    std::string leftover;
    int64_t created = 0;
};

const int32_t WS_POOL_MAX_ENTRIES = 4;
const int64_t WS_POOL_MAX_AGE_MS = 120000;
const int64_t WS_POOL_HANDSHAKE_TIMEOUT_MS = 8000;
const size_t WS_POOL_MAX_RESPONSE_HEADER = 16384;

std::mutex wsPoolMutex;
std::vector<WarmWebSocket> wsPool;
int64_t wsPoolGeneration = 0;
int32_t wsPoolInFlight = 0;

int64_t wsPoolNow() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

void wsPoolClose(WarmWebSocket &entry) {
    if (entry.ssl != nullptr) {
        SSL_free(entry.ssl);
        entry.ssl = nullptr;
        entry.readBio = nullptr;
        entry.writeBio = nullptr;
    }
    if (entry.fd >= 0) {
        close(entry.fd);
        entry.fd = -1;
    }
}

bool wsPoolWait(int fd, short events, int64_t deadline) {
    int64_t remaining = deadline - wsPoolNow();
    if (remaining <= 0) {
        return false;
    }
    struct pollfd p;
    p.fd = fd;
    p.events = events;
    p.revents = 0;
    if (poll(&p, 1, (int) remaining) <= 0) {
        return false;
    }
    return (p.revents & (POLLERR | POLLHUP | POLLNVAL)) == 0;
}

bool wsPoolFlush(BIO *writeBio, int fd, int64_t deadline) {
    char buffer[16384];
    while (BIO_ctrl_pending(writeBio) > 0) {
        int read = BIO_read(writeBio, buffer, sizeof(buffer));
        if (read <= 0) {
            break;
        }
        int offset = 0;
        while (offset < read) {
            ssize_t sent = send(fd, buffer + offset, read - offset, 0);
            if (sent > 0) {
                offset += (int) sent;
            } else if (sent == 0) {
                return false;
            } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
                if (!wsPoolWait(fd, POLLOUT, deadline)) {
                    return false;
                }
            } else {
                return false;
            }
        }
    }
    return true;
}

bool wsPoolPumpRead(BIO *readBio, int fd, int64_t deadline) {
    char buffer[16384];
    while (true) {
        ssize_t read = recv(fd, buffer, sizeof(buffer), 0);
        if (read > 0) {
            BIO_write(readBio, buffer, (int) read);
            return true;
        }
        if (read < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (!wsPoolWait(fd, POLLIN, deadline)) {
                return false;
            }
            continue;
        }
        return false;
    }
}

bool wsPoolSslHandshake(SSL *ssl, BIO *readBio, BIO *writeBio, int fd, int64_t deadline) {
    while (true) {
        ERR_clear_error();
        int result = SSL_do_handshake(ssl);
        if (result == 1) {
            return true;
        }
        int error = SSL_get_error(ssl, result);
        if (error == SSL_ERROR_WANT_READ) {
            if (!wsPoolFlush(writeBio, fd, deadline) || !wsPoolPumpRead(readBio, fd, deadline)) {
                return false;
            }
        } else if (error == SSL_ERROR_WANT_WRITE) {
            if (!wsPoolFlush(writeBio, fd, deadline) || !wsPoolWait(fd, POLLOUT, deadline)) {
                return false;
            }
        } else {
            return false;
        }
    }
}

bool wsPoolSslWrite(SSL *ssl, BIO *readBio, BIO *writeBio, int fd, const std::string &data, int64_t deadline) {
    size_t offset = 0;
    while (offset < data.size()) {
        ERR_clear_error();
        int written = SSL_write(ssl, data.data() + offset, (int) (data.size() - offset));
        if (written > 0) {
            offset += (size_t) written;
            if (!wsPoolFlush(writeBio, fd, deadline)) {
                return false;
            }
            continue;
        }
        int error = SSL_get_error(ssl, written);
        if (error == SSL_ERROR_WANT_WRITE) {
            if (!wsPoolFlush(writeBio, fd, deadline) || !wsPoolWait(fd, POLLOUT, deadline)) {
                return false;
            }
        } else if (error == SSL_ERROR_WANT_READ) {
            if (!wsPoolFlush(writeBio, fd, deadline) || !wsPoolPumpRead(readBio, fd, deadline)) {
                return false;
            }
        } else {
            return false;
        }
    }
    return wsPoolFlush(writeBio, fd, deadline);
}

bool wsPoolReadUpgrade(SSL *ssl, BIO *readBio, BIO *writeBio, int fd, std::string &response, int64_t deadline) {
    char buffer[4096];
    while (response.find("\r\n\r\n") == std::string::npos) {
        if (response.size() > WS_POOL_MAX_RESPONSE_HEADER) {
            return false;
        }
        ERR_clear_error();
        int read = SSL_read(ssl, buffer, sizeof(buffer));
        if (read > 0) {
            response.append(buffer, read);
            continue;
        }
        int error = SSL_get_error(ssl, read);
        if (error == SSL_ERROR_WANT_READ) {
            if (!wsPoolFlush(writeBio, fd, deadline) || !wsPoolPumpRead(readBio, fd, deadline)) {
                return false;
            }
        } else if (error == SSL_ERROR_WANT_WRITE) {
            if (!wsPoolFlush(writeBio, fd, deadline) || !wsPoolWait(fd, POLLOUT, deadline)) {
                return false;
            }
        } else {
            return false;
        }
    }
    return true;
}

bool wsPoolConnect(const std::string &host, const std::string &path, WarmWebSocket &entry) {
    int64_t deadline = wsPoolNow() + WS_POOL_HANDSHAKE_TIMEOUT_MS;
    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo *result = nullptr;
    if (getaddrinfo(host.c_str(), "443", &hints, &result) != 0 || result == nullptr) {
        return false;
    }
    int fd = -1;
    for (struct addrinfo *it = result; it != nullptr; it = it->ai_next) {
        fd = socket(it->ai_family, it->ai_socktype, it->ai_protocol);
        if (fd < 0) {
            continue;
        }
        int flags = fcntl(fd, F_GETFL, 0);
        fcntl(fd, F_SETFL, flags | O_NONBLOCK);
        int yes = 1;
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(yes));
        if (connect(fd, it->ai_addr, it->ai_addrlen) == 0) {
            break;
        }
        if (errno == EINPROGRESS && wsPoolWait(fd, POLLOUT, deadline)) {
            int error = 0;
            socklen_t length = sizeof(error);
            if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length) == 0 && error == 0) {
                break;
            }
        }
        close(fd);
        fd = -1;
    }
    freeaddrinfo(result);
    if (fd < 0) {
        return false;
    }

    SSL *ssl = SSL_new(getWebSocketSslCtx());
    BIO *readBio = BIO_new(BIO_s_mem());
    BIO *writeBio = BIO_new(BIO_s_mem());
    if (ssl == nullptr || readBio == nullptr || writeBio == nullptr) {
        if (ssl != nullptr) {
            SSL_free(ssl);
        }
        if (readBio != nullptr) {
            BIO_free(readBio);
        }
        if (writeBio != nullptr) {
            BIO_free(writeBio);
        }
        close(fd);
        return false;
    }
    SSL_set_bio(ssl, readBio, writeBio);
    SSL_set_tlsext_host_name(ssl, host.c_str());
    SSL_set_connect_state(ssl);

    uint8_t keyBytes[16];
    RAND_bytes(keyBytes, sizeof(keyBytes));
    std::string request = "GET " + path + " HTTP/1.1\r\nHost: " + host +
            "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nOrigin: https://web.telegram.org\r\nSec-WebSocket-Key: " +
            webSocketBase64(keyBytes, sizeof(keyBytes)) +
            "\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: binary\r\n\r\n";

    std::string response;
    bool ready = wsPoolSslHandshake(ssl, readBio, writeBio, fd, deadline) &&
            wsPoolSslWrite(ssl, readBio, writeBio, fd, request, deadline) &&
            wsPoolReadUpgrade(ssl, readBio, writeBio, fd, response, deadline);
    if (ready) {
        size_t headerEnd = response.find("\r\n\r\n");
        ready = headerEnd != std::string::npos && response.compare(0, 9, "HTTP/1.1 ") == 0 &&
                response.compare(9, 3, "101") == 0;
        if (ready) {
            entry.fd = fd;
            entry.ssl = ssl;
            entry.readBio = readBio;
            entry.writeBio = writeBio;
            entry.host = host;
            entry.path = path;
            entry.leftover = response.substr(headerEnd + 4);
            entry.created = wsPoolNow();
            return true;
        }
    }
    SSL_free(ssl);
    close(fd);
    return false;
}

}

void ConnectionSocket::webSocketPoolWarm(const std::string &host, const std::string &path) {
    if (host.empty() || path.empty()) {
        return;
    }
    int64_t generation;
    {
        std::lock_guard<std::mutex> lock(wsPoolMutex);
        if (wsPoolInFlight + (int32_t) wsPool.size() >= WS_POOL_MAX_ENTRIES) {
            return;
        }
        for (WarmWebSocket &entry : wsPool) {
            if (entry.host == host && entry.path == path) {
                return;
            }
        }
        wsPoolInFlight++;
        generation = wsPoolGeneration;
    }
    std::thread([host, path, generation] {
        WarmWebSocket entry;
        bool ready = wsPoolConnect(host, path, entry);
        std::lock_guard<std::mutex> lock(wsPoolMutex);
        wsPoolInFlight--;
        if (!ready) {
            if (LOGS_ENABLED) DEBUG_D("WSS preconnect failed %s%s", host.c_str(), path.c_str());
            return;
        }
        if (generation != wsPoolGeneration || (int32_t) wsPool.size() >= WS_POOL_MAX_ENTRIES) {
            wsPoolClose(entry);
            return;
        }
        if (LOGS_ENABLED) DEBUG_D("WSS preconnect ready %s%s", host.c_str(), path.c_str());
        wsPool.push_back(std::move(entry));
    }).detach();
}

void ConnectionSocket::webSocketPoolReset() {
    std::vector<WarmWebSocket> closeList;
    {
        std::lock_guard<std::mutex> lock(wsPoolMutex);
        wsPoolGeneration++;
        closeList.swap(wsPool);
    }
    for (WarmWebSocket &entry : closeList) {
        wsPoolClose(entry);
    }
}

ConnectionSocket::ConnectionSocket(int32_t instance) {
    instanceNum = instance;
    outgoingByteStream = new ByteStream();
    lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
    eventObject = new EventObject(this, EventObjectTypeConnection);
}

ConnectionSocket::~ConnectionSocket() {
    if (h2Ref != nullptr) {
        H2Transport::detach(h2Ref);
        h2Ref.reset();
    }
    webSocketFreeSsl();
    if (outgoingByteStream != nullptr) {
        delete outgoingByteStream;
        outgoingByteStream = nullptr;
    }
    if (eventObject != nullptr) {
        delete eventObject;
        eventObject = nullptr;
    }
    if (tempBuffer != nullptr) {
        delete tempBuffer;
        tempBuffer = nullptr;
    }
    if (tlsBuffer != nullptr) {
        tlsBuffer->reuse();
        tlsBuffer = nullptr;
    }
}

void ConnectionSocket::openConnection(std::string address, uint16_t port, std::string secret, bool ipv6, int32_t networkType) {
    currentNetworkType = networkType;
    isIpv6 = ipv6;
    currentAddress = address;
    currentPort = port;
    waitingForHostResolve = "";
    adjustWriteOpAfterResolve = false;
    tlsState = 0;
    ConnectionsManager::getInstance(instanceNum).attachConnection(this);

    memset(&socketAddress, 0, sizeof(sockaddr_in));
    memset(&socketAddress6, 0, sizeof(sockaddr_in6));

    if (h2) {
        proxyAuthState = 0;
        h2Connected = false;
        lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
        if (h2Ref != nullptr) {
            H2Transport::detach(h2Ref);
            h2Ref.reset();
        }
        h2Ref = H2Transport::createRef(this, instanceNum, ++h2Generation, h2Host, h2Path);
        if (LOGS_ENABLED) DEBUG_D("connection(%p) connecting via h2 %s%s", this, h2Host.c_str(), h2Path.c_str());
        H2Transport::attach(h2Ref);
        return;
    }

    if (webSocket) {
        if (webSocketTryAdopt()) {
            return;
        }
        proxyAuthState = 0;
        socketAddress.sin_family = AF_INET;
        socketAddress.sin_port = htons(port);
        socketAddress6.sin6_family = AF_INET6;
        socketAddress6.sin6_port = htons(port);
        if (inet_pton(AF_INET, address.c_str(), &socketAddress.sin_addr.s_addr) == 1) {
            openConnectionInternal(false);
        } else if (inet_pton(AF_INET6, address.c_str(), &socketAddress6.sin6_addr.s6_addr) == 1) {
            openConnectionInternal(true);
        } else {
#ifdef USE_DELEGATE_HOST_RESOLVE
            waitingForHostResolve = address;
            ConnectionsManager::getInstance(instanceNum).delegate->getHostByName(address, instanceNum, this);
#else
            struct hostent *he;
            if ((he = gethostbyname(address.c_str())) == nullptr) {
                if (LOGS_ENABLED) DEBUG_E("connection(%p) can't resolve web host %s", this, address.c_str());
                closeSocket(1, -1);
                return;
            }
            struct in_addr **addr_list = (struct in_addr **) he->h_addr_list;
            if (addr_list[0] != nullptr) {
                socketAddress.sin_addr.s_addr = addr_list[0]->s_addr;
                openConnectionInternal(false);
            } else {
                if (LOGS_ENABLED) DEBUG_E("connection(%p) can't resolve web host %s", this, address.c_str());
                closeSocket(1, -1);
            }
#endif
        }
        return;
    }

    std::string *proxyAddress = &overrideProxyAddress;
    std::string *proxySecret = &overrideProxySecret;
    uint16_t proxyPort = overrideProxyPort;
    if (proxyAddress->empty()) {
        proxyAddress = &ConnectionsManager::getInstance(instanceNum).proxyAddress;
        proxyPort = ConnectionsManager::getInstance(instanceNum).proxyPort;
        proxySecret = &ConnectionsManager::getInstance(instanceNum).proxySecret;
    }

    if (!proxyAddress->empty()) {
        if (LOGS_ENABLED) DEBUG_D("connection(%p) connecting via proxy %s:%d secret[%d]", this, proxyAddress->c_str(), proxyPort, (int) proxySecret->size());
        uint32_t tempBuffLength;
        if (proxySecret->empty()) {
            proxyAuthState = 1;
            tempBuffLength = 1024;
        } else if (proxySecret->size() > 17 && (*proxySecret)[0] == '\xee') {
            proxyAuthState = 10;
            currentSecret = proxySecret->substr(1, 16);
            currentSecretDomain = proxySecret->substr(17);
            tempBuffLength = 65 * 1024;
        } else {
            proxyAuthState = 0;
            tempBuffLength = 0;
        }
        if (tempBuffLength > 0) {
            if (tempBuffer == nullptr || tempBuffer->length < tempBuffLength) {
                if (tempBuffer != nullptr) {
                    delete tempBuffer;
                }
                tempBuffer = new ByteArray(tempBuffLength);
            }
        }
        socketAddress.sin_family = AF_INET;
        socketAddress.sin_port = htons(proxyPort);
        socketAddress6.sin6_family = AF_INET6;
        socketAddress6.sin6_port = htons(proxyPort);
        bool continueCheckAddress;
        if (inet_pton(AF_INET, proxyAddress->c_str(), &socketAddress.sin_addr.s_addr) != 1) {
            continueCheckAddress = true;
            if (LOGS_ENABLED) DEBUG_D("connection(%p) not ipv4 address %s", this, proxyAddress->c_str());
        } else {
            ipv6 = false;
            continueCheckAddress = false;
        }
        if (continueCheckAddress) {
            if (inet_pton(AF_INET6, proxyAddress->c_str(), &socketAddress6.sin6_addr.s6_addr) != 1) {
                continueCheckAddress = true;
                if (LOGS_ENABLED) DEBUG_D("connection(%p) not ipv6 address %s", this, proxyAddress->c_str());
            } else {
                ipv6 = true;
                continueCheckAddress = false;
            }
            if (continueCheckAddress) {
#ifdef USE_DELEGATE_HOST_RESOLVE
                waitingForHostResolve = *proxyAddress;
                ConnectionsManager::getInstance(instanceNum).delegate->getHostByName(*proxyAddress, instanceNum, this);
                return;
#else
                struct hostent *he;
                if ((he = gethostbyname(proxyAddress->c_str())) == nullptr) {
                    if (LOGS_ENABLED) DEBUG_E("connection(%p) can't resolve host %s address", this, proxyAddress->c_str());
                    closeSocket(1, -1);
                    return;
                }
                struct in_addr **addr_list = (struct in_addr **) he->h_addr_list;
                if (addr_list[0] != nullptr) {
                    socketAddress.sin_addr.s_addr = addr_list[0]->s_addr;
                    if (LOGS_ENABLED) DEBUG_D("connection(%p) resolved host %s address %x", this, proxyAddress->c_str(), addr_list[0]->s_addr);
                    ipv6 = false;
                } else {
                    if (LOGS_ENABLED) DEBUG_E("connection(%p) can't resolve host %s address", this, proxyAddress->c_str());
                    closeSocket(1, -1);
                    return;
                }
#endif
            }
        }
    } else {
        proxyAuthState = 0;
        if (ipv6) {
            socketAddress6.sin6_family = AF_INET6;
            socketAddress6.sin6_port = htons(port);
            if (inet_pton(AF_INET6, address.c_str(), &socketAddress6.sin6_addr.s6_addr) != 1) {
                if (LOGS_ENABLED) DEBUG_E("connection(%p) bad ipv6 %s", this, address.c_str());
                closeSocket(1, -1);
                return;
            }
        } else {
            socketAddress.sin_family = AF_INET;
            socketAddress.sin_port = htons(port);
            if (inet_pton(AF_INET, address.c_str(), &socketAddress.sin_addr.s_addr) != 1) {
                if (LOGS_ENABLED) DEBUG_E("connection(%p) bad ipv4 %s", this, address.c_str());
                closeSocket(1, -1);
                return;
            }
        }
        uint32_t tempBuffLength;
        if (secret.size() > 17 && secret[0] == '\xee') {
            proxyAuthState = 10;
            currentSecret = secret.substr(1, 16);
            currentSecretDomain = secret.substr(17);
            tempBuffLength = 65 * 1024;
        } else {
            proxyAuthState = 0;
            tempBuffLength = 0;
        }
        if (tempBuffLength > 0) {
            if (tempBuffer == nullptr || tempBuffer->length < tempBuffLength) {
                if (tempBuffer != nullptr) {
                    delete tempBuffer;
                }
                tempBuffer = new ByteArray(tempBuffLength);
            }
        }
    }

    openConnectionInternal(ipv6);
}

void ConnectionSocket::openConnectionInternal(bool ipv6) {
    if ((socketFd = socket(ipv6 ? AF_INET6 : AF_INET, SOCK_STREAM, 0)) < 0) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) can't create socket", this);
        closeSocket(1, -1);
        return;
    }
    int epolFd = ConnectionsManager::getInstance(instanceNum).epolFd;
    int yes = 1;
    if (setsockopt(socketFd, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(int))) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) set TCP_NODELAY failed", this);
    }
#ifdef DEBUG_VERSION
    int size = 4 * 1024 * 1024;
    if (setsockopt(socketFd, SOL_SOCKET, SO_SNDBUF, &size, sizeof(int))) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) set SO_SNDBUF failed", this);
    }
    if (setsockopt(socketFd, SOL_SOCKET, SO_RCVBUF, &size, sizeof(int))) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) set SO_RCVBUF failed", this);
    }
#endif

    if (fcntl(socketFd, F_SETFL, O_NONBLOCK) == -1) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) set O_NONBLOCK failed", this);
        closeSocket(1, -1);
        return;
    }

    if (webSocket) {
        if (!webSocketInitSsl()) {
            if (LOGS_ENABLED) DEBUG_E("connection(%p) WSS ssl init failed", this);
            closeSocket(1, -1);
            return;
        }
        webSocketState = WebSocketStateTls;
    }

    if (connect(socketFd, (ipv6 ? (sockaddr *) &socketAddress6 : (sockaddr *) &socketAddress), (socklen_t) (ipv6 ? sizeof(sockaddr_in6) : sizeof(sockaddr_in))) == -1 && errno != EINPROGRESS) {
        closeSocket(1, -1);
    } else {
        eventMask.events = EPOLLOUT | EPOLLIN | EPOLLRDHUP | EPOLLERR | EPOLLET;
        eventMask.data.ptr = eventObject;
        if (epoll_ctl(epolFd, EPOLL_CTL_ADD, socketFd, &eventMask) != 0) {
            if (LOGS_ENABLED) DEBUG_E("connection(%p) epoll_ctl, adding socket failed", this);
            closeSocket(1, -1);
        }
    }
    if (adjustWriteOpAfterResolve) {
        adjustWriteOp();
    }
}

int32_t ConnectionSocket::checkSocketError(int32_t *error) {
    if (socketFd < 0) {
        return true;
    }
    int ret;
    int code;
    socklen_t len = sizeof(int);
    ret = getsockopt(socketFd, SOL_SOCKET, SO_ERROR, &code, &len);
    if (ret != 0 || code != 0) {
        if (LOGS_ENABLED) DEBUG_E("socket error 0x%x code 0x%x", ret, code);
    }
    *error = code;
    return (ret || code) != 0;
}

void ConnectionSocket::closeSocket(int32_t reason, int32_t error) {
    socketGeneration++;
    if (h2Ref != nullptr) {
        H2Transport::detach(h2Ref);
        h2Ref.reset();
    }
    h2Connected = false;
    lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
    ConnectionsManager::getInstance(instanceNum).detachConnection(this);
    if (socketFd >= 0) {
        epoll_ctl(ConnectionsManager::getInstance(instanceNum).epolFd, EPOLL_CTL_DEL, socketFd, nullptr);
        if (close(socketFd) != 0) {
            if (LOGS_ENABLED) DEBUG_E("connection(%p) unable to close socket", this);
        }
        socketFd = -1;
    }
    waitingForHostResolve = "";
    adjustWriteOpAfterResolve = false;
    proxyAuthState = 0;
    tlsState = 0;
    onConnectedSent = false;
    outgoingByteStream->clean();
    webSocketFreeSsl();
    wsInBuffer.clear();
    wsOutQueue.clear();
    webSocketResetFrameState();
    if (tlsBuffer != nullptr) {
        tlsBuffer->reuse();
        tlsBuffer = nullptr;
    }
    onDisconnected(reason, error);
}

void ConnectionSocket::onEvent(uint32_t events) {
    if (events & EPOLLIN) {
        int32_t error;
        if (checkSocketError(&error) != 0) {
            closeSocket(1, error);
            return;
        } else {
            ssize_t readCount;
            NativeByteBuffer *buffer = ConnectionsManager::getInstance(instanceNum).networkBuffer;
            while (true) {
                buffer->rewind();
                readCount = recv(socketFd, buffer->bytes(), READ_BUFFER_SIZE, 0);
                int err = errno;
//                if (LOGS_ENABLED) DEBUG_D("connection(%p) recv resulted with %d, errno=%d", this, readCount, err);
                if (readCount < 0) {
                    if (err == EAGAIN) {
                        break;
                    }
                    closeSocket(1, -1);
                    if (LOGS_ENABLED) DEBUG_E("connection(%p) recv failed", this);
                    return;
                }
                if (readCount > 0) {
                    buffer->limit((uint32_t) readCount);
                    lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
                    if (webSocket) {
                        int32_t gen = socketGeneration;
                        if (webSocketOnNetworkData(buffer->bytes(), (size_t) readCount) < 0) {
                            closeSocket(1, -1);
                            if (LOGS_ENABLED) DEBUG_E("connection(%p) WSS network data processing failed", this);
                            return;
                        }
                        if (gen != socketGeneration) {
                            return;
                        }
                    } else if (proxyAuthState == 11) {
                        if (LOGS_ENABLED) DEBUG_D("connection(%p) TLS received %d", this, (int) readCount);
                        size_t newBytesRead = bytesRead + readCount;
                        if (newBytesRead > 64 * 1024) {
                            closeSocket(1, -1);
                            if (LOGS_ENABLED) DEBUG_E("connection(%p) TLS client hello too much data", this);
                            return;
                        }
                        if (newBytesRead >= 16) {
                            std::memcpy(tempBuffer->bytes + bytesRead, buffer->bytes(), (size_t) readCount);

                            static std::string hello1 = std::string("\x16\x03\x03", 3);
                            if (std::memcmp(hello1.data(), tempBuffer->bytes, hello1.size()) != 0) {
                                closeSocket(1, -1);
                                if (LOGS_ENABLED) DEBUG_E("connection(%p) TLS hello1 mismatch", this);
                                return;
                            }
                            size_t len1 = (tempBuffer->bytes[3] << 8) + tempBuffer->bytes[4];
                            if (len1 > 64 * 1024 - 5) {
                                closeSocket(1, -1);
                                if (LOGS_ENABLED) DEBUG_E("connection(%p) TLS len1 invalid", this);
                                return;
                            } else if (newBytesRead < len1 + 5) {
                                if (LOGS_ENABLED) DEBUG_D("connection(%p) TLS client hello wait for more data", this);
                                bytesRead = newBytesRead;
                                return;
                            }

                            static std::string hello2 = std::string("\x14\x03\x03\x00\x01\x01\x17\x03\x03", 9);
                            if (std::memcmp(hello2.data(), tempBuffer->bytes + 5 + len1, hello2.size()) != 0) {
                                closeSocket(1, -1);
                                if (LOGS_ENABLED) DEBUG_E("connection(%p) TLS hello2 mismatch", this);
                                return;
                            }
                            size_t len2 = (tempBuffer->bytes[5 + 9 + len1] << 8) + tempBuffer->bytes[5 + 9 + len1 + 1];
                            if (len2 > 64 * 1024 - len1 - 5 - 11) {
                                closeSocket(1, -1);
                                if (LOGS_ENABLED) DEBUG_E("connection(%p) TLS len2 invalid", this);
                                return;
                            } else if (newBytesRead < len2 + len1 + 5 + 11) {
                                if (LOGS_ENABLED) DEBUG_D("connection(%p) TLS client hello wait for more data", this);
                                bytesRead = newBytesRead;
                                return;
                            }
                            std::memcpy(tempBuffer->bytes + 64 * 1024 + 32, tempBuffer->bytes + 11, 32);
                            std::memset(tempBuffer->bytes + 11, 0, 32);

                            uint8_t *temp = new uint8_t[32 + newBytesRead];
                            memcpy(temp, tempBuffer->bytes + 64 * 1024, 32);
                            memcpy(temp + 32, tempBuffer->bytes, newBytesRead);
                            uint32_t outLength;
                            HMAC(EVP_sha256(), currentSecret.data(), currentSecret.size(), temp, 32 + newBytesRead, tempBuffer->bytes + 64 * 1024, &outLength);
                            delete[] temp;
                            if (std::memcmp(tempBuffer->bytes + 64 * 1024, tempBuffer->bytes + 64 * 1024 + 32, 32) != 0) {
                                tlsHashMismatch = true;
                                closeSocket(1, -1);
                                if (LOGS_ENABLED) DEBUG_E("connection(%p) TLS hash mismatch", this);
                                return;
                            }
                            if (LOGS_ENABLED) DEBUG_D("connection(%p) TLS hello complete", this);
                            tlsState = 1;
                            proxyAuthState = 0;
                            bytesRead = 0;
                            adjustWriteOp();
                        } else {
                            std::memcpy(tempBuffer->bytes + bytesRead, buffer->bytes(), (size_t) readCount);
                            bytesRead = newBytesRead;
                        }
                    } else if (proxyAuthState == 2) {
                        if (readCount == 2) {
                            uint8_t auth_method = buffer->bytes()[1];
                            if (auth_method == 0xff) {
                                closeSocket(1, -1);
                                if (LOGS_ENABLED) DEBUG_E("connection(%p) unsupported proxy auth method", this);
                            } else if (auth_method == 0x02) {
                                if (LOGS_ENABLED) DEBUG_D("connection(%p) proxy auth required", this);
                                proxyAuthState = 3;
                            } else if (auth_method == 0x00) {
                                proxyAuthState = 5;
                            }
                            adjustWriteOp();
                        } else {
                            closeSocket(1, -1);
                            if (LOGS_ENABLED) DEBUG_E("connection(%p) invalid proxy response on state 2", this);
                        }
                    } else if (proxyAuthState == 4) {
                        if (readCount == 2) {
                            uint8_t auth_method = buffer->bytes()[1];
                            if (auth_method != 0x00) {
                                closeSocket(1, -1);
                                if (LOGS_ENABLED) DEBUG_E("connection(%p) auth invalid", this);
                            } else {
                                proxyAuthState = 5;
                            }
                            adjustWriteOp();
                        } else {
                            closeSocket(1, -1);
                            if (LOGS_ENABLED) DEBUG_E("connection(%p) invalid proxy response on state 4", this);
                        }
                    } else if (proxyAuthState == 6) {
                        if (readCount > 2) {
                            uint8_t status = buffer->bytes()[1];
                            if (status == 0x00) {
                                if (LOGS_ENABLED) DEBUG_D("connection(%p) connected via proxy", this);
                                proxyAuthState = 0;
                                adjustWriteOp();
                            } else {
                                closeSocket(1, -1);
                                if (LOGS_ENABLED) DEBUG_E("connection(%p) invalid proxy status on state 6, 0x%x", this, status);
                            }
                        } else {
                            closeSocket(1, -1);
                            if (LOGS_ENABLED) DEBUG_E("connection(%p) invalid proxy response on state 6", this);
                        }
                    } else if (proxyAuthState == 0) {
                        if (ConnectionsManager::getInstance(instanceNum).delegate != nullptr) {
                            ConnectionsManager::getInstance(instanceNum).delegate->onBytesReceived((int32_t) readCount, currentNetworkType, instanceNum);
                        }
                        if (tlsState != 0) {
                            while (buffer->hasRemaining()) {
                                size_t newBytesRead = buffer->remaining();
                                if (tlsBuffer != nullptr) {
                                    newBytesRead += tlsBuffer->position();
                                    if (tlsBufferSized) {
                                        newBytesRead += 5;
                                    }
                                }
                                if (newBytesRead >= 5) {
                                    if (tlsBuffer == nullptr || !tlsBufferSized) {
                                        uint32_t pos = buffer->position();

                                        uint8_t offset = 0;
                                        uint8_t header[5];
                                        if (tlsBuffer != nullptr) {
                                            offset = (uint8_t) tlsBuffer->position();
                                            memcpy(header, tlsBuffer->bytes(), offset);
                                            tlsBuffer->reuse();
                                            tlsBuffer = nullptr;
                                        }
                                        memcpy(header + offset, buffer->bytes() + pos, (uint8_t) (5 - offset));

                                        static std::string header1 = std::string("\x17\x03\x03", 3);
                                        if (std::memcmp(header1.data(), header, header1.size()) != 0) {
                                            closeSocket(1, -1);
                                            if (LOGS_ENABLED) DEBUG_E("connection(%p) TLS response header1 mismatch", this);
                                            return;
                                        }
                                        uint32_t len1 = (header[3] << 8) + header[4];
                                        if (len1 > 64 * 1024) {
                                            closeSocket(1, -1);
                                            if (LOGS_ENABLED) DEBUG_E("connection(%p) TLS response len1 invalid", this);
                                            return;
                                        } else {
                                            tlsBuffer = BuffersStorage::getInstance().getFreeBuffer(len1);
                                            tlsBufferSized = true;
                                            buffer->position(pos + (5 - offset));
                                        }
                                    } else {
                                        if (LOGS_ENABLED) DEBUG_D("connection(%p) TLS response new data %d", this, buffer->remaining());
                                    }
                                    buffer->limit(std::min(buffer->position() + tlsBuffer->remaining(), buffer->limit()));
                                    tlsBuffer->writeBytes(buffer);
                                    buffer->limit((uint32_t) readCount);
                                    if (tlsBuffer->remaining() == 0) {
                                        tlsBuffer->rewind();
                                        onReceivedData(tlsBuffer);
                                        if (tlsBuffer == nullptr) {
                                            return;
                                        }
                                        tlsBuffer->reuse();
                                        tlsBuffer = nullptr;
                                    } else {
                                        if (LOGS_ENABLED) DEBUG_D("connection(%p) TLS response wait for more data, total size %d, left %d", this, tlsBuffer->limit(), tlsBuffer->remaining());
                                    }
                                } else {
                                    if (tlsBuffer == nullptr) {
                                        tlsBuffer = BuffersStorage::getInstance().getFreeBuffer(4);
                                        tlsBufferSized = false;
                                    }
                                    tlsBuffer->writeBytes(buffer);
                                    if (LOGS_ENABLED) DEBUG_D("connection(%p) TLS response wait for more data, not enough bytes for header, total = %d", this, (int) tlsBuffer->position());
                                }
                            }
                        } else {
                            onReceivedData(buffer);
                        }
                    }
                } else if (readCount == 0) {
                    break;
                }
//                if (readCount != READ_BUFFER_SIZE) {
//                    break;
//                }
            }
        }
    }
    if (events & EPOLLOUT) {
        int32_t error;
        if (checkSocketError(&error) != 0) {
            closeSocket(1, error);
            return;
        } else {
            if (webSocket) {
                if (webSocketState == WebSocketStateReady) {
                    if (webSocketSendOutgoing() < 0) {
                        closeSocket(1, -1);
                        return;
                    }
                    adjustWriteOp();
                } else {
                    if (webSocketDriveHandshake() < 0) {
                        closeSocket(1, -1);
                        return;
                    }
                }
            } else if (proxyAuthState != 0) {
                if (proxyAuthState >= 10) {
                    if (proxyAuthState == 10) {
                        lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
                        tlsHashMismatch = false;
                        proxyAuthState = 11;
                        TLSHello hello = TLSHello::getDefault();
                        hello.setDomain(currentSecretDomain);
                        uint32_t size = hello.writeToBuffer(tempBuffer->bytes);
                        uint32_t outLength;
                        HMAC(EVP_sha256(), currentSecret.data(), currentSecret.size(), tempBuffer->bytes, size, tempBuffer->bytes + 64 * 1024, &outLength);

                        int32_t currentTime = ConnectionsManager::getInstance(instanceNum).getCurrentTime();
                        int32_t old = ((int32_t *) (tempBuffer->bytes + 64 * 1024 + 28))[0];
                        ((int32_t *) (tempBuffer->bytes + 64 * 1024 + 28))[0] = old ^ currentTime;

                        memcpy(tempBuffer->bytes + 11, tempBuffer->bytes + 64 * 1024, 32);
                        bytesRead = 0;

                        if (send(socketFd, tempBuffer->bytes, size, 0) < 0) {
                            if (LOGS_ENABLED) DEBUG_E("connection(%p) send failed", this);
                            closeSocket(1, -1);
                            return;
                        }
                        adjustWriteOp();
                    }
                } else {
                    if (proxyAuthState == 1) {
                        lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
                        proxyAuthState = 2;
                        tempBuffer->bytes[0] = 0x05;
                        tempBuffer->bytes[1] = 0x02;
                        tempBuffer->bytes[2] = 0x00;
                        tempBuffer->bytes[3] = 0x02;
                        if (send(socketFd, tempBuffer->bytes, 4, 0) < 0) {
                            if (LOGS_ENABLED) DEBUG_E("connection(%p) send failed", this);
                            closeSocket(1, -1);
                            return;
                        }
                        adjustWriteOp();
                    } else if (proxyAuthState == 3) {
                        tempBuffer->bytes[0] = 0x01;
                        std::string *proxyUser;
                        std::string *proxyPassword;
                        if (!overrideProxyAddress.empty()) {
                            proxyUser = &overrideProxyUser;
                            proxyPassword = &overrideProxyPassword;
                        } else {
                            proxyUser = &ConnectionsManager::getInstance(instanceNum).proxyUser;
                            proxyPassword = &ConnectionsManager::getInstance(instanceNum).proxyPassword;
                        }
                        uint8_t len1 = (uint8_t) proxyUser->length();
                        uint8_t len2 = (uint8_t) proxyPassword->length();
                        tempBuffer->bytes[1] = len1;
                        memcpy(tempBuffer->bytes + 2, proxyUser->c_str(), len1);
                        tempBuffer->bytes[2 + len1] = len2;
                        memcpy(tempBuffer->bytes + 3 + len1, proxyPassword->c_str(), len2);
                        proxyAuthState = 4;
                        if (send(socketFd, tempBuffer->bytes, 3 + len1 + len2, 0) < 0) {
                            if (LOGS_ENABLED) DEBUG_E("connection(%p) send failed", this);
                            closeSocket(1, -1);
                            return;
                        }
                        adjustWriteOp();
                    } else if (proxyAuthState == 5) {
                        tempBuffer->bytes[0] = 0x05;
                        tempBuffer->bytes[1] = 0x01;
                        tempBuffer->bytes[2] = 0x00;
                        tempBuffer->bytes[3] = (uint8_t) (isIpv6 ? 0x04 : 0x01);
                        uint16_t networkPort = ntohs(currentPort);
                        inet_pton(isIpv6 ? AF_INET6 : AF_INET, currentAddress.c_str(), tempBuffer->bytes + 4);
                        memcpy(tempBuffer->bytes + 4 + (isIpv6 ? 16 : 4), &networkPort, sizeof(uint16_t));
                        proxyAuthState = 6;
                        if (send(socketFd, tempBuffer->bytes, 4 + (isIpv6 ? 16 : 4) + 2, 0) < 0) {
                            if (LOGS_ENABLED) DEBUG_E("connection(%p) send failed", this);
                            closeSocket(1, -1);
                            return;
                        }
                        adjustWriteOp();
                    }
                }
            } else {
                if (!onConnectedSent) {
                    lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
                    if (LOGS_ENABLED) DEBUG_D("connection(%p) reset last event time, on connect", this);
                    onConnected();
                    onConnectedSent = true;
                }
                NativeByteBuffer *buffer = ConnectionsManager::getInstance(instanceNum).networkBuffer;
                buffer->clear();
                outgoingByteStream->get(buffer);
                buffer->flip();

                uint32_t remaining = buffer->remaining();
                if (remaining) {
                    ssize_t sentLength;
                    if (tlsState != 0) {
                        if (remaining > 2878) {
                            remaining = 2878;
                        }
                        size_t headersSize = 0;
                        if (tlsState == 1) {
                            static std::string header1 = std::string("\x14\x03\x03\x00\x01\x01", 6);
                            std::memcpy(tempBuffer->bytes, header1.data(), header1.size());
                            headersSize += header1.size();
                            tlsState = 2;
                        }
                        static std::string header2 = std::string("\x17\x03\x03", 3);
                        std::memcpy(tempBuffer->bytes + headersSize, header2.data(), header2.size());
                        headersSize += header2.size();

                        tempBuffer->bytes[headersSize] = static_cast<uint8_t>((remaining >> 8) & 0xff);
                        tempBuffer->bytes[headersSize + 1] = static_cast<uint8_t>(remaining & 0xff);
                        headersSize += 2;

                        std::memcpy(tempBuffer->bytes + headersSize, buffer->bytes(), remaining);

                        if ((sentLength = send(socketFd, tempBuffer->bytes, headersSize + remaining, 0)) < headersSize) {
                            if (LOGS_ENABLED) DEBUG_E("connection(%p) send failed", this);
                            closeSocket(1, -1);
                            return;
                        } else {
                            if (ConnectionsManager::getInstance(instanceNum).delegate != nullptr) {
                                ConnectionsManager::getInstance(instanceNum).delegate->onBytesSent((int32_t) sentLength, currentNetworkType, instanceNum);
                            }
                            outgoingByteStream->discard((uint32_t) (sentLength - headersSize));
                            adjustWriteOp();
                        }
                    } else {
                        if ((sentLength = send(socketFd, buffer->bytes(), remaining, 0)) < 0) {
                            if (LOGS_ENABLED) DEBUG_D("connection(%p) send failed", this);
                            closeSocket(1, -1);
                            return;
                        } else {
                            if (ConnectionsManager::getInstance(instanceNum).delegate != nullptr) {
                                ConnectionsManager::getInstance(instanceNum).delegate->onBytesSent((int32_t) sentLength, currentNetworkType, instanceNum);
                            }
                            outgoingByteStream->discard((uint32_t) sentLength);
                            adjustWriteOp();
                        }
                    }
                }
            }
        }
    }
    if (events & EPOLLHUP) {
        if (LOGS_ENABLED) DEBUG_E("socket event has EPOLLHUP");
        closeSocket(1, -1);
        return;
    } else if (events & EPOLLRDHUP) {
        if (LOGS_ENABLED) DEBUG_E("socket event has EPOLLRDHUP");
        closeSocket(1, -1);
        return;
    }
    if (events & EPOLLERR) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) epoll error", this);
        return;
    }
}

void ConnectionSocket::writeBuffer(uint8_t *data, uint32_t size) {
    NativeByteBuffer *buffer = BuffersStorage::getInstance().getFreeBuffer(size);
    buffer->writeBytes(data, size);
    outgoingByteStream->append(buffer);
    adjustWriteOp();
}

void ConnectionSocket::writeBuffer(NativeByteBuffer *buffer) {
    outgoingByteStream->append(buffer);
    adjustWriteOp();
}

void ConnectionSocket::adjustWriteOp() {
    if (h2) {
        return;
    }
    if (!waitingForHostResolve.empty()) {
        adjustWriteOpAfterResolve = true;
        return;
    }
    eventMask.events = EPOLLIN | EPOLLRDHUP | EPOLLERR | EPOLLET;
    if (webSocket) {
        if (webSocketState != WebSocketStateReady || !wsOutQueue.empty() || tlsPendingSent < tlsPendingWrite.size()) {
            eventMask.events |= EPOLLOUT;
        }
    } else if (proxyAuthState == 0 && (outgoingByteStream->hasData() || !onConnectedSent) || proxyAuthState == 1 || proxyAuthState == 3 || proxyAuthState == 5 || proxyAuthState == 10) {
        eventMask.events |= EPOLLOUT;
    }
    eventMask.data.ptr = eventObject;
    if (epoll_ctl(ConnectionsManager::getInstance(instanceNum).epolFd, EPOLL_CTL_MOD, socketFd, &eventMask) != 0) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) epoll_ctl, modify socket failed", this);
        closeSocket(1, -1);
    }
}

void ConnectionSocket::setTimeout(time_t time) {
    timeout = time;
    lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
    if (LOGS_ENABLED) DEBUG_D("connection(%p) set current timeout = %lld", this, (long long) timeout);
}

time_t ConnectionSocket::getTimeout() {
    return timeout;
}

bool ConnectionSocket::checkTimeout(int64_t now) {
    if (h2) {
        // The endpoint thread owns setup, request and reconnect timeouts.
        return false;
    }
    if (webSocket && webSocketState != WebSocketStateReady && (now - lastEventTime) > 6000) {
        if (LOGS_ENABLED) DEBUG_D("connection(%p) WSS handshake timeout", this);
        closeSocket(2, 0);
        return true;
    }
    if (timeout != 0 && (now - lastEventTime) > (int64_t) timeout * 1000) {
        if (!onConnectedSent || hasPendingRequests()) {
            closeSocket(2, 0);
            return true;
        } else {
            lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
            if (LOGS_ENABLED) DEBUG_D("connection(%p) reset last event time, no requests", this);
        }
    }
    return false;
}

bool ConnectionSocket::hasTlsHashMismatch() {
    return tlsHashMismatch;
}

void ConnectionSocket::resetLastEventTime() {
    lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
}

bool ConnectionSocket::isDisconnected() {
    return socketFd < 0 && waitingForHostResolve.empty() && h2Ref == nullptr;
}

void ConnectionSocket::dropConnection() {
    closeSocket(0, 0);
}

void ConnectionSocket::setOverrideProxy(std::string address, uint16_t port, std::string username, std::string password, std::string secret) {
    overrideProxyAddress = address;
    overrideProxyPort = port;
    overrideProxyUser = username;
    overrideProxyPassword = password;
    overrideProxySecret = secret;
}

void ConnectionSocket::setWebSocket(bool enabled, std::string host, std::string path, std::string sni) {
    webSocket = enabled;
    webSocketHost = std::move(host);
    webSocketPath = std::move(path);
    webSocketSni = std::move(sni);
}

void ConnectionSocket::setH2(bool enabled, std::string host, std::string path) {
    h2 = enabled;
    h2Host = std::move(host);
    h2Path = std::move(path);
    if (!enabled) {
        h2Connected = false;
    }
}

void ConnectionSocket::queueH2Message(const uint8_t *data, size_t length) {
    if (h2Ref == nullptr || data == nullptr || length == 0) {
        return;
    }
    H2Transport::submit(h2Ref, h2Generation, data, length);
}

bool ConnectionSocket::isH2Registered(uint32_t generation) const {
    return h2 && h2Generation == generation;
}

void ConnectionSocket::onH2EndpointConnected() {
    if (!h2 || h2Connected) {
        return;
    }
    h2Connected = true;
    lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
    if (LOGS_ENABLED) DEBUG_D("connection(%p) h2 ready host=%s%s", this, h2Host.c_str(), h2Path.c_str());
    if (!onConnectedSent) {
        onConnected();
        onConnectedSent = true;
    }
}

void ConnectionSocket::onH2EndpointFailure() {
    if (!h2) {
        return;
    }
    if (LOGS_ENABLED) DEBUG_D("connection(%p) h2 endpoint failure", this);
    closeSocket(1, 0);
}

void ConnectionSocket::onH2Response(const std::string &body) {
    if (!h2 || !h2Connected || body.empty()) {
        return;
    }
    lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
    onH2Packet((const uint8_t *) body.data(), body.size());
}

void ConnectionSocket::queueWebSocketMessage(const uint8_t *data, size_t length, const uint8_t *data2, size_t length2, const uint8_t *data3, size_t length3) {
    std::string message;
    message.reserve(webSocketHeaderReserve + length + length2 + length3);
    message.append(webSocketHeaderReserve, '\0');
    message.append((const char *) data, length);
    if (data2 != nullptr) {
        message.append((const char *) data2, length2);
    }
    if (data3 != nullptr) {
        message.append((const char *) data3, length3);
    }
    wsOutQueue.push_back(std::move(message));
    adjustWriteOp();
}

bool ConnectionSocket::webSocketInitSsl() {
    webSocketFreeSsl();
    ssl = SSL_new(getWebSocketSslCtx());
    if (ssl == nullptr) {
        return false;
    }
    sslReadBio = BIO_new(BIO_s_mem());
    sslWriteBio = BIO_new(BIO_s_mem());
    if (sslReadBio == nullptr || sslWriteBio == nullptr) {
        return false;
    }
    SSL_set_bio(ssl, sslReadBio, sslWriteBio);
    SSL_set_tlsext_host_name(ssl, (webSocketSni.empty() ? webSocketHost : webSocketSni).c_str());
    SSL_set_connect_state(ssl);
    return true;
}

void ConnectionSocket::webSocketFreeSsl() {
    if (ssl != nullptr) {
        SSL_free(ssl);
        ssl = nullptr;
    }
    sslReadBio = nullptr;
    sslWriteBio = nullptr;
    tlsPendingWrite.clear();
    tlsPendingSent = 0;
    webSocketState = WebSocketStateNone;
}

int32_t ConnectionSocket::webSocketFlushCiphertext() {
    size_t pending;
    while ((pending = BIO_ctrl_pending(sslWriteBio)) > 0) {
        size_t oldSize = tlsPendingWrite.size();
        tlsPendingWrite.resize(oldSize + pending);
        int32_t r = BIO_read(sslWriteBio, &tlsPendingWrite[oldSize], (int32_t) pending);
        if (r <= 0) {
            tlsPendingWrite.resize(oldSize);
            break;
        }
        if ((size_t) r < pending) {
            tlsPendingWrite.resize(oldSize + r);
        }
    }
    while (tlsPendingSent < tlsPendingWrite.size()) {
        ssize_t sent = send(socketFd, tlsPendingWrite.data() + tlsPendingSent, tlsPendingWrite.size() - tlsPendingSent, 0);
        if (sent < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                break;
            }
            return -1;
        }
        if (sent == 0) {
            break;
        }
        if (ConnectionsManager::getInstance(instanceNum).delegate != nullptr) {
            ConnectionsManager::getInstance(instanceNum).delegate->onBytesSent((int32_t) sent, currentNetworkType, instanceNum);
        }
        tlsPendingSent += sent;
    }
    if (tlsPendingSent >= tlsPendingWrite.size()) {
        tlsPendingWrite.clear();
        tlsPendingSent = 0;
    } else if (tlsPendingSent >= 64 * 1024) {
        tlsPendingWrite.erase(0, tlsPendingSent);
        tlsPendingSent = 0;
    }
    return 0;
}

int32_t ConnectionSocket::webSocketDriveHandshake() {
    if (webSocketState != WebSocketStateTls) {
        return webSocketFlushCiphertext();
    }
    int32_t r = SSL_do_handshake(ssl);
    if (r == 1) {
        if (webSocketSendHttpUpgrade() < 0) {
            return -1;
        }
    } else {
        int32_t err = SSL_get_error(ssl, r);
        if (err != SSL_ERROR_WANT_READ && err != SSL_ERROR_WANT_WRITE) {
            char errbuf[256];
            ERR_error_string_n(ERR_get_error(), errbuf, sizeof(errbuf));
            if (LOGS_ENABLED) DEBUG_E("connection(%p) WSS TLS handshake failed, ssl error %d (%s)", this, err, errbuf);
            return -1;
        }
    }
    return webSocketFlushCiphertext();
}

int32_t ConnectionSocket::webSocketSendHttpUpgrade() {
    uint8_t keyBytes[16];
    RAND_bytes(keyBytes, sizeof(keyBytes));
    std::string key = webSocketBase64(keyBytes, sizeof(keyBytes));
    std::string request;
    request.reserve(256);
    request += "GET ";
    request += webSocketPath;
    request += " HTTP/1.1\r\nHost: ";
    request += webSocketHost;
    request += "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nOrigin: https://web.telegram.org\r\nSec-WebSocket-Key: ";
    request += key;
    request += "\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: binary\r\n\r\n";
    int32_t w = SSL_write(ssl, request.data(), (int32_t) request.size());
    if (w <= 0) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) WSS failed to write http upgrade", this);
        return -1;
    }
    webSocketState = WebSocketStateHttp;
    if (LOGS_ENABLED) DEBUG_D("connection(%p) WSS sent http upgrade to %s%s", this, webSocketHost.c_str(), webSocketPath.c_str());
    return 0;
}

int32_t ConnectionSocket::webSocketOnNetworkData(uint8_t *data, size_t length) {
    if (length > 0 && BIO_write(sslReadBio, data, (int32_t) length) <= 0) {
        return -1;
    }
    if (webSocketState == WebSocketStateTls) {
        if (webSocketDriveHandshake() < 0) {
            return -1;
        }
        if (webSocketState == WebSocketStateTls) {
            return 0;
        }
    }
    uint8_t plain[16384];
    while (true) {
        int32_t n = SSL_read(ssl, plain, sizeof(plain));
        if (n <= 0) {
            int32_t err = SSL_get_error(ssl, n);
            if (err == SSL_ERROR_WANT_READ || err == SSL_ERROR_WANT_WRITE) {
                break;
            }
            if (LOGS_ENABLED) DEBUG_E("connection(%p) WSS SSL_read error %d", this, err);
            return -1;
        }
        if (ConnectionsManager::getInstance(instanceNum).delegate != nullptr) {
            ConnectionsManager::getInstance(instanceNum).delegate->onBytesReceived(n, currentNetworkType, instanceNum);
        }
        int32_t gen = socketGeneration;
        if (webSocketState == WebSocketStateHttp) {
            wsInBuffer.append((const char *) plain, (size_t) n);
            int32_t upgrade = webSocketCheckHttpUpgrade();
            if (upgrade < 0) {
                return -1;
            }
            if (upgrade == 0) {
                continue;
            }
            if (gen != socketGeneration) {
                return 0;
            }
            std::string leftover;
            leftover.swap(wsInBuffer);
            if (!leftover.empty() && webSocketConsumePlain((uint8_t *) &leftover[0], leftover.size()) < 0) {
                return -1;
            }
        } else if (webSocketConsumePlain(plain, (size_t) n) < 0) {
            return -1;
        }
        if (gen != socketGeneration) {
            return 0;
        }
    }
    if (webSocketFlushCiphertext() < 0) {
        return -1;
    }
    if (tlsPendingSent < tlsPendingWrite.size()) {
        adjustWriteOp();
    }
    return 0;
}

int32_t ConnectionSocket::webSocketCheckHttpUpgrade() {
    size_t headerEnd = wsInBuffer.find("\r\n\r\n");
    if (headerEnd == std::string::npos) {
        return wsInBuffer.size() > 16 * 1024 ? -1 : 0;
    }
    size_t statusEnd = wsInBuffer.find("\r\n");
    if (wsInBuffer.compare(0, 7, "HTTP/1.") != 0 || statusEnd < 12 || wsInBuffer.compare(9, 3, "101") != 0) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) WSS handshake not accepted", this);
        return -1;
    }
    wsInBuffer.erase(0, headerEnd + 4);
    webSocketBecameReady();
    return 1;
}

void ConnectionSocket::webSocketResetFrameState() {
    wsFrameHeaderSize = 0;
    wsFrameOpcode = 0;
    wsFrameMasked = false;
    wsFramePayloadLeft = 0;
    wsFrameMaskOffset = 0;
    wsControlPayload.clear();
}

static size_t webSocketHeaderSizeFor(const uint8_t *header, size_t have) {
    if (have < 2) {
        return 2;
    }
    uint8_t lengthByte = header[1] & 0x7f;
    size_t size = 2;
    if (lengthByte == 126) {
        size += 2;
    } else if (lengthByte == 127) {
        size += 8;
    }
    if ((header[1] & 0x80) != 0) {
        size += 4;
    }
    return size;
}

int32_t ConnectionSocket::webSocketConsumePlain(uint8_t *data, size_t length) {
    size_t offset = 0;
    while (offset < length) {
        if (wsFramePayloadLeft == 0) {
            size_t need = webSocketHeaderSizeFor(wsFrameHeader, wsFrameHeaderSize);
            while (wsFrameHeaderSize < need && offset < length) {
                wsFrameHeader[wsFrameHeaderSize++] = data[offset++];
                need = webSocketHeaderSizeFor(wsFrameHeader, wsFrameHeaderSize);
            }
            if (wsFrameHeaderSize < need) {
                return 0;
            }
            uint8_t lengthByte = wsFrameHeader[1] & 0x7f;
            size_t cursor = 2;
            uint64_t payloadLength = lengthByte;
            if (lengthByte == 126) {
                payloadLength = ((uint64_t) wsFrameHeader[2] << 8) | wsFrameHeader[3];
                cursor = 4;
            } else if (lengthByte == 127) {
                payloadLength = 0;
                for (int32_t i = 0; i < 8; i++) {
                    payloadLength = (payloadLength << 8) | wsFrameHeader[2 + i];
                }
                cursor = 10;
            }
            wsFrameOpcode = wsFrameHeader[0] & 0x0f;
            wsFrameMasked = (wsFrameHeader[1] & 0x80) != 0;
            if (wsFrameMasked) {
                memcpy(wsFrameMask, wsFrameHeader + cursor, 4);
            }
            bool control = (wsFrameOpcode & 0x08) != 0;
            if (control ? payloadLength > 125 : payloadLength > 2 * 1024 * 1024) {
                if (LOGS_ENABLED) DEBUG_E("connection(%p) WSS oversized frame", this);
                return -1;
            }
            wsFrameHeaderSize = 0;
            wsFrameMaskOffset = 0;
            wsFramePayloadLeft = payloadLength;
            wsControlPayload.clear();
            if (payloadLength == 0) {
                int32_t gen = socketGeneration;
                if (webSocketFinishFrame() < 0) {
                    return -1;
                }
                if (gen != socketGeneration) {
                    return 0;
                }
                continue;
            }
        }
        size_t available = length - offset;
        size_t chunk = wsFramePayloadLeft < available ? (size_t) wsFramePayloadLeft : available;
        uint8_t *payload = data + offset;
        if (wsFrameMasked) {
            for (size_t i = 0; i < chunk; i++) {
                payload[i] ^= wsFrameMask[(wsFrameMaskOffset + i) & 3];
            }
            wsFrameMaskOffset += chunk;
        }
        offset += chunk;
        wsFramePayloadLeft -= chunk;
        if ((wsFrameOpcode & 0x08) != 0) {
            wsControlPayload.append((const char *) payload, chunk);
        } else if (wsFrameOpcode == 0x0 || wsFrameOpcode == 0x2) {
            int32_t gen = socketGeneration;
            NativeByteBuffer *buffer = BuffersStorage::getInstance().getFreeBuffer((uint32_t) chunk);
            buffer->writeBytes(payload, (uint32_t) chunk);
            buffer->rewind();
            onReceivedData(buffer);
            buffer->reuse();
            if (gen != socketGeneration) {
                return 0;
            }
        }
        if (wsFramePayloadLeft == 0) {
            int32_t gen = socketGeneration;
            if (webSocketFinishFrame() < 0) {
                return -1;
            }
            if (gen != socketGeneration) {
                return 0;
            }
        }
    }
    return 0;
}

int32_t ConnectionSocket::webSocketFinishFrame() {
    if (wsFrameOpcode == 0x8) {
        size_t size = wsControlPayload.size();
        int32_t closeCode = size >= 2 ? (((uint8_t) wsControlPayload[0] << 8) | (uint8_t) wsControlPayload[1]) : 0;
        std::string reason = size > 2 ? wsControlPayload.substr(2) : "";
        if (LOGS_ENABLED) DEBUG_D("connection(%p) WSS close code %d reason '%s'", this, closeCode, reason.c_str());
        return -1;
    }
    if (wsFrameOpcode == 0x9) {
        return webSocketWriteFrame(0xA, (const uint8_t *) wsControlPayload.data(), wsControlPayload.size());
    }
    return 0;
}

int32_t ConnectionSocket::webSocketWriteFrame(uint8_t opcode, const uint8_t *payload, size_t length) {
    uint8_t mask[4];
    RAND_bytes(mask, sizeof(mask));
    std::string frame;
    frame.reserve(length + 14);
    webSocketAppendFrameHeader(frame, opcode, length, mask);
    size_t headerSize = frame.size();
    frame.append((const char *) payload, length);
    webSocketMaskPayload((uint8_t *) &frame[headerSize], length, mask);
    int32_t w = SSL_write(ssl, frame.data(), (int32_t) frame.size());
    if (w <= 0) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) WSS SSL_write frame failed", this);
        return -1;
    }
    return 0;
}

int32_t ConnectionSocket::webSocketWriteQueuedFrame(std::string &message) {
    size_t length = message.size() - webSocketHeaderReserve;
    uint8_t mask[4];
    RAND_bytes(mask, sizeof(mask));
    std::string header;
    header.reserve(webSocketHeaderReserve);
    webSocketAppendFrameHeader(header, 0x2, length, mask);
    size_t offset = webSocketHeaderReserve - header.size();
    memcpy(&message[offset], header.data(), header.size());
    if (length > 0) {
        webSocketMaskPayload((uint8_t *) &message[webSocketHeaderReserve], length, mask);
    }
    int32_t w = SSL_write(ssl, message.data() + offset, (int32_t) (header.size() + length));
    if (w <= 0) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) WSS SSL_write frame failed", this);
        return -1;
    }
    return 0;
}

int32_t ConnectionSocket::webSocketSendOutgoing() {
    while (!wsOutQueue.empty()) {
        std::string message = std::move(wsOutQueue.front());
        wsOutQueue.erase(wsOutQueue.begin());
        if (webSocketWriteQueuedFrame(message) < 0) {
            return -1;
        }
        if (webSocketFlushCiphertext() < 0) {
            return -1;
        }
    }
    return webSocketFlushCiphertext();
}

void ConnectionSocket::webSocketBecameReady() {
    webSocketState = WebSocketStateReady;
    if (LOGS_ENABLED) DEBUG_D("connection(%p) WSS ready", this);
    if (!onConnectedSent) {
        lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
        onConnected();
        onConnectedSent = true;
    }
}

bool ConnectionSocket::webSocketTryAdopt() {
    if (!webSocketSni.empty()) {
        return false;
    }
    WarmWebSocket entry;
    bool found = false;
    {
        std::lock_guard<std::mutex> lock(wsPoolMutex);
        int64_t now = wsPoolNow();
        std::vector<WarmWebSocket> dropped;
        for (auto it = wsPool.begin(); it != wsPool.end();) {
            bool stale = now - it->created > WS_POOL_MAX_AGE_MS;
            bool dead = false;
            if (!stale) {
                char probe;
                ssize_t peek = recv(it->fd, &probe, 1, MSG_PEEK | MSG_DONTWAIT);
                dead = peek == 0;
            }
            if (stale || dead) {
                dropped.push_back(*it);
                it = wsPool.erase(it);
                continue;
            }
            if (!found && it->host == webSocketHost && it->path == webSocketPath) {
                entry = *it;
                found = true;
                it = wsPool.erase(it);
                continue;
            }
            ++it;
        }
        for (WarmWebSocket &dead : dropped) {
            wsPoolClose(dead);
        }
    }
    if (!found) {
        return false;
    }
    socketFd = entry.fd;
    ssl = entry.ssl;
    sslReadBio = entry.readBio;
    sslWriteBio = entry.writeBio;
    wsInBuffer = entry.leftover;
    webSocketState = WebSocketStateReady;
    tlsPendingWrite.clear();
    tlsPendingSent = 0;
    lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
    eventMask.events = EPOLLIN | EPOLLRDHUP | EPOLLERR | EPOLLET;
    eventMask.data.ptr = eventObject;
    if (epoll_ctl(ConnectionsManager::getInstance(instanceNum).epolFd, EPOLL_CTL_ADD, socketFd, &eventMask) != 0) {
        if (LOGS_ENABLED) DEBUG_E("connection(%p) WSS pool epoll add failed", this);
        webSocketFreeSsl();
        close(socketFd);
        socketFd = -1;
        return false;
    }
    if (LOGS_ENABLED) DEBUG_D("connection(%p) WSS pool hit %s%s", this, webSocketHost.c_str(), webSocketPath.c_str());
    // Adoption happens synchronously inside Connection::connect(), which is
    // called from Connection::sendData() while processRequestQueue() iterates
    // its request list. Reporting onConnected() here would recurse back into
    // processRequestQueue() and invalidate the outer iterator, so the ready
    // transition and the pooled plaintext are deferred to the manager queue.
    int32_t generation = socketGeneration;
    std::string leftover;
    leftover.swap(wsInBuffer);
    ConnectionsManager::getInstance(instanceNum).scheduleTask([this, generation, leftover] {
        if (socketGeneration != generation || !webSocket || onConnectedSent || socketFd < 0) {
            return;
        }
        webSocketBecameReady();
        if (socketGeneration == generation && !leftover.empty()) {
            webSocketConsumePlain(reinterpret_cast<uint8_t *>(const_cast<char *>(leftover.data())), leftover.size());
        }
    });
    return true;
}

void ConnectionSocket::onHostNameResolved(std::string host, std::string ip, bool ipv6) {
    (void) ipv6;
    ConnectionsManager::getInstance(instanceNum).scheduleTask([&, host, ip] {
        if (waitingForHostResolve == host) {
            waitingForHostResolve = "";
            if (ip.empty()) {
                if (LOGS_ENABLED) DEBUG_E("connection(%p) can't resolve host %s address via delegate", this, host.c_str());
                closeSocket(1, -1);
                return;
            }
            bool resolvedIpv6;
            if (inet_pton(AF_INET, ip.c_str(), &socketAddress.sin_addr.s_addr) == 1) {
                resolvedIpv6 = false;
            } else if (inet_pton(AF_INET6, ip.c_str(), &socketAddress6.sin6_addr.s6_addr) == 1) {
                resolvedIpv6 = true;
            } else {
                if (LOGS_ENABLED) DEBUG_E("connection(%p) can't resolve host %s address via delegate", this, host.c_str());
                closeSocket(1, -1);
                return;
            }
            if (LOGS_ENABLED) DEBUG_D("connection(%p) resolved host %s address %s via delegate", this, host.c_str(), ip.c_str());
            if (webSocket) {
                lastEventTime = ConnectionsManager::getInstance(instanceNum).getCurrentTimeMonotonicMillis();
            }
            openConnectionInternal(resolvedIpv6);
        }
    });
}
