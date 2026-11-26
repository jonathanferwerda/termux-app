// The P1 USB gate: submit isochronous URBs directly on the file descriptor of
// an Android UsbDeviceConnection.
//
// Java's android.hardware.usb.UsbRequest only speaks bulk and interrupt, so a
// UAC1 isochronous capture path cannot be built on it. The kernel's usbfs
// interface, reachable through the connection's own fd, can carry iso URBs;
// this file proves that on the UM2. It is a bounded diagnostic probe, not a
// driver: it sets one alternate setting, submits a few URBs, reaps for a few
// hundred milliseconds and reports what the kernel handed back. It never
// closes the fd (Java owns it) and always cancels or reaps every URB before it
// returns a buffer to anyone.
//
// The struct layouts are asserted against the NDK's own kernel header rather
// than hand-encoded, so a wrong sizeof stops the build instead of corrupting
// an ioctl.

#include <jni.h>

#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <time.h>

#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <vector>

// arm64-v8a (the only ABI this APK ships) layouts from <linux/usbdevice_fs.h>.
static_assert(sizeof(usbdevfs_urb) == 56, "usbdevfs_urb is not 56 bytes");
static_assert(sizeof(usbdevfs_ctrltransfer) == 24, "usbdevfs_ctrltransfer is not 24 bytes");
static_assert(sizeof(usbdevfs_setinterface) == 8, "usbdevfs_setinterface is not 8 bytes");
static_assert(sizeof(usbdevfs_iso_packet_desc) == 12, "usbdevfs_iso_packet_desc is not 12 bytes");

