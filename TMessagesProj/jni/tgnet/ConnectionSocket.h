/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2015-2018.
 */

#ifndef CONNECTIONSOCKET_H
#define CONNECTIONSOCKET_H

#include <sys/epoll.h>
#include <netinet/in.h>
#include <string>
#include <vector>

class NativeByteBuffer;
class ConnectionsManager;
class ByteStream;
class EventObject;
class ByteArray;

struct ssl_st;
struct bio_st;

enum WebSocketState {
    WebSocketStateNone = 0,
    WebSocketStateTls = 1,
    WebSocketStateHttp = 2,
    WebSocketStateReady = 3
};

class ConnectionSocket {

public:
    ConnectionSocket(int32_t instance);
    virtual ~ConnectionSocket();

    void writeBuffer(uint8_t *data, uint32_t size);
    void writeBuffer(NativeByteBuffer *buffer);
    void openConnection(std::string address, uint16_t port, std::string secret, bool ipv6, int32_t networkType);
    void setTimeout(time_t timeout);
    time_t getTimeout();
    bool isDisconnected();
    void dropConnection();
    void setOverrideProxy(std::string address, uint16_t port, std::string username, std::string password, std::string secret);
    void setWebSocket(bool enabled, std::string host, std::string path);
    void queueWebSocketMessage(const uint8_t *data, size_t length, const uint8_t *data2 = nullptr, size_t length2 = 0, const uint8_t *data3 = nullptr, size_t length3 = 0);
    void onHostNameResolved(std::string host, std::string ip, bool ipv6);

protected:
    int32_t instanceNum;
    void onEvent(uint32_t events);
    bool checkTimeout(int64_t now);
    void resetLastEventTime();
    bool hasTlsHashMismatch();
    virtual void onReceivedData(NativeByteBuffer *buffer) = 0;
    virtual void onDisconnected(int32_t reason, int32_t error) = 0;
    virtual void onConnected() = 0;
    virtual bool hasPendingRequests() = 0;

    std::string overrideProxyUser = "";
    std::string overrideProxyPassword = "";
    std::string overrideProxyAddress = "";
    std::string overrideProxySecret = "";
    uint16_t overrideProxyPort = 1080;

private:
    ByteStream *outgoingByteStream = nullptr;
    struct epoll_event eventMask;
    struct sockaddr_in socketAddress;
    struct sockaddr_in6 socketAddress6;
    int socketFd = -1;
    time_t timeout = 12;
    bool onConnectedSent = false;
    int64_t lastEventTime = 0;
    EventObject *eventObject;
    int32_t currentNetworkType;
    bool isIpv6;
    std::string currentAddress;
    uint16_t currentPort;

    std::string waitingForHostResolve;
    bool adjustWriteOpAfterResolve;

    std::string currentSecret;
    std::string currentSecretDomain;

    bool tlsHashMismatch = false;
    bool tlsBufferSized = true;
    NativeByteBuffer *tlsBuffer = nullptr;
    ByteArray *tempBuffer = nullptr;
    size_t bytesRead = 0;
    int8_t tlsState = 0;

    uint8_t proxyAuthState;

    bool webSocket = false;
    std::string webSocketHost;
    std::string webSocketPath;
    int8_t webSocketState = WebSocketStateNone;
    struct ssl_st *ssl = nullptr;
    struct bio_st *sslReadBio = nullptr;
    struct bio_st *sslWriteBio = nullptr;
    std::string tlsPendingWrite;
    size_t tlsPendingSent = 0;
    std::string wsInBuffer;
    std::vector<std::string> wsOutQueue;
    uint8_t wsFrameHeader[14];
    size_t wsFrameHeaderSize = 0;
    uint8_t wsFrameOpcode = 0;
    uint8_t wsFrameMask[4];
    bool wsFrameMasked = false;
    uint64_t wsFramePayloadLeft = 0;
    uint64_t wsFrameMaskOffset = 0;
    std::string wsControlPayload;
    int32_t socketGeneration = 0;

    bool webSocketInitSsl();
    void webSocketFreeSsl();
    int32_t webSocketDriveHandshake();
    int32_t webSocketSendHttpUpgrade();
    int32_t webSocketOnNetworkData(uint8_t *data, size_t length);
    int32_t webSocketCheckHttpUpgrade();
    int32_t webSocketConsumePlain(uint8_t *data, size_t length);
    int32_t webSocketFinishFrame();
    void webSocketResetFrameState();
    int32_t webSocketWriteFrame(uint8_t opcode, const uint8_t *payload, size_t length);
    int32_t webSocketWriteQueuedFrame(std::string &message);
    int32_t webSocketSendOutgoing();
    int32_t webSocketFlushCiphertext();
    void webSocketBecameReady();

    int32_t checkSocketError(int32_t *error);
    void closeSocket(int32_t reason, int32_t error);
    void openConnectionInternal(bool ipv6);
    void adjustWriteOp();

    friend class EventObject;
    friend class ConnectionsManager;
    friend class Connection;
};

#endif
