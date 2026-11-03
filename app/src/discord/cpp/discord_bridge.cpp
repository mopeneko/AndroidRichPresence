#include <jni.h>
#include <algorithm>
#include <stdexcept>
#include <deque>
#include <memory>
#include <string>
#include <vector>
#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

namespace {
constexpr uint64_t applicationId = 1567132201588367360ULL;
constexpr auto redirect = "discord-1567132201588367360:/authorize/callback";
using Bytes = std::vector<uint8_t>;
void wipe(std::string& value) { std::fill(value.begin(), value.end(), '\0'); value.clear(); }
void integer(Bytes& out, uint32_t value) {
    for (int shift = 24; shift >= 0; shift -= 8) out.push_back(value >> shift);
}
void text(Bytes& out, const std::string& value) {
    integer(out, value.size()); out.insert(out.end(), value.begin(), value.end());
}
Bytes event(uint32_t type) { Bytes out; integer(out, type); return out; }
struct Bridge {
    discordpp::Client client;
    std::deque<Bytes> events;
    std::string verifier;
    bool active = true;
    bool tokenInstalled = false;
    bool lastReady = false;
    bool ready() {
        return active && tokenInstalled && client.GetStatus() == discordpp::Client::Status::Ready
            && client.IsAuthenticated();
    }
    void completed(jlong operation, bool success) {
        auto out = event(5);
        for (int shift = 56; shift >= 0; shift -= 8) out.push_back(uint64_t(operation) >> shift);
        out.push_back(success); events.push_back(std::move(out));
    }
    ~Bridge() {
        wipe(verifier);
        for (auto& bytes : events) std::fill(bytes.begin(), bytes.end(), 0);
    }
};
using Handle = std::shared_ptr<Bridge>;
Handle& bridge(jlong handle) { return *reinterpret_cast<Handle*>(handle); }
void fail(JNIEnv* env) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Discord SDK operation failed");
}
std::string input(JNIEnv* env, jbyteArray bytes) {
    auto size = env->GetArrayLength(bytes);
    if (size <= 0 || size > 32768) throw std::runtime_error("Invalid input");
    std::string value(size, '\0');
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(value.data()));
    return value;
}
discordpp::Client::TokenExchangeCallback tokenCallback(std::weak_ptr<Bridge> owner) {
    return [owner](discordpp::ClientResult result, std::string access, std::string refresh,
                   discordpp::AuthorizationTokenType type, int32_t expires, std::string) {
        auto value = owner.lock();
        if (value && value->active) {
            if (result.Successful() && type == discordpp::AuthorizationTokenType::Bearer
                    && !access.empty() && !refresh.empty() && expires > 0) {
                auto out = event(1);
                text(out, access); text(out, refresh); integer(out, expires);
                value->events.push_back(std::move(out));
            } else value->events.push_back(event(6));
        }
        wipe(access); wipe(refresh);
    };
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_cc_lem0n_androidrichpresence_discord_DiscordBridge_nativeCreate(JNIEnv* env, jobject) {
    try {
        auto value = std::make_shared<Bridge>();
        std::weak_ptr<Bridge> owner = value;
        value->client.SetTokenExpirationCallback([owner] {
            if (auto value = owner.lock(); value && value->active) value->events.push_back(event(4));
        });
        return reinterpret_cast<jlong>(new Handle(std::move(value)));
    } catch (...) { fail(env); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_cc_lem0n_androidrichpresence_discord_DiscordBridge_nativeAuthorize(JNIEnv* env, jobject, jlong handle) {
    try {
        auto value = bridge(handle);
        auto pkce = value->client.CreateAuthorizationCodeVerifier();
        value->verifier = pkce.Verifier();
        discordpp::AuthorizationArgs args;
        args.SetClientId(applicationId);
        args.SetScopes(discordpp::Client::GetDefaultPresenceScopes());
        args.SetCodeChallenge(pkce.Challenge());
        std::weak_ptr<Bridge> owner = value;
        value->client.Authorize(args, [owner](discordpp::ClientResult result, std::string code, std::string uri) {
            auto value = owner.lock();
            if (!value || !value->active) { wipe(code); return; }
            if (!result.Successful() || code.empty() || uri != redirect) {
                value->events.push_back(event(6)); wipe(value->verifier); wipe(code); return;
            }
            value->events.push_back(event(7));
            value->client.GetToken(applicationId, code, value->verifier, uri, tokenCallback(owner));
            wipe(value->verifier); wipe(code);
        });
    } catch (...) { fail(env); }
}

extern "C" JNIEXPORT void JNICALL
Java_cc_lem0n_androidrichpresence_discord_DiscordBridge_nativeRefresh(JNIEnv* env, jobject, jlong handle, jbyteArray refresh) {
    try {
        auto value = bridge(handle);
        auto token = input(env, refresh);
        value->client.RefreshToken(applicationId, token, tokenCallback(value));
        wipe(token);
    } catch (...) { fail(env); }
}

extern "C" JNIEXPORT void JNICALL
Java_cc_lem0n_androidrichpresence_discord_DiscordBridge_nativeInstall(JNIEnv* env, jobject, jlong handle, jbyteArray access) {
    try {
        auto value = bridge(handle);
        value->tokenInstalled = false;
        auto token = input(env, access);
        std::weak_ptr<Bridge> owner = value;
        value->client.UpdateToken(discordpp::AuthorizationTokenType::Bearer, token,
            [owner](discordpp::ClientResult result) {
                if (auto value = owner.lock(); value && value->active) {
                    value->tokenInstalled = result.Successful();
                    auto out = event(2); out.push_back(result.Successful());
                    value->events.push_back(std::move(out));
                }
            });
        wipe(token);
    } catch (...) { fail(env); }
}

extern "C" JNIEXPORT void JNICALL
Java_cc_lem0n_androidrichpresence_discord_DiscordBridge_nativeConnect(JNIEnv* env, jobject, jlong handle) {
    try { if (bridge(handle)->tokenInstalled) bridge(handle)->client.Connect(); }
    catch (...) { fail(env); }
}

extern "C" JNIEXPORT void JNICALL
Java_cc_lem0n_androidrichpresence_discord_DiscordBridge_nativeUpdate(
    JNIEnv* env, jobject, jlong handle, jlong operation, jbyteArray title, jboolean playing) {
    try {
        auto value = bridge(handle);
        if (!value->ready()) { value->completed(operation, false); return; }
        discordpp::Activity activity;
        activity.SetType(discordpp::ActivityTypes::Watching);
        activity.SetDetails(input(env, title));
        activity.SetState(playing ? "再生中" : "一時停止");
        std::weak_ptr<Bridge> owner = value;
        value->client.UpdateRichPresence(activity, [owner, operation](const discordpp::ClientResult& result) {
            if (auto value = owner.lock(); value && value->active) value->completed(operation, result.Successful());
        });
    } catch (...) { fail(env); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_cc_lem0n_androidrichpresence_discord_DiscordBridge_nativePoll(JNIEnv* env, jobject, jlong handle) {
    try {
        discordpp::RunCallbacks();
        auto& value = bridge(handle);
        bool ready = value->ready();
        if (ready != value->lastReady) {
            value->lastReady = ready;
            auto out = event(3); out.push_back(ready); value->events.push_back(std::move(out));
        }
        if (value->events.empty()) return nullptr;
        auto bytes = std::move(value->events.front()); value->events.pop_front();
        auto result = env->NewByteArray(bytes.size());
        if (result) env->SetByteArrayRegion(result, 0, bytes.size(), reinterpret_cast<jbyte*>(bytes.data()));
        std::fill(bytes.begin(), bytes.end(), 0);
        return result;
    } catch (...) { fail(env); return nullptr; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cc_lem0n_androidrichpresence_discord_DiscordBridge_nativeClear(JNIEnv* env, jobject, jlong handle) {
    try {
        if (!bridge(handle)->ready()) return false;
        bridge(handle)->client.ClearRichPresence();
        return true;
    } catch (...) { fail(env); return false; }
}

extern "C" JNIEXPORT void JNICALL
Java_cc_lem0n_androidrichpresence_discord_DiscordBridge_nativeClose(JNIEnv*, jobject, jlong handle) {
    auto value = bridge(handle);
    value->active = false;
    wipe(value->verifier);
    value->client.AbortAuthorize();
    value->client.Disconnect();
    delete reinterpret_cast<Handle*>(handle);
}
