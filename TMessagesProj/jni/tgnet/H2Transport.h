/*
 * HTTP/2 media transport for tgnet.
 *
 * One TLS + ALPN(h2) connection is shared by every media ConnectionSocket of
 * the same account that uses the same Cloudflare proxy endpoint. Each MTProto
 * packet is mapped to one POST /api request, matching Flowseal/tg-ws-proxy.
 */

#ifndef H2TRANSPORT_H
#define H2TRANSPORT_H

#include <stddef.h>
#include <stdint.h>
#include <functional>
#include <memory>
#include <mutex>
#include <string>

class ConnectionSocket;

/*
 * Shared handle between a ConnectionSocket and the endpoint thread. The
 * tgnet thread owns the socket pointer; the endpoint thread only schedules
 * work back to the tgnet thread, so the pointer is nulled on the tgnet thread
 * before the socket is destroyed.
 */
class H2ClientRef {
public:
    std::mutex mutex;
    ConnectionSocket *socket = nullptr;
    int32_t instance = 0;
    uint32_t generation = 0;
    std::string host;
    std::string path;
};

class H2Endpoint;

class H2Transport {
public:
    static std::shared_ptr<H2ClientRef> createRef(ConnectionSocket *socket, int32_t instance, uint32_t generation, const std::string &host, const std::string &path);
    static void attach(const std::shared_ptr<H2ClientRef> &ref);
    static void detach(const std::shared_ptr<H2ClientRef> &ref);
    static void submit(const std::shared_ptr<H2ClientRef> &ref, uint32_t generation, const uint8_t *data, size_t length);
    static bool available(int32_t instance, const std::string &host, const std::string &path);
    static void resetInstance(int32_t instance);
    static void scheduleDelivery(int32_t instance, std::function<void()> task);
};

#endif