namespace {

constexpr const char* kTag = "JawnUsbIso";

long long nowNanos() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<long long>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

void sleepMicros(long micros) {
    struct timespec ts;
    ts.tv_sec = micros / 1000000;
    ts.tv_nsec = (micros % 1000000) * 1000;
    nanosleep(&ts, nullptr);
}

// Only fixed, ASCII-only error strings reach here, so no escaping is needed.
std::string failJson(const char* stage, const char* error, int err) {
    std::string j = "{\"ok\":false,\"stage\":\"";
    j += stage;
    j += "\",\"error\":\"";
    j += error;
    j += "\",\"errno\":";
    j += std::to_string(err);
    j += "}";
    return j;
}

std::string hexBytes(const unsigned char* data, int len) {
    static const char* digits = "0123456789abcdef";
    std::string out;
    out.reserve(static_cast<size_t>(len) * 2);
    for (int i = 0; i < len; i++) {
        out += digits[(data[i] >> 4) & 0x0f];
        out += digits[data[i] & 0x0f];
    }
    return out;
}

template <typename Map>
std::string mapJson(const Map& counts) {
    std::string out = "{";
    bool first = true;
    for (const auto& entry : counts) {
        if (!first) out += ",";
        first = false;
        out += "\"";
        out += std::to_string(entry.first);
        out += "\":";
        out += std::to_string(entry.second);
    }
    out += "}";
    return out;
}

std::string stringArrayJson(const std::vector<std::string>& items) {
    std::string out = "[";
    bool first = true;
    for (const std::string& item : items) {
        if (!first) out += ",";
        first = false;
        out += "\"";
        out += item;
        out += "\"";
    }
    out += "]";
    return out;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_jawnos_app_JawnUsbProbe_nativeIsoProbe(
        JNIEnv* env, jclass, jint fd, jint iface, jint alt, jint endpoint,
        jint packetSize, jint packetsPerUrb, jint urbCount, jint millis) {
    if (fd < 0) {
        return env->NewStringUTF(failJson(
                "fd", "the connection has no usable file descriptor", EBADF).c_str());
    }
    if (packetSize <= 0 || packetsPerUrb <= 0 || urbCount <= 0 || millis <= 0) {
        return env->NewStringUTF(failJson(
                "arguments", "packetSize, packetsPerUrb, urbCount and millis must be positive",
                EINVAL).c_str());
    }
    const size_t stride = static_cast<size_t>(packetsPerUrb) * static_cast<size_t>(packetSize);
    const size_t totalBytes = stride * static_cast<size_t>(urbCount);
    // The probe's fixed inputs are tiny; this only refuses a nonsense request.
    if (totalBytes > static_cast<size_t>(64) * 1024 * 1024) {
        return env->NewStringUTF(failJson(
                "arguments", "the requested isochronous buffers are unreasonably large",
                EINVAL).c_str());
    }

    // 1. Select the alternate setting from native. Java already did this
    // through UsbDeviceConnection.setInterface(); a refusal here is recorded
    // and the probe continues, because the ioctl is a cross-check, not a
    // precondition.
    usbdevfs_setinterface si;
    std::memset(&si, 0, sizeof(si));
    si.interface = static_cast<unsigned>(iface);
    si.altsetting = static_cast<unsigned>(alt);
    errno = 0;
    const int siRet = ioctl(fd, USBDEVFS_SETINTERFACE, &si);
    const int siErr = siRet < 0 ? errno : 0;

    // 2. A class control transfer on the same fd: GET_CUR SAMPLING_FREQ_CONTROL
    // on the endpoint, the same bytes the Java probe reads. It proves control
    // transfers work from native before any URB is queued.
    unsigned char ctl[3] = {0, 0, 0};
    usbdevfs_ctrltransfer ct;
    std::memset(&ct, 0, sizeof(ct));
    ct.bRequestType = 0xA2;
    ct.bRequest = 0x81;
    ct.wValue = 0x0100;
    ct.wIndex = static_cast<unsigned short>(endpoint);
    ct.wLength = 3;
    ct.timeout = 1000;
    ct.data = ctl;
    errno = 0;
    const int ctRet = ioctl(fd, USBDEVFS_CONTROL, &ct);
    const int ctErr = ctRet < 0 ? errno : 0;

    // 3. One data buffer, sized once and never resized or moved afterwards, so
    // every URB's buffer pointer stays valid until the last reap.
    std::vector<uint8_t>* data = nullptr;
    try {
        data = new std::vector<uint8_t>(totalBytes);
    } catch (...) {
        data = nullptr;
    }
    if (data == nullptr) {
        return env->NewStringUTF(failJson(
                "alloc", "could not allocate the isochronous buffers", ENOMEM).c_str());
    }

    usbdevfs_urb** urbs = new (std::nothrow) usbdevfs_urb*[urbCount]();
    if (urbs == nullptr) {
        delete data;
        return env->NewStringUTF(failJson(
                "alloc", "could not allocate the URB array", ENOMEM).c_str());
    }

    const size_t urbBytes =
            sizeof(usbdevfs_urb) + static_cast<size_t>(packetsPerUrb) * sizeof(usbdevfs_iso_packet_desc);
    bool allocOk = true;
    for (int i = 0; i < urbCount; i++) {
        urbs[i] = static_cast<usbdevfs_urb*>(calloc(1, urbBytes));
        if (urbs[i] == nullptr) {
            allocOk = false;
            break;
        }
        usbdevfs_urb* urb = urbs[i];
        urb->type = USBDEVFS_URB_TYPE_ISO;  // 0, not 1
        urb->endpoint = static_cast<unsigned char>(endpoint);
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->number_of_packets = packetsPerUrb;
        urb->buffer = data->data() + static_cast<size_t>(i) * stride;
        urb->buffer_length = static_cast<int>(stride);
        for (int p = 0; p < packetsPerUrb; p++) {
            urb->iso_frame_desc[p].length = static_cast<unsigned>(packetSize);
        }
    }
    if (!allocOk) {
        for (int i = 0; i < urbCount; i++) free(urbs[i]);
        delete[] urbs;
        delete data;
        return env->NewStringUTF(failJson(
                "alloc", "could not allocate an isochronous URB", ENOMEM).c_str());
    }

    // 4. Submit.
    int submitted = 0;
    int firstSubmitErr = 0;
    bool anySubmitFailed = false;
    std::vector<char> inFlight(static_cast<size_t>(urbCount), 0);
    int outstanding = 0;
    for (int i = 0; i < urbCount; i++) {
        errno = 0;
        const int ret = ioctl(fd, USBDEVFS_SUBMITURB, urbs[i]);
        if (ret == 0) {
            submitted++;
            inFlight[static_cast<size_t>(i)] = 1;
            outstanding++;
        } else {
            anySubmitFailed = true;
            if (firstSubmitErr == 0) firstSubmitErr = errno;
        }
    }
    if (submitted == 0) {
        // Nothing is in flight, so nothing needs cancelling and the buffers
        // can go straight back.
        for (int i = 0; i < urbCount; i++) free(urbs[i]);
        delete[] urbs;
        delete data;
        return env->NewStringUTF(failJson(
                "submiturb", "every isochronous URB submit was refused",
                firstSubmitErr).c_str());
    }

    // 5. Reap and resubmit until the deadline.
    std::map<int, int> urbStatus;
    std::map<int, int> packetLength;
    std::map<int, int> packetStatus;
    std::vector<std::string> errors;
    long long bytes = 0;
    long long frames = 0;
    int peak = 0;
    unsigned long long sumSquares = 0;
    unsigned long long samples = 0;
    int reaped = 0;
    int resubmitted = 0;
    int cancelled = 0;

    auto absorb = [&](usbdevfs_urb* urb) {
        urbStatus[urb->status]++;
        const uint8_t* base = static_cast<const uint8_t*>(urb->buffer);
        for (int p = 0; p < urb->number_of_packets; p++) {
            const usbdevfs_iso_packet_desc& desc = urb->iso_frame_desc[p];
            packetLength[static_cast<int>(desc.actual_length)]++;
            packetStatus[desc.status]++;
            if (desc.status == 0 && desc.actual_length > 0) {
                unsigned len = desc.actual_length;
                if (len > static_cast<unsigned>(packetSize)) {
                    len = static_cast<unsigned>(packetSize);
                }
                const uint8_t* pkt = base + static_cast<size_t>(p) * static_cast<size_t>(packetSize);
                const unsigned n = len / 2;  // 16-bit samples
                for (unsigned s = 0; s < n; s++) {
                    const int16_t sample = static_cast<int16_t>(
                            static_cast<uint16_t>(pkt[2 * s]) |
                            (static_cast<uint16_t>(pkt[2 * s + 1]) << 8));
                    const int magnitude = sample < 0 ? -static_cast<int>(sample)
                                                     : static_cast<int>(sample);
                    if (magnitude > peak) peak = magnitude;
                    sumSquares += static_cast<unsigned long long>(
                            static_cast<long long>(sample) * static_cast<long long>(sample));
                    samples++;
                }
            }
            bytes += static_cast<long long>(desc.actual_length);
            frames += static_cast<long long>(desc.actual_length / 4);  // 48k/16/2: 4 bytes a frame
        }
    };

    const long long deadline = nowNanos() + static_cast<long long>(millis) * 1000000LL;
    while (nowNanos() < deadline) {
        usbdevfs_urb* done = nullptr;
        errno = 0;
        const int ret = ioctl(fd, USBDEVFS_REAPURBNDELAY, &done);
        if (ret < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                sleepMicros(200);
                continue;
            }
            if (errors.size() < 8) {
                errors.push_back("reap urb: errno " + std::to_string(errno));
            }
            break;
        }
        if (done == nullptr) {
            sleepMicros(200);
            continue;
        }
        int idx = -1;
        for (int i = 0; i < urbCount; i++) {
            if (urbs[i] == done) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            if (errors.size() < 8) errors.push_back("reaped an URB that is not ours");
            continue;
        }
        if (inFlight[static_cast<size_t>(idx)]) {
            inFlight[static_cast<size_t>(idx)] = 0;
            outstanding--;
        }
        reaped++;
        absorb(done);

        if (nowNanos() < deadline) {
            // Clear the completion fields only; the URB keeps its type,
            // endpoint, flags, buffer, packet lengths and packet count.
            done->status = 0;
            done->actual_length = 0;
            done->error_count = 0;
            done->start_frame = 0;
            for (int p = 0; p < done->number_of_packets; p++) {
                done->iso_frame_desc[p].actual_length = 0;
                done->iso_frame_desc[p].status = 0;
            }
            errno = 0;
            const int again = ioctl(fd, USBDEVFS_SUBMITURB, done);
            if (again == 0) {
                inFlight[static_cast<size_t>(idx)] = 1;
                outstanding++;
                resubmitted++;
            } else if (errors.size() < 8) {
                errors.push_back("resubmit urb " + std::to_string(idx) +
                                 ": errno " + std::to_string(errno));
            }
        }
    }

    // 6. Cleanup: ask the kernel to discard whatever is still in flight, then
    // reap until it is all back. The fd stays open; Java closes it.
    for (int i = 0; i < urbCount; i++) {
        if (inFlight[static_cast<size_t>(i)]) {
            ioctl(fd, USBDEVFS_DISCARDURB, urbs[i]);
            cancelled++;
        }
    }
    int tries = 0;
    while (outstanding > 0 && tries < 200) {
        usbdevfs_urb* done = nullptr;
        errno = 0;
        const int ret = ioctl(fd, USBDEVFS_REAPURBNDELAY, &done);
        if (ret == 0 && done != nullptr) {
            int idx = -1;
            for (int i = 0; i < urbCount; i++) {
                if (urbs[i] == done) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0 && inFlight[static_cast<size_t>(idx)]) {
                inFlight[static_cast<size_t>(idx)] = 0;
                outstanding--;
            }
            reaped++;
            absorb(done);
        } else {
            tries++;
            sleepMicros(1000);
        }
    }

    const bool leaked = outstanding > 0;
    if (!leaked) {
        for (int i = 0; i < urbCount; i++) free(urbs[i]);
        delete data;
    } else {
        // The kernel still owns these URBs. Leaking them is the safe choice:
        // freeing memory the kernel may still write to is not. Java closes the
        // fd right after this call, which drops them.
        __android_log_print(ANDROID_LOG_WARN, kTag,
                            "leaving %d URB(s) to the kernel; the fd close will drop them",
                            outstanding);
    }
    delete[] urbs;

    const bool ok = reaped > 0 && bytes > 0 && !anySubmitFailed;
    if (!ok) {
        if (reaped == 0) {
            errors.push_back("no URB completed before the deadline");
        } else if (bytes == 0) {
            errors.push_back("the completed URBs carried no bytes");
        }
        if (anySubmitFailed) {
            errors.push_back("an URB submit was refused: errno " +
                             std::to_string(firstSubmitErr));
        }
    }

    const long long rms = samples > 0
            ? static_cast<long long>(std::llround(
                    std::sqrt(static_cast<double>(sumSquares) / static_cast<double>(samples))))
            : 0LL;

    std::string json;
    json.reserve(640);
    json += "{\"ok\":";
    json += ok ? "true" : "false";
    json += ",\"stage\":\"done\"";
    json += ",\"fd\":";
    json += std::to_string(static_cast<int>(fd));
    json += ",\"setinterface\":{\"iface\":";
    json += std::to_string(static_cast<int>(iface));
    json += ",\"alt\":";
    json += std::to_string(static_cast<int>(alt));
    json += ",\"ret\":";
    json += std::to_string(siRet);
    json += ",\"errno\":";
    json += std::to_string(siErr);
    json += "}";
    json += ",\"control\":{\"ret\":";
    json += std::to_string(ctRet);
    json += ",\"raw\":\"";
    json += hexBytes(ctl, 3);
    json += "\",\"errno\":";
    json += std::to_string(ctErr);
    json += "}";
    json += ",\"endpoint\":";
    json += std::to_string(static_cast<int>(endpoint));
    json += ",\"packet_size\":";
    json += std::to_string(static_cast<int>(packetSize));
    json += ",\"packets_per_urb\":";
    json += std::to_string(static_cast<int>(packetsPerUrb));
    json += ",\"urbs\":";
    json += std::to_string(static_cast<int>(urbCount));
    json += ",\"duration_ms\":";
    json += std::to_string(static_cast<int>(millis));
    json += ",\"submitted\":";
    json += std::to_string(submitted);
    json += ",\"reaped\":";
    json += std::to_string(reaped);
    json += ",\"resubmitted\":";
    json += std::to_string(resubmitted);
    json += ",\"cancelled\":";
    json += std::to_string(cancelled);
    json += ",\"urb_status\":";
    json += mapJson(urbStatus);
    json += ",\"packet_length\":";
    json += mapJson(packetLength);
    json += ",\"packet_status\":";
    json += mapJson(packetStatus);
    json += ",\"bytes\":";
    json += std::to_string(bytes);
    json += ",\"frames\":";
    json += std::to_string(frames);
    json += ",\"peak\":";
    json += std::to_string(peak);
    json += ",\"rms\":";
    json += std::to_string(rms);
    json += ",\"errors\":";
    json += stringArrayJson(errors);
    json += "}";

    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "fd=%d ep=%d pkt=%d ppu=%d urbs=%d submitted=%d reaped=%d "
                        "bytes=%lld peak=%d rms=%lld cancelled=%d ok=%d",
                        static_cast<int>(fd), static_cast<int>(endpoint),
                        static_cast<int>(packetSize), static_cast<int>(packetsPerUrb),
                        static_cast<int>(urbCount), submitted, reaped, bytes, peak, rms,
                        cancelled, ok ? 1 : 0);

    return env->NewStringUTF(json.c_str());
}
